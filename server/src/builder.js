/**
 * 后台「一键构建」:在服务器上编 App / 板子固件,编好放进构建记录,管理员点「发布」才下发。
 *
 *   开始构建 → 从主仓库 git archive 出指定分支的副本 → 副本里改版本号 → tools/tp-build.sh 编译
 *            → 产物放 data/builds/<id>/ → 状态 success
 *   发布     → 走 releases.js(和后台手工上传同一段代码)→ 状态 published
 *
 * ⚠️ 只读主仓库:只用 git archive / for-each-ref / rev-parse,绝不 checkout(别的会话在用这个工作区)。
 * ⚠️ 服务器只有 2 核 1.7G 内存,还跑着 MySQL:
 *    同一时间只跑一个构建;构建进程放进 systemd 临时 scope 限内存(超了只换出构建自己),
 *    nice/ionice 降优先级,固件 -j1。
 * ⚠️ 服务重启时正在跑的构建会被停掉并标记失败(boot 时 recover())。
 */
const fs = require('fs');
const path = require('path');
const { spawn, execFile } = require('child_process');
const db = require('./db');
const releases = require('./releases');
const { DATA_DIR, sha256File, audit } = require('./common');

// ---------------------------------------------------------------- 配置(环境变量可覆盖,测试时用)
const BUILD_REPO = process.env.BUILD_REPO || '/root/esp32-s3-hid';          // 主仓库(只读)
const BUILD_ROOT = process.env.BUILD_ROOT || '/opt/tp-build';               // 工具链
const BUILD_WORK = process.env.BUILD_WORK || path.join(BUILD_ROOT, 'work'); // 工作目录(固件编译缓存在这)
const BUILD_MEM_LIMIT = process.env.BUILD_MEM_LIMIT || '1000M';             // 构建进程内存上限
const BUILD_TIMEOUT_MS = Number(process.env.BUILD_TIMEOUT_MS) || 40 * 60 * 1000;
const DEFAULT_BRANCH = process.env.BUILD_DEFAULT_BRANCH || 'opt/stability-20261014';
const BUILDS_DIR = path.join(DATA_DIR, 'builds');                           // 每次构建一个子目录:日志 + 产物
const SCRIPT = path.join(__dirname, '..', 'tools', 'tp-build.sh');
const KEEP_BUILDS = 5;                                                      // 每类只留最近 5 次构建的日志和产物
const UNIT_PREFIX = 'tp-build-p' + (process.env.PORT || 80) + '-';         // scope 名带端口:测试实例和正式实例不撞名

const KINDS = { app: 'App', firmware: '固件' };
const VERSION_RE = /^[0-9A-Za-z._-]{1,32}$/;

/** 当前正在跑的构建(同一时间只有一个) */
let current = null;   // { id, child, unit, timer, cancelled, reason }

/** 带状态码的错误,路由层直接把 message 回给前端 */
function httpError(status, message) {
  const e = new Error(message);
  e.status = status;
  return e;
}

function gitDir() {
  const g = path.join(BUILD_REPO, '.git');
  return fs.existsSync(g) ? g : BUILD_REPO;
}

/** 跑一条命令,拿 stdout(参数用数组传,不经过 shell) */
function run(cmd, args, opt) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, Object.assign({ maxBuffer: 4 * 1024 * 1024 }, opt), (err, stdout, stderr) => {
      if (err) { err.stderr = String(stderr || ''); return reject(err); }
      resolve(String(stdout));
    });
  });
}

// ---------------------------------------------------------------- 环境 / 分支 / 版本号

/** 工具链是否装好(缺什么列出来,页面上提示去跑 setup-build-env.sh) */
function envStatus() {
  const firstMatch = (dir, file) => {
    try {
      return fs.readdirSync(dir).some(d => fs.existsSync(path.join(dir, d, file)));
    } catch (_) { return false; }
  };
  const checks = {
    app: [
      ['JDK 17', fs.existsSync(path.join(BUILD_ROOT, 'jdk-17/bin/javac'))],
      ['Android build-tools', firstMatch(path.join(BUILD_ROOT, 'android-sdk/build-tools'), 'aapt2')],
      ['Android platform', firstMatch(path.join(BUILD_ROOT, 'android-sdk/platforms'), 'android.jar')],
    ],
    firmware: [
      ['PlatformIO', fs.existsSync(path.join(BUILD_ROOT, 'pio-venv/bin/pio'))],
      ['espressif32 平台', fs.existsSync(path.join(BUILD_ROOT, 'platformio/platforms/espressif32/platform.json'))],
      ['esptool', fs.existsSync(path.join(BUILD_ROOT, 'platformio/packages/tool-esptoolpy/package.json'))],
      ['secrets.h(WiFi / 服务器地址 / 板子 token)', fs.existsSync(path.join(BUILD_ROOT, 'secrets.h'))],
    ],
  };
  const repoOk = fs.existsSync(gitDir());
  const out = {};
  for (const k of Object.keys(checks)) {
    const missing = checks[k].filter(c => !c[1]).map(c => c[0]);
    if (!repoOk) missing.unshift('代码仓库 ' + BUILD_REPO);
    out[k] = { ready: missing.length === 0, missing };
  }
  return out;
}

/** 主仓库里的本地分支(最近提交的排前面) */
async function listBranches() {
  const outp = await run('git', ['--git-dir=' + gitDir(), 'for-each-ref', '--sort=-committerdate',
    '--format=%(refname:short)%09%(objectname)%09%(committerdate:raw)%09%(subject)', 'refs/heads']);
  return outp.split('\n').filter(Boolean).map(l => {
    const [name, sha, date, ...subj] = l.split('\t');
    return { name, sha, at: Number(String(date).split(' ')[0]) * 1000, subject: subj.join('\t') };
  });
}

/** 下一个 App versionCode = max(已上架的, 构建记录里没失败的) + 1 */
async function nextVersionCode() {
  const a = await db.one('SELECT MAX(version_code) n FROM apks');
  const b = await db.one(
    `SELECT MAX(version_code) n FROM builds
     WHERE kind = 'app' AND status IN ('running','success','publishing','published')`);
  return Math.max(Number(a && a.n) || 0, Number(b && b.n) || 0) + 1;
}

// ---------------------------------------------------------------- 开始构建

/**
 * 开始一次构建(立即返回构建号,编译在后台跑)
 * @param {object} o kind / branch / versionName / channel / notes / userId / ip
 */
async function start(o) {
  if (current) throw httpError(409, `构建 #${current.id || '?'} 还没结束,同一时间只能跑一个`);
  current = { id: null };   // 先占位,挡住并发的第二个请求
  try {
    const kind = String(o.kind || '');
    if (!KINDS[kind]) throw httpError(400, 'kind 只能是 app 或 firmware');
    const env = envStatus()[kind];
    if (!env.ready) {
      throw httpError(503, `服务器上${KINDS[kind]}构建环境没装好(缺:${env.missing.join('、')}),`
        + '先在服务器上执行 bash server/tools/setup-build-env.sh');
    }
    const versionName = String(o.versionName || '').trim();
    if (!VERSION_RE.test(versionName)) {
      throw httpError(400, '版本号只能用字母、数字和 . _ -(最长 32 位)');
    }
    const branches = await listBranches();
    const br = branches.find(b => b.name === String(o.branch || ''));
    if (!br) throw httpError(400, '没有这个分支:' + o.branch);

    let versionCode = null;
    let channel = null;
    if (kind === 'app') {
      versionCode = await nextVersionCode();
    } else {
      channel = o.channel === 'beta' ? 'beta' : 'stable';
      const dup = await db.one('SELECT id FROM firmware WHERE version = ? AND channel = ?', [versionName, channel]);
      if (dup) throw httpError(409, `${channel} 渠道已经有 ${versionName} 这个版本了,换一个版本号`);
    }

    const r = await db.run(
      `INSERT INTO builds (kind, branch, commit_sha, commit_msg, version_code, version_name, channel,
                           notes, status, created_by)
       VALUES (?,?,?,?,?,?,?,?, 'running', ?)`,
      [kind, br.name, br.sha, String(br.subject).slice(0, 255), versionCode, versionName, channel,
       String(o.notes || ''), o.userId]);
    const id = r.insertId;
    current.id = id;
    await audit(o.userId, 'build_start', `#${id}`,
      `${kind} ${br.name}@${br.sha.slice(0, 7)} ${versionCode ? versionCode + '/' : ''}${versionName}`, o.ip);

    const row = await db.one('SELECT * FROM builds WHERE id = ?', [id]);
    setImmediate(() => runJob(row).catch(e => console.error('[构建] #' + id + ' 异常', e)));
    return { id, versionCode };
  } catch (e) {
    current = null;
    throw e;
  }
}

function buildDir(id) { return path.join(BUILDS_DIR, String(id)); }
function logPath(id) { return path.join(buildDir(id), 'build.log'); }

/** 产物文件名(下载时的文件名也用它) */
function artifactName(b) {
  return b.kind === 'app'
    ? `touchpad-${b.version_code}-${b.version_name}.apk`
    : `fw-${b.version_name}-ble.bin`;
}

/** 从主仓库导出某个提交到目录(git archive | tar x) */
function exportTree(sha, dest) {
  return new Promise((resolve, reject) => {
    const g = spawn('git', ['--git-dir=' + gitDir(), 'archive', sha]);
    const t = spawn('tar', ['x', '-C', dest]);
    let err = '';
    g.stderr.on('data', d => { err += d; });
    t.stderr.on('data', d => { err += d; });
    g.stdout.pipe(t.stdin);
    let gCode = null;
    g.on('close', c => { gCode = c; });
    t.on('close', c => {
      if (gCode === 0 && c === 0) resolve();
      else reject(new Error('导出代码失败:' + (err.trim() || `git=${gCode} tar=${c}`)));
    });
  });
}

/** 清空目录,但保留 keep 里列出的子项(固件的 .pio 编译缓存) */
function emptyDir(dir, keep) {
  fs.mkdirSync(dir, { recursive: true });
  for (const n of fs.readdirSync(dir)) {
    if (keep.includes(n)) continue;
    fs.rmSync(path.join(dir, n), { recursive: true, force: true });
  }
}

function hasCmd(p) { try { fs.accessSync(p, fs.constants.X_OK); return true; } catch (_) { return false; } }

/** 后台执行一次构建,结束时写回状态 */
async function runJob(b) {
  const id = b.id;
  const dir = buildDir(id);
  fs.mkdirSync(dir, { recursive: true });
  const logFd = fs.openSync(logPath(id), 'a');
  const say = s => fs.writeSync(logFd, s + '\n');
  const t0 = Date.now();
  let status = 'failed';
  let error = '';
  let size = null, sha = '', peak = null, artifact = '';

  try {
    say(`构建 #${id}  ${KINDS[b.kind]}  分支 ${b.branch}  提交 ${b.commit_sha.slice(0, 7)} ${b.commit_msg}`);
    say(`版本 ${b.version_code ? 'versionCode=' + b.version_code + ' ' : ''}${b.version_name}`
      + (b.channel ? `  渠道 ${b.channel}` : ''));
    say(`开始于 ${new Date().toLocaleString('zh-CN', { hour12: false })}`);
    say('');

    // 1. 导出代码到工作目录(App 每次全新;固件保留 .pio,第二次起只编改动的文件)
    const src = path.join(BUILD_WORK, b.kind);
    say(`[导出] ${b.branch}@${b.commit_sha.slice(0, 7)} → ${src}`);
    emptyDir(src, b.kind === 'firmware' ? ['.pio'] : []);
    await exportTree(b.commit_sha, src);

    // 2. 编译(限内存 + 低优先级)
    const out = path.join(dir, artifactName(b));
    const env = Object.assign({}, process.env, {
      KIND: b.kind, SRC: src, OUT: out, VERSION_NAME: b.version_name,
      VERSION_CODE: b.version_code ? String(b.version_code) : '', BUILD_ROOT,
    });
    let cmd = ['nice', '-n', '10', 'ionice', '-c3'];
    // GNU time 只输出一行(-v 会刷二十多行统计,把结果行挤出视野)
    if (hasCmd('/usr/bin/time')) cmd.push('/usr/bin/time', '-f', '[统计] 内存峰值 %M KB  用时 %e 秒  退出码 %x');
    cmd.push('bash', SCRIPT);
    let unit = null;
    if (process.getuid && process.getuid() === 0 && hasCmd('/usr/bin/systemd-run')) {
      unit = UNIT_PREFIX + id;
      cmd = ['/usr/bin/systemd-run', '--scope', '-q', '--unit=' + unit,
        '-p', 'MemoryLimit=' + BUILD_MEM_LIMIT].concat(cmd);
      say(`[限制] 内存上限 ${BUILD_MEM_LIMIT}(systemd scope ${unit}.scope),nice 10,ionice idle`);
    }
    say(`[编译] ${cmd.join(' ')}`);
    say('');

    const code = await new Promise((resolve) => {
      const child = spawn(cmd[0], cmd.slice(1), {
        cwd: BUILD_WORK, env, stdio: ['ignore', logFd, logFd], detached: !unit,
      });
      current.child = child;
      current.unit = unit;
      current.timer = setTimeout(() => cancel(id, null, '', '超过 ' + Math.round(BUILD_TIMEOUT_MS / 60000) + ' 分钟,自动停止'),
        BUILD_TIMEOUT_MS);
      child.on('error', e => { say('启动失败:' + e.message); resolve(-1); });
      child.on('close', c => resolve(c));
    });
    clearTimeout(current.timer);

    // 3. 收尾:看结果
    const logTail = readTail(logPath(id), 64 * 1024);
    const m = logTail.match(/\[统计\] 内存峰值 (\d+) KB/);
    if (m) peak = Number(m[1]);
    if (current.cancelled) {
      status = 'cancelled';
      error = current.reason || '已取消';
    } else if (code === 0 && fs.existsSync(out)) {
      status = 'success';
      artifact = path.basename(out);
      size = fs.statSync(out).size;
      sha = sha256File(out);
    } else {
      const lines = logTail.split('\n').filter(l => l.startsWith('✗'));
      error = lines.length ? lines[lines.length - 1].slice(1).trim() : `编译失败(退出码 ${code}),看日志`;
    }
  } catch (e) {
    error = e.message;
    say('✗ ' + e.message);
  }

  const secs = Math.round((Date.now() - t0) / 1000);
  say('');
  say(`== 结果:${{ success: '成功', failed: '失败', cancelled: '已取消' }[status]}`
    + (error ? '  ' + error : '') + `  用时 ${secs} 秒` + (peak ? `  内存峰值 ${Math.round(peak / 1024)} MB` : ''));
  fs.closeSync(logFd);

  await db.run(
    `UPDATE builds SET status = ?, error = ?, artifact = ?, size = ?, sha256 = ?, peak_mem_kb = ?,
                       finished_at = NOW() WHERE id = ?`,
    [status, String(error).slice(0, 255), artifact, size, sha, peak, id]);
  console.log(`[构建] #${id} ${b.kind} ${status} ${secs}s ${error}`);
  current = null;
  await pruneBuilds();
}

/** 读文件最后 n 字节 */
function readTail(file, n) {
  try {
    const st = fs.statSync(file);
    const fd = fs.openSync(file, 'r');
    const len = Math.min(n, st.size);
    const buf = Buffer.alloc(len);
    fs.readSync(fd, buf, 0, len, st.size - len);
    fs.closeSync(fd);
    return buf.toString('utf8');
  } catch (_) { return ''; }
}

// ---------------------------------------------------------------- 取消 / 重启恢复 / 清理

/** 停掉正在跑的构建(整个进程树) */
function cancel(id, userId, ip, reason) {
  if (!current || current.id !== Number(id) || !current.child) {
    throw httpError(409, '这个构建没在运行');
  }
  current.cancelled = true;
  current.reason = reason || '管理员取消';
  if (current.unit) {
    execFile('systemctl', ['stop', current.unit + '.scope'], () => {});
  } else {
    try { process.kill(-current.child.pid, 'SIGTERM'); } catch (_) {}
  }
  if (userId !== null && userId !== undefined) audit(userId, 'build_cancel', `#${id}`, '', ip).catch(() => {});
  return true;
}

/** 服务启动时:上次没跑完的构建停掉并标记失败;发布到一半的退回「成功」 */
async function recover() {
  const rows = await db.q("SELECT id FROM builds WHERE status = 'running'");
  for (const r of rows) {
    await new Promise(res => execFile('systemctl', ['stop', UNIT_PREFIX + r.id + '.scope'], () => res()));
  }
  if (rows.length) {
    await db.run(
      `UPDATE builds SET status = 'failed', error = '服务重启,构建被中断', finished_at = NOW()
       WHERE status = 'running'`);
    console.log(`[构建] 启动时把 ${rows.length} 个中断的构建标记为失败`);
  }
  await db.run("UPDATE builds SET status = 'success' WHERE status = 'publishing'");
}

/** 每类只留最近 KEEP_BUILDS 次构建的目录(日志 + 产物),更早的删掉,记录保留 */
async function pruneBuilds() {
  for (const kind of Object.keys(KINDS)) {
    const rows = await db.q(
      `SELECT id FROM builds WHERE kind = ? AND status <> 'running' ORDER BY id DESC`, [kind]);
    for (const r of rows.slice(KEEP_BUILDS)) {
      const d = buildDir(r.id);
      if (!fs.existsSync(d)) continue;
      fs.rmSync(d, { recursive: true, force: true });
      await db.run("UPDATE builds SET artifact = '' WHERE id = ?", [r.id]);
      console.log(`[构建] 清理旧构建 #${r.id} 的日志和产物`);
    }
  }
}

// ---------------------------------------------------------------- 发布

/** 把构建产物发布出去(写进 apks / firmware 表,手机 / 板子就能检查到更新) */
async function publish(id, userId, ip) {
  const b = await db.one('SELECT * FROM builds WHERE id = ?', [id]);
  if (!b) throw httpError(404, '没有这个构建');
  if (b.status === 'published') throw httpError(409, '已经发布过了');
  if (b.status !== 'success') throw httpError(409, '只有构建成功的才能发布');
  const file = path.join(buildDir(id), b.artifact || '');
  if (!b.artifact || !fs.existsSync(file)) throw httpError(410, '产物文件已被清理,重新构建一次');

  if (b.kind === 'app') {
    const a = await db.one('SELECT MAX(version_code) n FROM apks');
    const max = Number(a && a.n) || 0;
    if (b.version_code <= max) {
      throw httpError(409, `线上已经有 versionCode ${max} 了,本次是 ${b.version_code},`
        + '发布了手机也不会当成新版。重新构建一次(会自动取新号)');
    }
  } else {
    const dup = await db.one('SELECT id FROM firmware WHERE version = ? AND channel = ?', [b.version_name, b.channel]);
    if (dup) throw httpError(409, `${b.channel} 渠道已经有 ${b.version_name} 了`);
  }

  // 先抢占状态,防止连点两次发布两份
  const lock = await db.run("UPDATE builds SET status = 'publishing' WHERE id = ? AND status = 'success'", [id]);
  if (!lock.affectedRows) throw httpError(409, '正在发布中');
  try {
    const opt = { src: file, move: false, notes: b.notes, userId, ip, auditDetail: `构建 #${id}` };
    const r = b.kind === 'app'
      ? await releases.addApk(Object.assign(opt, { versionCode: b.version_code, versionName: b.version_name }))
      : await releases.addFirmware(Object.assign(opt, { version: b.version_name, channel: b.channel,
                                                        partsSrc: path.join(buildDir(id), 'parts') }));
    await db.run(
      "UPDATE builds SET status = 'published', published_id = ?, published_at = NOW() WHERE id = ?",
      [r.id, id]);
    await audit(userId, 'build_publish', `#${id}`,
      `${b.kind} ${b.version_code ? b.version_code + '/' : ''}${b.version_name} → id=${r.id}`, ip);
    return r;
  } catch (e) {
    await db.run("UPDATE builds SET status = 'success' WHERE id = ? AND status = 'publishing'", [id]);
    throw e;
  }
}

// ---------------------------------------------------------------- 查询

function toDto(b) {
  const ms = d => (d ? new Date(d).getTime() : 0);
  return {
    id: b.id, kind: b.kind, branch: b.branch, commit: b.commit_sha, commitMsg: b.commit_msg,
    versionCode: b.version_code, versionName: b.version_name, channel: b.channel, notes: b.notes || '',
    status: b.status, error: b.error, artifact: b.artifact, size: b.size, sha256: b.sha256,
    peakMemKb: b.peak_mem_kb, publishedId: b.published_id, createdBy: b.created_by,
    username: b.username || '',
    createdAt: ms(b.created_at), finishedAt: ms(b.finished_at), publishedAt: ms(b.published_at),
    // 固件构建带了整片刷机文件 ⇒ 没发布也能先在「USB 刷机」里刷一块板子试
    usbFlash: b.kind === 'firmware' && !!b.artifact
      && fs.existsSync(path.join(buildDir(b.id), 'parts', 'flash.json')),
  };
}

async function list(limit) {
  const rows = await db.q(
    `SELECT b.*, u.username FROM builds b LEFT JOIN users u ON u.id = b.created_by
     ORDER BY b.id DESC LIMIT ${Math.min(Math.max(Number(limit) || 30, 1), 100)}`);
  return rows.map(toDto);
}

async function get(id) {
  const b = await db.one(
    'SELECT b.*, u.username FROM builds b LEFT JOIN users u ON u.id = b.created_by WHERE b.id = ?', [id]);
  return b ? toDto(b) : null;
}

/** 从 offset 字节开始读日志(前端轮询追加显示) */
function readLog(id, offset) {
  const file = logPath(id);
  if (!fs.existsSync(file)) return null;
  const size = fs.statSync(file).size;
  const from = Math.min(Math.max(Number(offset) || 0, 0), size);
  let len = Math.min(size - from, 256 * 1024);
  const buf = Buffer.alloc(len);
  const fd = fs.openSync(file, 'r');
  fs.readSync(fd, buf, 0, len, from);
  fs.closeSync(fd);
  len = completeUtf8Len(buf, len);
  return { text: buf.slice(0, len).toString('utf8'), offset: from + len, size };
}

/**
 * 去掉末尾不完整的 UTF-8 字符(按字节分段读,中文可能被切成两半;
 * 剩下的字节留给下一次读,不然前端会显示乱码)
 */
function completeUtf8Len(buf, len) {
  for (let back = 1; back <= 3 && back <= len; back++) {
    const c = buf[len - back];
    if ((c & 0xC0) === 0x80) continue;                 // 续字节,接着往前找首字节
    const need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
    return need > back ? len - back : len;
  }
  return len;
}

function artifactPath(b) {
  return b.artifact ? path.join(buildDir(b.id), b.artifact) : '';
}

function runningId() { return current ? current.id : null; }

module.exports = {
  envStatus, listBranches, nextVersionCode, start, cancel, recover, publish,
  list, get, readLog, artifactPath, runningId, pruneBuilds,
  DEFAULT_BRANCH, BUILD_REPO,
};
