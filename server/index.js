/**
 * 触摸注入板 · 控制中心(MySQL 版)
 * ══════════════════════════════════════════════════════════════════
 *  存储:MySQL 8(touchpad 库,12 张表)
 *  账号:数据库里的 users 表,scrypt 哈希,会话存库
 *
 *  四组接口:
 *    1. /api/v1/board/*   板子:心跳、查固件、下固件
 *    2. /api/v1/app/*     手机 App:查 APK 更新、上报信息
 *    3. /api/admin/*      管理接口(要登录)
 *    4. /admin/*          网页看板 + 登录页
 * ══════════════════════════════════════════════════════════════════
 */
// ══════════════════════════════════════════════════════════════════════
//  全局异常兜底
//
//  ⚠️ 血泪教训:某个接口里一句 SQL 报错(表排序规则不一致),
//     未捕获的 Promise 异常**直接把 node 进程杀掉**了 ——
//     整个控制中心(看板 + 心跳 + OTA)全部停摆,而且 systemd 没配重启。
//
//     一个坏请求绝对不该让整站下线。这里兜住所有未捕获异常:
//     记日志、继续服务。真正的错误仍然能在 journalctl 里看到。
// ══════════════════════════════════════════════════════════════════════
process.on('unhandledRejection', (err) => {
  console.error('[未捕获的 Promise 异常]', err && err.stack ? err.stack : err);
});
process.on('uncaughtException', (err) => {
  console.error('[未捕获的异常]', err && err.stack ? err.stack : err);
});

const express = require('express');
const multer = require('multer');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const http = require('http');

const db = require('./src/db');
const auth = require('./src/auth');
const { encrypt, decrypt, safeEqual } = require('./src/crypto');

const PORT = process.env.PORT || 80;
const PUBLIC_DIR = path.join(__dirname, 'public');
const DATA_DIR = path.join(__dirname, 'data');
const FW_DIR = path.join(DATA_DIR, 'firmware');
const APK_DIR = path.join(DATA_DIR, 'apk');

for (const d of [DATA_DIR, FW_DIR, APK_DIR]) {
  if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true });
}

const HEARTBEAT_KEEP_DAYS = 7;
// 板子每 1 小时心跳一次,给 30 分钟容错
// 在线判定窗口。
// ⚠️ 必须**小于心跳间隔**才有意义 —— 以前是 90 分钟而心跳 60 分钟,
//    窗口比心跳还大 ⇒ **板子拔电也永远显示"在线"**(用户实测反馈)。
//    现在:心跳 2 分钟一次,窗口 5 分钟(容忍丢 2 次)。
/** 进程启动时刻 —— 主页用它算「服务运行了多久」 */
const BOOT_AT = Date.now();

const ONLINE_MS = 5 * 60 * 1000;

let boardToken = '';
let appToken = '';

// ---------------------------------------------------------------- 工具
const json = (res, code, obj) => res.status(code).type('application/json').send(JSON.stringify(obj));
const sha256File = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');
const clientIp = (req) => (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '')
  .toString().split(',')[0].trim();

function parseCookies(req) {
  const out = {};
  (req.get('Cookie') || '').split(';').forEach(kv => {
    const i = kv.indexOf('=');
    if (i > 0) out[kv.slice(0, i).trim()] = decodeURIComponent(kv.slice(i + 1).trim());
  });
  return out;
}

// ---------------------------------------------------------------- 公网 IP 定位
// 板子在内网(192.168.x),它自己的 IP 没有意义。
// **服务端能从心跳的源地址看到它所在网络的公网 IP** —— 那才是能定位的。
// 用 ip-api.com(免费、支持中文),但**必须缓存**:限速 45 次/分钟,
// 而板子 2 分钟一次心跳,不缓存会被限速。
const geoCache = new Map();          // pubIp -> { at, data }
const GEO_TTL_MS = 24 * 3600 * 1000; // 同一个 IP 24 小时只查一次

function isPrivateIp(ip) {
  if (!ip) return true;
  const s = String(ip).replace(/^::ffff:/, '');
  return s === '127.0.0.1' || s === '::1' ||
         s.startsWith('10.') || s.startsWith('192.168.') ||
         /^172\.(1[6-9]|2\d|3[01])\./.test(s) || s.startsWith('169.254.');
}

/**
 * 查一个公网 IP 的地理位置。
 *
 * ⚠️ 这里**不能用 fetch** —— 服务器是 Node v16,`fetch` 是 Node 18 才有的全局函数,
 *    实测报 `fetch is not defined`,定位一直是空的。改用原生 http.get。
 */
function geoLookup(pubIp) {
  return new Promise((resolve) => {
    if (!pubIp || isPrivateIp(pubIp)) return resolve(null);
    const hit = geoCache.get(pubIp);
    if (hit && Date.now() - hit.at < GEO_TTL_MS) return resolve(hit.data);

    const url = 'http://ip-api.com/json/' + encodeURIComponent(pubIp)
      + '?lang=zh-CN&fields=status,country,regionName,city,isp,lat,lon';
    let done = false;
    const finish = (data) => {
      if (done) return;
      done = true;
      geoCache.set(pubIp, { at: Date.now(), data });   // 失败也缓存,别打爆限速
      resolve(data);
    };
    const req = http.get(url, { timeout: 6000 }, (r) => {
      let buf = '';
      r.setEncoding('utf8');
      r.on('data', (c) => { buf += c; });
      r.on('end', () => {
        try {
          const j = JSON.parse(buf);
          if (j.status !== 'success') throw new Error(j.message || '查询失败');
          finish({ country: j.country, region: j.regionName, city: j.city,
                   isp: j.isp, lat: j.lat, lon: j.lon });
        } catch (e) {
          console.warn('[geo] 解析失败', pubIp, e.message);
          finish(null);
        }
      });
    });
    req.on('timeout', () => { req.destroy(); console.warn('[geo] 超时', pubIp); finish(null); });
    req.on('error', (e) => { console.warn('[geo] 出错', pubIp, e.message); finish(null); });
  });
}

const isOnline = (lastSeen) =>
  lastSeen && (Date.now() - new Date(lastSeen).getTime()) < ONLINE_MS;

/** 这台板子该不该拿到某个固件版本(黑白名单 + 指定版本) */
async function firmwareForBoard(mac, boardFw, channel) {
  const latest = await db.one(
    'SELECT * FROM firmware WHERE channel = ? ORDER BY created_at DESC LIMIT 1',
    [channel || 'stable']);
  if (!latest) return null;

  const pol = await db.one('SELECT * FROM policies WHERE mac = ?', [mac]);
  if (pol) {
    if (pol.mode === 'deny') return null;                 // 黑名单:永不升级
    if (pol.target) {                                     // 指定版本
      const t = await db.one('SELECT * FROM firmware WHERE id = ?', [pol.target]);
      if (t && t.version !== boardFw) return t;
      return null;
    }
  }
  return latest.version === boardFw ? null : latest;
}

async function audit(userId, action, target, detail, ip) {
  try {
    await db.run(
      'INSERT INTO audit_log (user_id, action, target, detail, ip) VALUES (?,?,?,?,?)',
      [userId || null, action, String(target || '').slice(0, 128),
       String(detail || '').slice(0, 2000), String(ip || '').slice(0, 64)]);
  } catch (e) { /* 审计失败不影响主流程 */ }
}

// ---------------------------------------------------------------- 鉴权

function needBoard(req, res, next) {
  const t = req.get('X-Token') || req.query.token || '';
  if (safeEqual(t, boardToken)) return next();
  return json(res, 401, { ok: false, message: '板子 token 不对' });
}

function needApp(req, res, next) {
  const t = req.get('X-Token') || req.query.token || '';
  if (safeEqual(t, appToken)) return next();
  return json(res, 401, { ok: false, message: 'App token 不对' });
}

function needLogin(req, res, next) {
  const token = parseCookies(req).tp_sess || req.get('X-Session') || req.query.s || '';
  auth.userBySession(token).then(u => {
    if (!u) {
      if (req.query.s || req.path.startsWith('/api/')) {
        return json(res, 401, { ok: false, message: '未登录或会话已过期' });
      }
      return res.redirect('/admin/login');
    }
    req.user = u;
    req.sessionToken = token;
    next();
  }).catch(e => {
    console.error('鉴权失败:', e.message);
    res.status(500).send('鉴权失败');
  });
}

function needAdminRole(req, res, next) {
  if (req.user && req.user.role === 'admin') return next();
  return json(res, 403, { ok: false, message: '需要管理员权限' });
}

// ---------------------------------------------------------------- 上传
const uploadFw = multer({ dest: FW_DIR, limits: { fileSize: 8 * 1024 * 1024 } });
const uploadApk = multer({ dest: APK_DIR, limits: { fileSize: 64 * 1024 * 1024 } });

// ================================================================ 应用
const app = express();
app.use(express.json({ limit: '2mb' }));
app.use(express.urlencoded({ extended: true, limit: '2mb' }));

app.use((req, res, next) => {
  const t0 = Date.now();
  res.on('finish', () => {
    if (req.path === '/api/v1/board/heartbeat') return;      // 心跳太频繁,不记
    if (req.path.startsWith('/admin') && !req.path.startsWith('/api/')) return;
    console.log(`${new Date().toISOString()} ${req.method} ${req.originalUrl} ${res.statusCode} ${Date.now() - t0}ms`);
  });
  next();
});

// ================================================================ 板子接口

app.get('/api/v1/board/health', (req, res) => json(res, 200, { ok: true, ts: Date.now() }));

/**
 * 把一份"板子状态报告"写进数据库。
 *
 * ⚠️ 抽出来是因为有**两个来源**:
 *   ① 板子自己心跳(POST /api/v1/board/heartbeat)—— 板子有网时
 *   ② **App 代报**(POST /api/v1/app/board-relay)—— 板子连不上网时
 *      典型场景:板子只能开热点(公司网络要认证),自己上不了网,
 *      但手机连着板子热点、手机自己有蜂窝 —— 让手机帮忙中转。
 *
 * 参数:
 *   b      板子状态(同心跳 body)
 *   mac    板子 MAC(小写)
 *   pubIp  公网 IP(板子自报时是 req 源地址;App 代报时留空,不覆盖)
 *   doGeo  要不要做地理定位(只有知道真实公网 IP 时才做)
 */
async function applyBoardReport(b, mac, pubIp, doGeo) {

  const old = await db.one('SELECT * FROM boards WHERE mac = ?', [mac]);
  const wifi = b.wifi || {};
  const passEnc = wifi.pass ? encrypt(wifi.pass) : (old ? old.wifi_pass_enc : null);

  await db.run(
  `INSERT INTO boards
    (mac, name, fw, ip, channel, heap, uptime, hid, flows, running,
     next_name, next_at, wifi_ssid, wifi_rssi, wifi_channel, wifi_pass_enc, last_seen)
   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())
   ON DUPLICATE KEY UPDATE
     name=VALUES(name), fw=VALUES(fw), ip=VALUES(ip), channel=VALUES(channel),
     heap=VALUES(heap), uptime=VALUES(uptime), hid=VALUES(hid), flows=VALUES(flows),
     running=VALUES(running), next_name=VALUES(next_name), next_at=VALUES(next_at),
     wifi_ssid=VALUES(wifi_ssid), wifi_rssi=VALUES(wifi_rssi),
     wifi_channel=VALUES(wifi_channel), wifi_pass_enc=VALUES(wifi_pass_enc),
     last_seen=NOW()`,
  [mac, b.name || (old ? old.name : '') || '', b.fw || '', b.ip || '',
   b.channel || 'stable', b.heap || 0, b.uptime || 0, b.hid ? 1 : 0,
   b.flows || 0, b.running || '',
   (b.next && b.next.name) || '', (b.next && b.next.at) || 0,
   wifi.ssid || (old ? old.wifi_ssid : '') || '', wifi.rssi || 0,
   wifi.channel || 0, passEnc]);

  // ---- 公网 IP + 地理位置 ----
  // 板子在内网,它报的 ip 是 192.168.x —— 没有定位意义。
  // **服务端从心跳的源地址看到的才是它所在网络的公网 IP**。
  // pubIp 由调用方给(板子自报时取源地址;App 代报时留空)
  if (doGeo && pubIp && !isPrivateIp(pubIp)) {
  await db.run('UPDATE boards SET pub_ip = ? WHERE mac = ?', [pubIp, mac])
    .catch(() => {});
  // 定位要发网络请求,**不阻塞心跳响应** —— 慢就慢,别让板子等
  geoLookup(pubIp).then(g => {
    if (!g) return;
    return db.run(
      `UPDATE boards SET geo_country=?, geo_region=?, geo_city=?, geo_isp=?,
                         geo_lat=?, geo_lon=?, geo_at=NOW() WHERE mac=?`,
      [g.country || '', g.region || '', g.city || '', g.isp || '',
       g.lat || 0, g.lon || 0, mac]);
  }).catch(e => console.warn('[geo] 写库失败', e.message));
  }

  // 流程名字 + 内容:整表替换(简单可靠)
  if (Array.isArray(b.flowNames)) {
  const texts = (b.flowTexts && typeof b.flowTexts === 'object') ? b.flowTexts : {};
  const oldRows = await db.q('SELECT name, text FROM board_flows WHERE mac = ?', [mac]);
  const oldMap = {};
  for (const r of oldRows) oldMap[r.name] = r.text;

  await db.run('DELETE FROM board_flows WHERE mac = ?', [mac]);
  for (const n of b.flowNames.slice(0, 30)) {
    const nm = String(n || '').slice(0, 64);
    if (!nm) continue;
    const tx = (typeof texts[nm] === 'string') ? texts[nm] : null;
    await db.run('INSERT INTO board_flows (mac, name, text) VALUES (?,?,?)',
      [mac, nm, tx]);
    // 内容变了 → 存一份历史(误删/改错都能翻回来)
    // 只在**旧内容非空**且确实变了的时候存历史 —— 否则会存一堆 NULL
    if (tx && oldMap[nm] && oldMap[nm] !== tx) {
      await db.run('INSERT INTO flow_history (mac, name, text) VALUES (?,?,?)',
        [mac, nm, oldMap[nm]]);
    }
  }
  // 只留最近 10 版
  await db.run(
    `DELETE FROM flow_history WHERE mac = ? AND id NOT IN (
       SELECT id FROM (
         SELECT id FROM flow_history WHERE mac = ?
         ORDER BY saved_at DESC LIMIT 10
       ) t)`, [mac, mac]);
  }

  if (b.ip && (!old || old.ip !== b.ip)) {
  await db.run('INSERT IGNORE INTO board_ips (mac, ip) VALUES (?,?)', [mac, b.ip]);
}

  // 心跳历史(只留最近若干天;按 2% 概率顺手清理,别每次心跳都扫表)
  await db.run(
    'INSERT INTO heartbeats (mac, heap, uptime, hid, flows, running) VALUES (?,?,?,?,?,?)',
    [mac, b.heap || 0, b.uptime || 0, b.hid ? 1 : 0, b.flows || 0, b.running || '']);
  if (Math.random() < 0.02) {
    await db.run('DELETE FROM heartbeats WHERE ts < DATE_SUB(NOW(), INTERVAL ? DAY)',
      [HEARTBEAT_KEEP_DAYS]);
  }

  // 有没有新固件要推给这块板子
  return await firmwareForBoard(mac, b.fw || '', b.channel || 'stable');
}

app.post('/api/v1/board/heartbeat', needBoard, async (req, res) => {
  try {
    const b = req.body || {};
    const mac = String(b.mac || '').toLowerCase().trim();
    if (!mac) return json(res, 400, { ok: false, message: '缺少 mac' });
    const pubIp = clientIp(req).replace(/^::ffff:/, '');
    const fw = await applyBoardReport(b, mac, pubIp, true);

    // ⚠️ **这是唯一的"服务器 → 板子"下行通道**。
    //    板子在内网、服务端够不到它,只能"板子来问、顺路捎命令回去"。
    //    等板子下次心跳(2 分钟)才把 LED 命令带下去。取完立刻删,避免重复执行。
    let led;
    try {
      const [c] = await db.q('SELECT v FROM settings WHERE k = ? LIMIT 1', ['led:' + mac]);
      if (c && c.v) {
        led = JSON.parse(c.v);
        await db.run('DELETE FROM settings WHERE k = ?', ['led:' + mac]);
      }
    } catch (e) { /* 没有命令就算了 */ }

    json(res, 200, {
      ok: true,
      serverTime: Date.now(),
      firmware: fw || { hasUpdate: false },
      led,
    });
  } catch (e) {
    console.error('心跳失败:', e);
    json(res, 500, { ok: false, message: e.message });
  }
});

/**
 * 板子报"流程事件"(例如抢到票了)。
 *
 * 用法:流程里写 `notify 抢到了 快去付款` —— 板子执行到这一步就 POST 过来。
 * 为什么要走服务器:板子在内网、人在外面,只能靠服务器中转 + 推送。
 */
app.post('/api/v1/board/event', needBoard, async (req, res) => {
  try {
    const p = req.body || {};
    const mac = String(p.mac || '').toLowerCase().trim();
    if (!mac) return json(res, 400, { ok: false, message: '缺少 mac' });
    const type = String(p.type || 'flow_event').slice(0, 32);
    const flow = String(p.flow || '').slice(0, 64);
    const message = String(p.message || '').slice(0, 255);
    if (!message) return json(res, 400, { ok: false, message: '缺少 message' });

    const r = await db.run(
      'INSERT INTO board_events (mac, type, flow, message) VALUES (?,?,?,?)',
      [mac, type, flow, message]);
    console.log(`[event] ${mac} ${type} ${flow ? '(' + flow + ') ' : ''}${message}`);

    // 推送(有配 webhook 才发),不阻塞板子的响应
    notifyWebhook({ mac, type, flow, message, id: r.insertId })
      .catch(e => console.warn('[push] 失败', e.message));

    json(res, 200, { ok: true, id: r.insertId });
  } catch (e) {
    console.error('[event] 出错', e.message);
    json(res, 500, { ok: false, message: e.message });
  }
});

/**
 * 把事件推到一个 webhook(飞书 / 企业微信 / Bark / Server酱 都行)。
 *
 * 地址存在 `settings` 表里:`event_webhook`。
 * 没配就什么也不做 —— 不强依赖。
 */
async function notifyWebhook(ev) {
  let url = '';
  try {
    const row = await db.one("SELECT v FROM settings WHERE k = 'event_webhook'");
    url = row ? String(row.v || '') : '';
  } catch (e) { return; }
  if (!url) return;

  const text = `🔔 板子事件\n${ev.mac}\n${ev.flow ? '流程:' + ev.flow + '\n' : ''}${ev.message}`;
  // 飞书/企业微信都是 {"msgtype":"text","text":{"content":"..."}}
  const payload = JSON.stringify({ msgtype: 'text', text: { content: text } });

  await new Promise((resolve, reject) => {
    const u = new URL(url);
    const mod = u.protocol === 'https:' ? require('https') : http;
    const r = mod.request({
      hostname: u.hostname, port: u.port || (u.protocol === 'https:' ? 443 : 80),
      path: u.pathname + u.search, method: 'POST',
      headers: { 'Content-Type': 'application/json',
                 'Content-Length': Buffer.byteLength(payload) },
      timeout: 6000,
    }, (resp) => { resp.resume(); resolve(); });
    r.on('timeout', () => { r.destroy(); reject(new Error('超时')); });
    r.on('error', reject);
    r.write(payload);
    r.end();
  });
}

app.get('/api/v1/board/firmware/download/:id', needBoard, async (req, res) => {
  const f = await db.one('SELECT * FROM firmware WHERE id = ?', [req.params.id]);
  if (!f) return res.status(404).send('没有这个固件');
  const p = path.join(FW_DIR, f.file);
  if (!fs.existsSync(p)) return res.status(410).send('固件文件已丢失');
  res.type('application/octet-stream').sendFile(p);
});

// ================================================================ App 接口

app.get('/api/v1/app/version', async (req, res) => {
  const code = parseInt(req.query.code || '0', 10) || 0;
  const latest = await db.one('SELECT * FROM apks ORDER BY version_code DESC LIMIT 1');
  if (!latest) return json(res, 200, { ok: true, hasUpdate: false });
  json(res, 200, {
    ok: true,
    hasUpdate: latest.version_code > code,
    versionCode: latest.version_code,
    versionName: latest.version_name,
    size: latest.size, sha256: latest.sha256, notes: latest.notes || '',
    url: `http://${req.headers.host}/api/v1/app/download/${latest.id}`,
  });
});

app.get('/api/v1/app/download/:id', async (req, res) => {
  const a = await db.one('SELECT * FROM apks WHERE id = ?', [req.params.id]);
  if (!a) return res.status(404).send('没有这个 APK');
  const p = path.join(APK_DIR, a.file);
  if (!fs.existsSync(p)) return res.status(410).send('APK 文件已丢失');
  res.type('application/vnd.android.package-archive').sendFile(p);
});

app.post('/api/v1/app/phone', needApp, async (req, res) => {
  try {
    const p = req.body || {};
    const id = String(p.deviceId || '').trim();
    if (!id) return json(res, 400, { ok: false, message: '缺少 deviceId' });
    const w = p.wifi || {};
    await db.run(
      `INSERT INTO phones (device_id, name, model, android, app_version,
                           wifi_ssid, wifi_rssi, last_seen)
       VALUES (?,?,?,?,?,?,?,NOW())
       ON DUPLICATE KEY UPDATE
         name=VALUES(name), model=VALUES(model), android=VALUES(android),
         app_version=VALUES(app_version),
         wifi_ssid=IF(VALUES(wifi_ssid)='', wifi_ssid, VALUES(wifi_ssid)),
         wifi_rssi=VALUES(wifi_rssi), last_seen=NOW()`,
      [id, p.name || '', p.model || '', p.android || '', p.appVersion || '',
       w.ssid || '', w.rssi || 0]);
    json(res, 200, { ok: true, serverTime: Date.now() });
  } catch (e) {
    json(res, 500, { ok: false, message: e.message });
  }
});

app.post('/api/v1/app/apps', needApp, async (req, res) => {
  try {
    const p = req.body || {};
    const id = String(p.deviceId || '').trim();
    if (!id) return json(res, 400, { ok: false, message: '缺少 deviceId' });
    const list = Array.isArray(p.apps) ? p.apps.slice(0, 800) : [];

    await db.run('DELETE FROM phone_apps WHERE device_id = ?', [id]);
    for (let i = 0; i < list.length; i += 100) {
      const chunk = list.slice(i, i + 100).map(a => [
        id, String(a.pkg || '').slice(0, 128),
        String(a.name || '').slice(0, 128), a.system ? 1 : 0]);
      await db.getPool().query(
        'INSERT IGNORE INTO phone_apps (device_id, pkg, name, is_system) VALUES ?', [chunk]);
    }
    await db.run('UPDATE phones SET last_seen = NOW() WHERE device_id = ?', [id]);
    json(res, 200, { ok: true, count: list.length });
  } catch (e) {
    json(res, 500, { ok: false, message: e.message });
  }
});

/**
 * App 上报自己的定时任务。
 *
 * 为什么要 App 报:定时存在 App 的本地设置里,板子不知道。
 * 只有 App 报上来,看板才能显示"下次 XX 时候执行"。
 */
/**
 * App 代板子上报。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  为什么需要
 *
 *  板子可能**上不了网**,典型场景:公司网络要认证(飞连/802.1X),
 *  ESP32 做不了 ⇒ 板子只能开热点 ⇒ 板子自己发不了心跳。
 *  这时候云端就看不到:在线状态、公网 IP、流程备份(误删的保险)。
 *
 *  但**手机是有网的**(连板子热点时 Android 会保留蜂窝)。
 *  所以让 App 把板子的状态**中转**上来 —— 数据一样进库,
 *  只是来源从"板子 TCP 源地址"变成"手机上报"。
 *
 *  公网 IP:App 代报时**留空**(手机的公网 IP ≠ 板子的),
 *  避免把手机的位置错记到板子头上。
 * ══════════════════════════════════════════════════════════════════════
 */
/**
 * App 上报**连接方式** —— 蓝牙 / USB 直插 / WiFi。
 *
 * 为什么单独一个接口:连接方式会**随时变**(拔线、蓝牙断、换网),
 * 用户最关心的就是"现在到底通没通"。所以变化时立刻报,不等 30 分钟的 relay。
 *
 * ⚠️ 注意两条链路(见 App 端 Link.java):
 *    · HID 链路(注入触摸)   = ble / usb
 *    · 控制链路(App 读写板子) = 目前只能是 wifi
 *   所以 mode 可能是 "ble",但控制其实还得靠 wifi。
 */
app.post('/api/v1/app/link', needApp, async (req, res) => {
  try {
    const p = req.body || {};
    const mac = String(p.boardMac || '').toLowerCase().trim();
    if (!mac) return json(res, 400, { ok: false, message: '缺少 boardMac' });
    await db.run(
      `UPDATE boards SET link_mode = ?, link_hid = ?, link_by = ?, link_at = NOW()
       WHERE mac = ?`,
      [String(p.mode || '').slice(0, 16), String(p.hid || '').slice(0, 64),
       String(p.deviceId || '').slice(0, 64), mac]);
    console.log(`[link] ${mac} -> ${p.mode}`);
    json(res, 200, { ok: true });
  } catch (e) {
    console.error('[link] 出错', e.message);
    json(res, 500, { ok: false, message: e.message });
  }
});

app.post('/api/v1/app/board-relay', needApp, async (req, res) => {
  try {
    const p = req.body || {};
    const mac = String(p.boardMac || '').toLowerCase().trim();
    if (!mac) return json(res, 400, { ok: false, message: '缺少 boardMac' });
    const st = p.status || {};

    // 组装成和板子心跳一样的结构
    const b = {
      mac,
      name: p.boardName || st.name || '',
      fw: st.fw || '',
      ip: st.ip || '',
      channel: st.channel || 'stable',
      heap: st.heap || 0,
      uptime: st.uptime || 0,
      hid: !!st.hid,
      flows: st.scriptCount || 0,
      running: (st.run && st.run.running) ? (st.run.lastName || '') : '',
      wifi: st.wifi || {},
      flowNames: Array.isArray(p.flowNames) ? p.flowNames : undefined,
      flowTexts: (p.flowTexts && typeof p.flowTexts === 'object') ? p.flowTexts : undefined,
    };

    // pubIp 留空、doGeo=false —— 手机的公网 IP 不能代表板子
    await applyBoardReport(b, mac, '', false);

    // ⚠️⚠️ **把服务器存的板子命令捎回去**。
    //
    //     板子连不上服务器时(比如只能开热点),它**永远收不到心跳响应**,
    //     于是后台"点灯"存进 settings 的命令**没人来取** ——
    //     后台按钮看着成功,板子毫无反应(实测就是这个断层)。
    //
    //     App 和板子有直连(WiFi/蓝牙控制通道),又和服务器有网,
    //     所以让 App 当中转:代报的同时把命令取走,再由 App 下发给板子。
    let led;
    try {
      const [c] = await db.q('SELECT v FROM settings WHERE k = ? LIMIT 1', ['led:' + mac]);
      if (c && c.v) {
        led = JSON.parse(c.v);
        await db.run('DELETE FROM settings WHERE k = ?', ['led:' + mac]);
        console.log(`[relay] 顺路捎给板子一条命令:${c.v}`);
      }
    } catch (e) { /* 没有命令就算了 */ }

    console.log(`[relay] App 代报板子 ${mac}:${b.flows} 个流程,${b.flowNames ? b.flowNames.length : 0} 个名字`);
    json(res, 200, { ok: true, led });
  } catch (e) {
    console.error('[relay] 出错', e.message);
    json(res, 500, { ok: false, message: e.message });
  }
});

// ============================================================================
//  流程同步(服务器为权威 + App 本地缓存)
//
//  背景 / 为什么要这么设计
//  ────────────────────────────────────────────────────────────────────────
//  原来「流程」有三处真相,谁也不服谁:
//    ① 板子的 spiffs   = 流程正文(权威)
//    ② App 的 SharedPreferences = 流程状态(权威)
//    ③ 服务器 board_flows = **只读备份**(只收不发)
//  ⇒ 换手机就丢状态,换板子就丢流程,群控更无从谈起。
//
//  现在服务器是**权威**,App 和板子都是**缓存**:
//    · App 改动 → 乐观地本地先生效 → 同时推服务器(带 baseVer)
//    · 服务器版本变了 → App 启动时拉增量 → 写回本地和板子
//    · 服务器够不到板子(内网)⇒ 由 App 中转,和点灯那条路一样
//
//  ⚠️ 冲突策略:**最后写入者赢(LWW)+ 服务器保留版本号**。
//     不做自动合并 —— 自动合并是另一个量级的复杂度,
//     而现在只有一两个人在用,冲突概率极低。
//     真冲突了返回 409 + 服务器当前内容,让 App 决定。
//
//  ⚠️ owner 现在用 **deviceId** —— 因为 App 还没有登录(第 ⑦ 项)。
//     等注册登录做完,owner 换成用户 id,历史流程不会有归属问题
//     (owner 只是个字符串,改成 'user:123' 就行)。
// ============================================================================

/** 从请求里取 owner(现在=设备号) */
function flowOwner(req) {
  const o = String((req.body && req.body.deviceId) || req.query.deviceId || '').trim();
  return o.slice(0, 64);
}

// ---- 拉增量:只返回比 since 新的 ----
app.get('/api/v1/app/flows', needApp, async (req, res) => {
  try {
    const owner = flowOwner(req);
    if (!owner) return json(res, 400, { ok: false, message: '缺少 deviceId' });
    const since = Math.max(Number(req.query.since) || 0, 0);

    const [mx] = await db.q(
      'SELECT COALESCE(MAX(ver),0) AS v FROM flows WHERE owner = ?', [owner]);
    const ver = mx ? Number(mx.v) : 0;

    // since=0 表示"全量"(第一次装/清过数据)
    const rows = since > 0
      ? await db.q(`SELECT name, text, meta, ver, deleted, updated_at
                    FROM flows WHERE owner = ? AND ver > ? ORDER BY ver`, [owner, since])
      : await db.q(`SELECT name, text, meta, ver, deleted, updated_at
                    FROM flows WHERE owner = ? AND deleted = 0 ORDER BY name`, [owner]);

    json(res, 200, {
      ok: true, ver, full: since === 0,
      flows: rows.map(r => ({
        name: r.name,
        text: r.text,
        meta: r.meta,                       // mysql2 会把 JSON 列自动 parse
        ver: Number(r.ver),
        deleted: !!r.deleted,
        updatedAt: r.updated_at,
      })),
    });
  } catch (e) {
    console.error('[flows] 拉取出错', e.message);
    json(res, 500, { ok: false, message: e.message });
  }
});

// ---- 上传一条(新增/修改)----
app.post('/api/v1/app/flows', needApp, async (req, res) => {
  try {
    const b = req.body || {};
    const owner = flowOwner(req);
    const name = String(b.name || '').trim().slice(0, 64);
    if (!owner) return json(res, 400, { ok: false, message: '缺少 deviceId' });
    if (!name)  return json(res, 400, { ok: false, message: '缺少流程名' });

    const baseVer = Math.max(Number(b.baseVer) || 0, 0);
    const text = typeof b.text === 'string' ? b.text.slice(0, 60000) : null;
    const meta = b.meta ? JSON.stringify(b.meta).slice(0, 20000) : null;

    const cur = await db.one('SELECT ver, deleted FROM flows WHERE owner = ? AND name = ?',
                             [owner, name]);

    // ⚠️ 冲突检测:客户端说"我基于第 N 版改的",但服务器已经到 M>N 了
    //    ⇒ 别人(或另一台设备)改过了。不静默覆盖,返回 409 让 App 决定。
    if (cur && baseVer && Number(cur.ver) > baseVer) {
      const sv = await db.one(
        'SELECT name, text, meta, ver, deleted, updated_at FROM flows WHERE owner=? AND name=?',
        [owner, name]);
      return json(res, 409, {
        ok: false, conflict: true,
        message: `服务器上已是第 ${cur.ver} 版(你基于第 ${baseVer} 版)`,
        server: sv,
      });
    }

    if (cur) {
      await db.run(
        `UPDATE flows SET text = COALESCE(?, text), meta = COALESCE(?, meta),
                          deleted = 0, ver = ver + 1
         WHERE owner = ? AND name = ?`, [text, meta, owner, name]);
    } else {
      await db.run(
        `INSERT INTO flows (owner, name, text, meta, ver, deleted)
         VALUES (?,?,?,?,1,0)`, [owner, name, text, meta]);
    }

    const nv = await db.one('SELECT ver FROM flows WHERE owner = ? AND name = ?', [owner, name]);
    // 顺手留一份历史 —— 和 flow_history 一个思路,但按 owner 存
    await db.run(
      `INSERT INTO flow_history (mac, name, text)
       SELECT ?, ?, text FROM flows WHERE owner = ? AND name = ?`,
      [owner, name, owner, name]).catch(() => {});

    json(res, 200, { ok: true, name, ver: Number(nv ? nv.ver : 1) });
  } catch (e) {
    console.error('[flows] 上传出错', e.message);
    json(res, 500, { ok: false, message: e.message });
  }
});

// ---- 软删除 ----
app.delete('/api/v1/app/flows/:name', needApp, async (req, res) => {
  try {
    const owner = flowOwner(req);
    const name = String(req.params.name || '').slice(0, 64);
    if (!owner) return json(res, 400, { ok: false, message: '缺少 deviceId' });
    // ⚠️ 软删除而不是 DELETE —— 别的设备要能拉到"这条被删了"这个事实,
    //    硬删的话增量同步永远不知道它曾经存在。
    await db.run(
      `INSERT INTO flows (owner, name, ver, deleted) VALUES (?,?,1,1)
       ON DUPLICATE KEY UPDATE deleted = 1, ver = ver + 1`, [owner, name]);
    const nv = await db.one('SELECT ver FROM flows WHERE owner=? AND name=?', [owner, name]);
    json(res, 200, { ok: true, name, ver: Number(nv ? nv.ver : 1) });
  } catch (e) {
    json(res, 500, { ok: false, message: e.message });
  }
});

// ---- 后台看:某个 owner 的所有流程 ----
app.get('/api/admin/flows', needLogin, async (req, res) => {
  const owner = String(req.query.owner || '').slice(0, 64);
  const rows = owner
    ? await db.q(`SELECT owner, name, LENGTH(text) AS size, ver, deleted, updated_at
                  FROM flows WHERE owner = ? ORDER BY name`, [owner])
    : await db.q(`SELECT owner, name, LENGTH(text) AS size, ver, deleted, updated_at
                  FROM flows ORDER BY owner, name LIMIT 500`);
  const owners = await db.q(
    'SELECT owner, COUNT(*) AS n, MAX(updated_at) AS at FROM flows GROUP BY owner ORDER BY at DESC');
  json(res, 200, { ok: true, flows: rows, owners });
});

app.post('/api/v1/app/schedule', needApp, async (req, res) => {
  try {
    const p = req.body || {};
    const mac = String(p.boardMac || '').toLowerCase().trim();
    const deviceId = String(p.deviceId || '').slice(0, 64);
    if (!mac) return json(res, 400, { ok: false, message: '缺少 boardMac' });
    const tasks = Array.isArray(p.tasks) ? p.tasks.slice(0, 100) : [];

    await db.run('DELETE FROM schedules WHERE board_mac = ? AND device_id = ?', [mac, deviceId]);
    for (const t of tasks) {
      const name = String(t.name || '').slice(0, 64);
      if (!name) continue;
      await db.run(
        `INSERT INTO schedules (board_mac, device_id, flow_name, fire_at, delay_ms,
                                mode, enabled, repeat_count)
         VALUES (?,?,?,?,?,?,?,?)
         ON DUPLICATE KEY UPDATE
           device_id=VALUES(device_id), fire_at=VALUES(fire_at), delay_ms=VALUES(delay_ms),
           mode=VALUES(mode), enabled=VALUES(enabled), repeat_count=VALUES(repeat_count)`,
        [mac, deviceId, name,
         t.fireAt ? new Date(t.fireAt) : null,
         t.delayMs || 0,
         String(t.mode || 'off').slice(0, 16),
         t.enabled ? 1 : 0,
         t.repeatCount || 1]);
    }
    json(res, 200, { ok: true, count: tasks.length });
  } catch (e) {
    console.error('上报定时失败:', e);
    json(res, 500, { ok: false, message: e.message });
  }
});

/**
 * 把服务端留底的流程**发回板子**(误删恢复)。
 *
 * 板子在内网、服务端在公网,所以只能**板子来拉**:
 * 板子下次心跳会带上 "我要恢复" 的意图 —— 这里换个做法:
 * 直接在管理页把内容显示出来,你复制粘贴到板子网页/App 里保存。
 * 同时提供 API 供 App 取。
 */
app.get('/api/admin/flow-backup', needLogin, async (req, res) => {
  const mac = String(req.query.mac || '').toLowerCase();
  if (!mac) return json(res, 400, { ok: false, message: '缺少 mac' });
  const rows = await db.q(
    'SELECT name, text, at FROM board_flows WHERE mac = ? ORDER BY name', [mac]);
  const hist = await db.q(
    `SELECT name, text, saved_at FROM flow_history
     WHERE mac = ? ORDER BY saved_at DESC LIMIT 50`, [mac]);
  json(res, 200, { ok: true, flows: rows, history: hist });
});

// 远程截图:口子留着,暂未实现
app.get('/api/v1/app/screenshot/request', needApp, (req, res) =>
  json(res, 200, { ok: true, enabled: false, pending: false, message: '服务端暂未启用远程截图' }));
app.post('/api/v1/app/screenshot', needApp, (req, res) =>
  json(res, 501, { ok: false, message: '远程截图暂未实现' }));

// ================================================================ 登录

function loginPage(err) {
  return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>登录 · 触摸注入板控制中心</title>
<style>
 body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
      background:#0d1117;color:#c9d1d9;
      font:14px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
 .box{background:#161b22;border:1px solid #30363d;border-radius:10px;padding:32px;width:340px}
 h1{font-size:18px;margin:0 0 6px}
 p.sub{color:#8b949e;font-size:12px;margin:0 0 20px}
 label{display:block;font-size:12px;color:#8b949e;margin:14px 0 6px}
 input{width:100%;box-sizing:border-box;background:#0d1117;color:#c9d1d9;
       border:1px solid #30363d;border-radius:6px;padding:10px;font-size:14px}
 input:focus{outline:none;border-color:#58a6ff}
 button{width:100%;margin-top:20px;background:#1f6feb;color:#fff;border:0;
        border-radius:6px;padding:11px;font-size:14px;cursor:pointer}
 button:hover{background:#388bfd}
 .err{background:rgba(248,81,73,.12);color:#f85149;border:1px solid rgba(248,81,73,.4);
      border-radius:6px;padding:9px 12px;font-size:13px;margin-bottom:8px}
</style></head><body>
<form class="box" method="post" action="/admin/login">
  <h1>🎯 触摸注入板</h1>
  <p class="sub">控制中心 · 请登录</p>
  ${err ? `<div class="err">${String(err).replace(/[<>&]/g, '')}</div>` : ''}
  <label>用户名</label>
  <input name="username" autocomplete="username" autofocus>
  <label>密码</label>
  <input name="password" type="password" autocomplete="current-password">
  <button type="submit">登录</button>
</form>
</body></html>`;
}

app.get('/admin/login', (req, res) => res.type('text/html').send(loginPage('')));

app.post('/admin/login', async (req, res) => {
  const { username, password } = req.body || {};
  const r = await auth.login(username, password, clientIp(req), req.get('User-Agent'));
  if (!r.ok) {
    await audit(null, 'login_fail', username, r.message, clientIp(req));
    return res.type('text/html').send(loginPage(r.message));
  }
  await audit(r.user.id, 'login', username, '', clientIp(req));
  res.set('Set-Cookie',
    `tp_sess=${r.token}; Path=/; Max-Age=${7 * 86400}; HttpOnly; SameSite=Lax`);
  res.redirect('/admin/');
});

app.get('/admin/logout', async (req, res) => {
  await auth.logout(parseCookies(req).tp_sess);
  res.set('Set-Cookie', 'tp_sess=; Path=/; Max-Age=0');
  res.redirect('/admin/login');
});

// ================================================================ 管理接口

app.get('/api/admin/me', needLogin, (req, res) => json(res, 200, { ok: true, user: req.user }));

app.get('/api/admin/summary', needLogin, async (req, res) => {
  const [b] = await db.q('SELECT COUNT(*) n FROM boards');
  const [p] = await db.q('SELECT COUNT(*) n FROM phones');
  const [f] = await db.q('SELECT COUNT(*) n FROM firmware');
  const [a] = await db.q('SELECT COUNT(*) n FROM apks');
  const boards = await db.q('SELECT last_seen FROM boards');
  json(res, 200, {
    ok: true,
    boards: b.n, phones: p.n, firmware: f.n, apk: a.n,
    online: boards.filter(x => isOnline(x.last_seen)).length,
    serverTime: Date.now(),
  });
});

app.get('/api/admin/boards', needLogin, async (req, res) => {
  const rows = await db.q('SELECT * FROM boards ORDER BY last_seen DESC');
  const out = [];
  for (const b of rows) {
    const flows = await db.q('SELECT name FROM board_flows WHERE mac = ? ORDER BY name', [b.mac]);
    // "下次要执行的任务":取 scheduled 里最近的一个未来时刻
    // ⚠️ 不过滤 enabled —— 暂停的定时也要显示出来,
    //    否则用户以为"没有任务",其实是"有任务但暂停了不会跑"(实测踩过)
      // ⚠️ 以前是 `ORDER BY fire_at ASC` —— 取到的是**最早**那个,
      //    于是一个早已过期的任务会永远占着"下次"的位置
      //    (用户实测:实际要跑的是 A,面板显示的却是几天前的 B)。
      //    正确优先级:
      //      ① 未来的最近一个(fire_at >= NOW())
      //      ② 没有未来的 → 最近更新过的那个(至少不是最老的)
      let nxt = await db.one(
        `SELECT flow_name, fire_at, mode, delay_ms, enabled
         FROM schedules
         WHERE board_mac = ? AND mode = 'at' AND fire_at IS NOT NULL
           AND fire_at >= NOW()
         ORDER BY fire_at ASC LIMIT 1`, [b.mac]);
      if (!nxt) {
        nxt = await db.one(
          `SELECT flow_name, fire_at, mode, delay_ms, enabled
           FROM schedules
           WHERE board_mac = ? AND fire_at IS NOT NULL
           ORDER BY updated_at DESC, fire_at DESC LIMIT 1`, [b.mac]);
      }
    out.push({
      mac: b.mac, name: b.name, fw: b.fw, ip: b.ip, channel: b.channel,
      heap: b.heap, uptime: b.uptime, hid: !!b.hid, flows: b.flows,
      running: b.running,
      flowNames: flows.map(f => f.name),
      next: nxt ? { name: nxt.flow_name, at: new Date(nxt.fire_at).getTime(),
                    enabled: !!nxt.enabled }
                : (b.next_name ? { name: b.next_name, at: Number(b.next_at), enabled: true }
                               : null),
      wifi: { ssid: b.wifi_ssid, rssi: b.wifi_rssi, channel: b.wifi_channel,
              hasPass: !!b.wifi_pass_enc },
      firstSeen: b.first_seen ? new Date(b.first_seen).getTime() : 0,
      // ⚠️ 必须给**时间戳**,不能给 Date 对象 —— JSON 会把它序列化成
      //    字符串,前端 `Date.now() - 字符串` = NaN,页面就显示成
      //    "NaN 天前"(用户实测反馈)。
      lastSeen: b.last_seen ? new Date(b.last_seen).getTime() : 0,
      online: isOnline(b.last_seen),
      pubIp: b.pub_ip || '',
      // App 报告的连接方式(蓝牙 / USB / WiFi)
      link: b.link_at ? {
        mode: b.link_mode || '', hid: b.link_hid || '',
        at: new Date(b.link_at).getTime(),
      } : null,
      geo: b.geo_at ? {
        country: b.geo_country || '', region: b.geo_region || '',
        city: b.geo_city || '', isp: b.geo_isp || '',
        lat: b.geo_lat || 0, lon: b.geo_lon || 0,
        at: new Date(b.geo_at).getTime(),
      } : null,
    });
  }
  json(res, 200, { ok: true, boards: out });
});

/**
 * 成功统计 —— 按「板子 + 流程」聚合 notify 事件。
 *
 * 用户要的是:"哪块板子、用的哪个流程、成功了几次"。
 * 数据来源就是流程里的 `notify ...` 上报(board_events 表)。
 */
app.get('/api/admin/event-stats', needLogin, async (req, res) => {
  try {
    const days = Math.min(Math.max(Number(req.query.days) || 0, 0), 365);
    // ⚠️⚠️ 两个查询的别名不同,条件必须分开拼 —— 踩过的坑:
    //      原来只有一个 `since` 且写死了 `e.at`,
    //      而下面"汇总"那个查询是 `FROM board_events`(**没有别名 e**),
    //      一带 ?days= 就报 `Unknown column 'e.at' in 'where clause'`。
    //      旧前端从来不传 days,所以这个 bug 一直潜伏着。
    const sinceGrp = days > 0
      ? `AND e.at >= DATE_SUB(NOW(), INTERVAL ${days} DAY)` : '';
    const sinceSum = days > 0
      ? `AND at >= DATE_SUB(NOW(), INTERVAL ${days} DAY)` : '';
    // ⚠️ JOIN 必须显式 COLLATE —— 两张表排序规则不一致时 MySQL 直接报错
    //    (之前就是这么把服务搞下线的)
    const rows = await db.q(`
      SELECT e.mac,
             -- ⚠️ 必须用 ANY_VALUE / MAX 包一下:MySQL 8 默认开启
             --    only_full_group_by,GROUP BY 里没列出的列会直接报错
             MAX(COALESCE(b.name, ''))                  AS board_name,
             COALESCE(e.flow, '(没写流程名)')            AS flow,
             COUNT(*)                                   AS total,
             SUM(CASE WHEN e.at >= CURDATE() THEN 1 ELSE 0 END) AS today,
             MAX(e.at)                                  AS last_at,
             MIN(e.at)                                  AS first_at,
             SUBSTRING_INDEX(GROUP_CONCAT(e.message ORDER BY e.at DESC SEPARATOR '\n'), '\n', 1)
                                                        AS last_msg
      FROM board_events e
      LEFT JOIN boards b
        ON b.mac COLLATE utf8mb4_unicode_ci = e.mac COLLATE utf8mb4_unicode_ci
      WHERE e.type = 'flow_event' ${sinceGrp}
      GROUP BY e.mac, e.flow
      ORDER BY total DESC, last_at DESC
      LIMIT 100`);
    const t = await db.one(`
      SELECT COUNT(*) AS total,
             SUM(CASE WHEN at >= CURDATE() THEN 1 ELSE 0 END) AS today
      FROM board_events WHERE type = 'flow_event' ${sinceSum}`);
    json(res, 200, { ok: true, stats: rows,
                     summary: { total: t ? t.total : 0, today: t ? t.today : 0 } });
  } catch (e) {
    console.error('[event-stats] 出错', e.message);
    json(res, 500, { ok: false, message: e.message });
  }
});

app.get('/api/admin/events', needLogin, async (req, res) => {
  try {
    const mac = String(req.query.mac || '').toLowerCase();
    // ⚠️ 改成**分页**(原来是 ?limit= 上限 200:看不到更早的,也没法翻)。
    //    兼容老调用 —— 没传 page 就按第 1 页。
    const size = Math.min(
      Math.max(Number(req.query.size) || Number(req.query.limit) || 50, 1), 200);
    const page = Math.max(Number(req.query.page) || 1, 1);
    const offset = (page - 1) * size;

    const [c] = mac
      ? await db.q('SELECT COUNT(*) n FROM board_events WHERE mac = ?', [mac])
      : await db.q('SELECT COUNT(*) n FROM board_events');
    const total = Number(c && c.n) || 0;

    // ⚠️ JOIN 的两个 mac 列排序规则必须一致,否则 MySQL 报
    //    "Illegal mix of collations"。显式 COLLATE 双保险。
    const rows = mac
      ? await db.q('SELECT * FROM board_events WHERE mac = ? ORDER BY at DESC LIMIT ? OFFSET ?',
                   [mac, size, offset])
      : await db.q(`SELECT e.*, b.name AS board_name FROM board_events e
                    LEFT JOIN boards b
                      ON b.mac COLLATE utf8mb4_unicode_ci
                       = e.mac COLLATE utf8mb4_unicode_ci
                    ORDER BY e.at DESC LIMIT ? OFFSET ?`, [size, offset]);
    json(res, 200, {
      ok: true, events: rows, list: rows,
      total, page, size, pages: Math.max(1, Math.ceil(total / size)),
    });
  } catch (e) {
    console.error('[events] 出错', e.message);
    json(res, 500, { ok: false, message: e.message });
  }
});

app.get('/api/admin/boards_old', needLogin, async (req, res) => {
  const rows = await db.q('SELECT * FROM boards ORDER BY last_seen DESC');
  json(res, 200, {
    ok: true,
    boards: rows.map(b => ({
      mac: b.mac, name: b.name, fw: b.fw, ip: b.ip, channel: b.channel,
      heap: b.heap, uptime: b.uptime, hid: !!b.hid, flows: b.flows,
      running: b.running,
      next: b.next_name ? { name: b.next_name, at: Number(b.next_at) } : null,
      wifi: { ssid: b.wifi_ssid, rssi: b.wifi_rssi, channel: b.wifi_channel,
              hasPass: !!b.wifi_pass_enc },
      firstSeen: b.first_seen, lastSeen: b.last_seen,
      online: isOnline(b.last_seen),
    })),
  });
});

app.get('/api/admin/board/:mac', needLogin, async (req, res) => {
  const mac = req.params.mac.toLowerCase();
  const b = await db.one('SELECT * FROM boards WHERE mac = ?', [mac]);
  if (!b) return json(res, 404, { ok: false, message: '没有这台板子' });
  const ips = await db.q('SELECT ip, at FROM board_ips WHERE mac = ? ORDER BY at DESC LIMIT 10', [mac]);
  const hb = await db.q(
    'SELECT ts, heap, uptime, hid, flows, running FROM heartbeats WHERE mac = ? ORDER BY ts DESC LIMIT 50',
    [mac]);
  const pol = await db.one('SELECT * FROM policies WHERE mac = ?', [mac]);
  json(res, 200, {
    ok: true,
    policy: pol,
    board: {
      ...b, online: isOnline(b.last_seen),
      wifi: { ssid: b.wifi_ssid, rssi: b.wifi_rssi, channel: b.wifi_channel,
              pass: decrypt(b.wifi_pass_enc) },
      ips, heartbeats: hb,
    },
  });
});

app.delete('/api/admin/board/:mac', needLogin, needAdminRole, async (req, res) => {
  const mac = req.params.mac.toLowerCase();
  await db.run('DELETE FROM boards WHERE mac = ?', [mac]);
  await db.run('DELETE FROM board_ips WHERE mac = ?', [mac]);
  await db.run('DELETE FROM heartbeats WHERE mac = ?', [mac]);
  await db.run('DELETE FROM policies WHERE mac = ?', [mac]);
  await audit(req.user.id, 'delete_board', mac, '', clientIp(req));
  json(res, 200, { ok: true });
});

/**
 * 让某块板子的灯亮/闪 —— 在几块同名板子里认出这一块。
 *
 * ⚠️ 板子在内网,服务端**够不到它**。所以这里不是直接发命令,
 *    而是把命令存进 settings,等板子下次心跳(2 分钟一次)带下去。
 *    ⇒ **按下按钮后最多等 2 分钟灯才亮**。
 *    想要"立刻亮",用 App 状态页的「点亮」按钮(App 和板子有直连)。
 */
app.post('/api/admin/led', needLogin, async (req, res) => {
  const b = req.body || {};
  const mac = String(b.mac || '').toLowerCase().trim();
  if (!mac) return json(res, 400, { ok: false, message: '缺少 mac' });
  const mode = String(b.mode || 'blink');
  // mode: blink(找板子) / off(关灯) / auto(回状态色) / color(指定颜色)
  let cmd;
  if (mode === 'off')       cmd = { off: true };
  else if (mode === 'auto') cmd = { auto: true };
  else if (mode === 'blink') cmd = { blink: true };
  else cmd = { r: +b.r | 0, g: +b.g | 0, b: +b.b | 0, ms: +b.ms | 0 };
  // ⚠️ settings 表字段是 k / v(不是 k / value)
  await db.run(
    'INSERT INTO settings (k, v) VALUES (?,?) ON DUPLICATE KEY UPDATE v = VALUES(v)',
    ['led:' + mac, JSON.stringify(cmd)]);
  await audit(req.user.id, 'led', mac, mode, clientIp(req));
  const on = await db.one(
    'SELECT mac FROM boards WHERE mac = ? AND last_seen > DATE_SUB(NOW(), INTERVAL ? SECOND)',
    [mac, Math.floor(ONLINE_MS / 1000)]);
  json(res, 200, {
    ok: true,
    message: on
      ? '已下达。板子下次心跳(最多 2 分钟)会亮起来'
      : '已下达。但这块板子当前不在线,要等它上线才有效',
  });
});

app.get('/api/admin/phones', needLogin, async (req, res) => {
  const rows = await db.q('SELECT * FROM phones ORDER BY last_seen DESC');
  const out = [];
  for (const p of rows) {
    const apps = await db.q(
      'SELECT pkg, name, is_system FROM phone_apps WHERE device_id = ? ORDER BY name',
      [p.device_id]);
    out.push({
      deviceId: p.device_id, name: p.name, model: p.model, android: p.android,
      appVersion: p.app_version,
      wifi: { ssid: p.wifi_ssid, rssi: p.wifi_rssi },
      // ⚠️ 时间必须转成**毫秒数** —— MySQL 的 DATETIME 经 mysql2 出来是 Date,
      //    JSON 化后是 ISO 字符串,前端 `Date.now() - ms` 会算出 NaN,
      //    界面上显示成 "NaN 天前"(实测)。/api/admin/boards 一直是毫秒所以正常。
      firstSeen: p.first_seen ? new Date(p.first_seen).getTime() : 0,
      lastSeen: p.last_seen ? new Date(p.last_seen).getTime() : 0,
      online: isOnline(p.last_seen),
      apps: apps.map(a => ({ pkg: a.pkg, name: a.name, system: !!a.is_system })),
    });
  }
  json(res, 200, { ok: true, phones: out });
});

// ---- 固件
app.get('/api/admin/firmware', needLogin, async (req, res) => {
  const rows = await db.q('SELECT * FROM firmware ORDER BY created_at DESC');
  json(res, 200, {
    ok: true,
    list: rows.map(f => ({
      id: f.id,
      version: f.version,
      channel: f.channel,
      file: f.file,
      size: f.size,
      sha256: f.sha256,
      notes: f.notes || '',
      createdAt: f.created_at ? new Date(f.created_at).getTime() : 0,
    })),
  });
});

app.post('/api/admin/firmware', needLogin, needAdminRole, uploadFw.single('file'), async (req, res) => {
  if (!req.file) return json(res, 400, { ok: false, message: '没收到文件' });
  const body = req.body || {};
  const version = String(body.version || '').trim();
  if (!version) { fs.unlinkSync(req.file.path); return json(res, 400, { ok: false, message: '要填版本号' }); }
  const name = `fw-${Date.now()}.bin`;
  fs.renameSync(req.file.path, path.join(FW_DIR, name));
  const r = await db.run(
    `INSERT INTO firmware (version, channel, file, size, sha256, notes, created_by)
     VALUES (?,?,?,?,?,?,?)`,
    [version, body.channel === 'beta' ? 'beta' : 'stable', name, req.file.size,
     sha256File(path.join(FW_DIR, name)), String(body.notes || ''), req.user.id]);
  await audit(req.user.id, 'upload_firmware', version, `id=${r.insertId}`, clientIp(req));
  json(res, 200, { ok: true, id: r.insertId });
});

app.delete('/api/admin/firmware/:id', needLogin, needAdminRole, async (req, res) => {
  const f = await db.one('SELECT * FROM firmware WHERE id = ?', [req.params.id]);
  if (!f) return json(res, 404, { ok: false, message: '没有这个固件' });
  await db.run('DELETE FROM firmware WHERE id = ?', [req.params.id]);
  try { fs.unlinkSync(path.join(FW_DIR, f.file)); } catch (_) {}
  await audit(req.user.id, 'delete_firmware', f.version, '', clientIp(req));
  json(res, 200, { ok: true });
});

// ---- APK
app.get('/api/admin/apk', needLogin, async (req, res) =>
{
  // ⚠️ 必须做字段名映射 —— 前端读的是 createdAt,
  //    而 SELECT * 出来是 created_at ⇒ undefined ⇒ 界面显示"—"(实测踩过)。
  //    时间也要转毫秒(和 boards/phones 统一)。
  const rows = await db.q('SELECT * FROM apks ORDER BY version_code DESC');
  json(res, 200, {
    ok: true,
    list: rows.map(a => ({
      id: a.id,
      versionCode: a.version_code,
      versionName: a.version_name,
      file: a.file,
      size: a.size,
      sha256: a.sha256,
      notes: a.notes || '',
      createdAt: a.created_at ? new Date(a.created_at).getTime() : 0,
      createdBy: a.created_by,
    })),
  });
});

app.post('/api/admin/apk', needLogin, needAdminRole, uploadApk.single('file'), async (req, res) => {
  if (!req.file) return json(res, 400, { ok: false, message: '没收到文件' });
  const body = req.body || {};
  const code = parseInt(body.versionCode || '0', 10);
  if (!code) { fs.unlinkSync(req.file.path); return json(res, 400, { ok: false, message: '要填 versionCode' }); }
  const name = `touchpad-${code}-${Date.now()}.apk`;
  fs.renameSync(req.file.path, path.join(APK_DIR, name));
  const r = await db.run(
    `INSERT INTO apks (version_code, version_name, file, size, sha256, notes, created_by)
     VALUES (?,?,?,?,?,?,?)`,
    [code, String(body.versionName || code), name, req.file.size,
     sha256File(path.join(APK_DIR, name)), String(body.notes || ''), req.user.id]);
  await audit(req.user.id, 'upload_apk', String(code), '', clientIp(req));
  json(res, 200, { ok: true, id: r.insertId });
});

app.delete('/api/admin/apk/:id', needLogin, needAdminRole, async (req, res) => {
  const a = await db.one('SELECT * FROM apks WHERE id = ?', [req.params.id]);
  if (!a) return json(res, 404, { ok: false, message: '没有这个 APK' });
  await db.run('DELETE FROM apks WHERE id = ?', [req.params.id]);
  try { fs.unlinkSync(path.join(APK_DIR, a.file)); } catch (_) {}
  await audit(req.user.id, 'delete_apk', String(a.version_code), '', clientIp(req));
  json(res, 200, { ok: true });
});

// ---- 策略
app.post('/api/admin/policy', needLogin, needAdminRole, async (req, res) => {
  const { mac, mode, target } = req.body || {};
  if (!mac) return json(res, 400, { ok: false, message: '缺少 mac' });
  const m = String(mac).toLowerCase();
  if (!mode || mode === 'none') {
    await db.run('DELETE FROM policies WHERE mac = ?', [m]);
  } else {
    await db.run(
      `INSERT INTO policies (mac, mode, target) VALUES (?,?,?)
       ON DUPLICATE KEY UPDATE mode=VALUES(mode), target=VALUES(target), at=NOW()`,
      [m, mode, target || null]);
  }
  await audit(req.user.id, 'set_policy', m, `${mode}/${target || ''}`, clientIp(req));
  json(res, 200, { ok: true });
});

// ---- 账号管理
app.get('/api/admin/users', needLogin, async (req, res) =>
  json(res, 200, { ok: true, users: await auth.listUsers() }));

app.post('/api/admin/users', needLogin, needAdminRole, async (req, res) => {
  const { username, password, role, displayName } = req.body || {};
  if (!username || !password) return json(res, 400, { ok: false, message: '用户名和密码都要填' });
  if (String(password).length < 4) return json(res, 400, { ok: false, message: '密码至少 4 位' });
  if (await auth.findUser(username)) return json(res, 409, { ok: false, message: '用户名已存在' });
  const id = await auth.createUser(username, password,
    role === 'admin' ? 'admin' : 'viewer', displayName);
  await audit(req.user.id, 'create_user', username, '', clientIp(req));
  json(res, 200, { ok: true, id });
});

app.post('/api/admin/users/:id/password', needLogin, async (req, res) => {
  const id = parseInt(req.params.id, 10);
  if (req.user.role !== 'admin' && req.user.id !== id) {
    return json(res, 403, { ok: false, message: '只能改自己的密码' });
  }
  const { password } = req.body || {};
  if (!password || String(password).length < 4) {
    return json(res, 400, { ok: false, message: '密码至少 4 位' });
  }
  await auth.setPassword(id, password);
  await audit(req.user.id, 'change_password', String(id), '', clientIp(req));
  json(res, 200, { ok: true });
});

app.post('/api/admin/users/:id/enabled', needLogin, needAdminRole, async (req, res) => {
  const id = parseInt(req.params.id, 10);
  if (id === req.user.id) return json(res, 400, { ok: false, message: '不能停用自己' });
  await auth.setEnabled(id, !!(req.body || {}).enabled);
  await audit(req.user.id, 'toggle_user', String(id), String(!!(req.body || {}).enabled), clientIp(req));
  json(res, 200, { ok: true });
});

app.delete('/api/admin/users/:id', needLogin, needAdminRole, async (req, res) => {
  const id = parseInt(req.params.id, 10);
  if (id === req.user.id) return json(res, 400, { ok: false, message: '不能删自己' });
  const u = await db.one('SELECT username FROM users WHERE id = ?', [id]);
  await auth.deleteUser(id);
  await audit(req.user.id, 'delete_user', u ? u.username : String(id), '', clientIp(req));
  json(res, 200, { ok: true });
});

app.get('/api/admin/sessions', needLogin, async (req, res) =>
  json(res, 200, { ok: true, sessions: await auth.listSessions(req.user.id) }));

/**
 * 操作审计(分页)。
 *
 * ⚠️ 原来写死 `LIMIT 200` —— 看不到更早的记录,也没法翻。
 *    改成分页:?page=1&size=50,返回 total / pages,前端据此画翻页条。
 */
app.get('/api/admin/audit', needLogin, async (req, res) => {
  const size = Math.min(Math.max(Number(req.query.size) || 50, 1), 200);
  const page = Math.max(Number(req.query.page) || 1, 1);
  const offset = (page - 1) * size;
  const [c] = await db.q('SELECT COUNT(*) n FROM audit_log');
  const total = Number(c && c.n) || 0;
  const list = await db.q(
    `SELECT a.*, u.username FROM audit_log a
     LEFT JOIN users u ON u.id = a.user_id
     ORDER BY a.at DESC LIMIT ? OFFSET ?`, [size, offset]);
  json(res, 200, {
    ok: true, list, total, page, size,
    pages: Math.max(1, Math.ceil(total / size)),
  });
});

// ---- 看板页面
app.get('/admin/', needLogin, (req, res) => {
  // ⚠️ 必须禁用缓存 —— 不然改了前端页面,浏览器还在跑旧的,
  //    表现就是"接口全是 404"(实测踩过:路由前缀改了、页面没跟着更新)。
  res.set('Cache-Control', 'no-store, no-cache, must-revalidate');
  res.set('Pragma', 'no-cache');
  // 新后台是「左侧菜单 + 多页面」结构,页面上有 4 个占位符要替换:
  //   __USER__    当前登录用户(前端显示"当前账号")
  //   __VER__     后台版本号
  //   __BUILT__   构建时间(出问题一眼看出跑的是哪版)
  //   __BOOT_AT__ 进程启动时刻(前端算"服务运行了多久")
  // ⚠️ 用 split/join 而不是 replace —— replace 只换第一个,
  //    而 __VER__ 在侧边栏和主页各出现一次。
  const adminVer = process.env.ADMIN_VER || '1.0';
  const adminBuilt = process.env.ADMIN_BUILT
    || new Date().toISOString().slice(0, 16).replace('T', ' ');
  res.type('text/html').send(
    fs.readFileSync(path.join(PUBLIC_DIR, 'index.html'), 'utf8')
      .replace('__USER__', JSON.stringify(req.user))
      .split('__VER__').join(adminVer)
      .split('__BUILT__').join(adminBuilt)
      .split('__BOOT_AT__').join(String(BOOT_AT)));
});
app.get('/admin', needLogin, (req, res) => res.redirect('/admin/'));
// ---------------------------------------------------------------------------
//  固件下载页(公开,不用登录)
//
//  为什么公开:拿到一块新板子的人,第一件事就是烧固件 ——
//  这时候他还没账号、也不想先登录。固件本身不敏感。
//
//  内容由 tools/release-firmware.sh 上传:
//    download/touchpad-{ble,usb,usb_touchtest}-full.bin
//    download/flash-{windows.bat,mac-linux.sh}
//    download/README.txt / version.json
// ---------------------------------------------------------------------------
// ⚠️ 是 __dirname/download,**不是** DATA_DIR/download ——
//    发布脚本(tools/release-firmware.sh)传到的就是它隔壁的 download/。
//    一开始写成 DATA_DIR/download,结果全部 404(实测)。
const DOWNLOAD_DIR = path.join(__dirname, 'download');
app.use('/download', express.static(DOWNLOAD_DIR, {
  setHeaders: (res, p) => {
    if (p.endsWith('.bin')) res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Cache-Control', 'no-store');   // 重新发布后要立刻能下到新的
  },
}));

app.use('/admin/static', express.static(PUBLIC_DIR));

// 旧的 /admin/api/* 前缀做个跳转兼容 —— 万一浏览器还跑着老页面,
// 也不会满屏 404(把 auth 情况一并带上)
app.use('/admin/api', needLogin, (req, res) => {
  res.set('Cache-Control', 'no-store');
  res.redirect(307, '/api/admin' + req.url);
});

// 图标:内联 SVG,顺手把浏览器的 /favicon.ico 404 也消掉
const FAVICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
<rect width="32" height="32" rx="7" fill="#1f6feb"/>
<circle cx="16" cy="16" r="6" fill="none" stroke="#fff" stroke-width="2.5"/>
<circle cx="16" cy="16" r="2" fill="#fff"/></svg>`;
const sendIcon = (req, res) => {
  res.type('image/svg+xml').set('Cache-Control', 'public, max-age=86400').send(FAVICON);
};
app.get('/favicon.ico', sendIcon);
app.get('/favicon.svg', sendIcon);

app.get('/', (req, res) => res.redirect('/admin/'));

// ================================================================ 启动

async function boot() {
  console.log('初始化数据库…');
  await db.initSchema();

  boardToken = await db.getSetting('boardToken', '');
  appToken = await db.getSetting('appToken', '');

  // 从旧的 config.json 继承 token(如果还在)
  let oldCfg = {};
  try {
    const p = path.join(DATA_DIR, 'config.json');
    if (fs.existsSync(p)) oldCfg = JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch (e) { /* 忽略 */ }

  if (!boardToken) {
    boardToken = oldCfg.boardToken || ('board_' + crypto.randomBytes(16).toString('hex'));
    await db.setSetting('boardToken', boardToken);
  }
  if (!appToken) {
    appToken = oldCfg.appToken || ('app_' + crypto.randomBytes(16).toString('hex'));
    await db.setSetting('appToken', appToken);
  }

  // 首次启动:建管理员账号(密码沿用旧 config.json 的,保持你能登进去)
  const n = await auth.countUsers();
  if (n === 0) {
    const adminPass = oldCfg.adminPass || crypto.randomBytes(6).toString('hex');
    await auth.createUser('admin', adminPass, 'admin', '管理员');
    console.log('════════════════════════════════════════════');
    console.log('  已创建管理员账号');
    console.log('  用户名: admin');
    console.log('  密  码: ' + adminPass);
    console.log('════════════════════════════════════════════');
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log('════════════════════════════════════════════');
    console.log('  触摸注入板 · 控制中心(MySQL 版)已启动');
    console.log('  端口     : ' + PORT);
    console.log('  管理后台 : http://<本机IP>/admin/');
    console.log('  数据库   : ' + db.DB.user + '@' + db.DB.host + ':' + db.DB.port + '/' + db.DB.database);
    console.log('  板子token: ' + boardToken);
    console.log('  App token: ' + appToken);
    console.log('════════════════════════════════════════════');
  });
}

boot().catch(e => {
  console.error('启动失败:', e);
  process.exit(1);
});
