/**
 * 登录页 + 管理接口 /api/admin/* + 看板页面 /admin/
 */
const express = require('express');
const fs = require('fs');
const path = require('path');
const db = require('../db');
const auth = require('../auth');
const retention = require('../retention');
const { decrypt } = require('../crypto');
const { PUBLIC_DIR, FW_DIR, APK_DIR, BOOT_AT, ONLINE_MS, json, sha256File, clientIp, parseCookies, isOnline, audit, needLogin, needAdminRole, uploadFw, uploadApk } = require('../common');

const router = express.Router();

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

router.get('/admin/login', (req, res) => res.type('text/html').send(loginPage('')));

router.post('/admin/login', async (req, res) => {
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

router.get('/admin/logout', async (req, res) => {
  await auth.logout(parseCookies(req).tp_sess);
  res.set('Set-Cookie', 'tp_sess=; Path=/; Max-Age=0');
  res.redirect('/admin/login');
});

// ================================================================ 管理接口

router.get('/api/admin/me', needLogin, (req, res) => json(res, 200, { ok: true, user: req.user }));

router.get('/api/admin/summary', needLogin, async (req, res) => {
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

router.get('/api/admin/boards', needLogin, async (req, res) => {
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
router.get('/api/admin/event-stats', needLogin, async (req, res) => {
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

router.get('/api/admin/events', needLogin, async (req, res) => {
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

router.get('/api/admin/boards_old', needLogin, async (req, res) => {
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

router.get('/api/admin/board/:mac', needLogin, async (req, res) => {
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

router.delete('/api/admin/board/:mac', needLogin, needAdminRole, async (req, res) => {
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
router.post('/api/admin/led', needLogin, async (req, res) => {
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

router.get('/api/admin/phones', needLogin, async (req, res) => {
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
router.get('/api/admin/firmware', needLogin, async (req, res) => {
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

router.post('/api/admin/firmware', needLogin, needAdminRole, uploadFw.single('file'), async (req, res) => {
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
  const pruned = await retention.pruneAll(req.user.id, clientIp(req), '上传固件 ' + version);
  json(res, 200, { ok: true, id: r.insertId, pruned });
});

router.delete('/api/admin/firmware/:id', needLogin, needAdminRole, async (req, res) => {
  const f = await db.one('SELECT * FROM firmware WHERE id = ?', [req.params.id]);
  if (!f) return json(res, 404, { ok: false, message: '没有这个固件' });
  await db.run('DELETE FROM firmware WHERE id = ?', [req.params.id]);
  try { fs.unlinkSync(path.join(FW_DIR, f.file)); } catch (_) {}
  await audit(req.user.id, 'delete_firmware', f.version, '', clientIp(req));
  json(res, 200, { ok: true });
});

// ---- APK
router.get('/api/admin/apk', needLogin, async (req, res) =>
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

router.post('/api/admin/apk', needLogin, needAdminRole, uploadApk.single('file'), async (req, res) => {
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
  const pruned = await retention.pruneAll(req.user.id, clientIp(req), '上传 APK ' + code);
  json(res, 200, { ok: true, id: r.insertId, pruned });
});

router.delete('/api/admin/apk/:id', needLogin, needAdminRole, async (req, res) => {
  const a = await db.one('SELECT * FROM apks WHERE id = ?', [req.params.id]);
  if (!a) return json(res, 404, { ok: false, message: '没有这个 APK' });
  await db.run('DELETE FROM apks WHERE id = ?', [req.params.id]);
  try { fs.unlinkSync(path.join(APK_DIR, a.file)); } catch (_) {}
  await audit(req.user.id, 'delete_apk', String(a.version_code), '', clientIp(req));
  json(res, 200, { ok: true });
});

// ---- 策略
router.post('/api/admin/policy', needLogin, needAdminRole, async (req, res) => {
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
router.get('/api/admin/users', needLogin, async (req, res) =>
  json(res, 200, { ok: true, users: await auth.listUsers() }));

router.post('/api/admin/users', needLogin, needAdminRole, async (req, res) => {
  const { username, password, role, displayName } = req.body || {};
  if (!username || !password) return json(res, 400, { ok: false, message: '用户名和密码都要填' });
  if (String(password).length < 4) return json(res, 400, { ok: false, message: '密码至少 4 位' });
  if (await auth.findUser(username)) return json(res, 409, { ok: false, message: '用户名已存在' });
  const id = await auth.createUser(username, password,
    role === 'admin' ? 'admin' : 'viewer', displayName);
  await audit(req.user.id, 'create_user', username, '', clientIp(req));
  json(res, 200, { ok: true, id });
});

router.post('/api/admin/users/:id/password', needLogin, async (req, res) => {
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

router.post('/api/admin/users/:id/enabled', needLogin, needAdminRole, async (req, res) => {
  const id = parseInt(req.params.id, 10);
  if (id === req.user.id) return json(res, 400, { ok: false, message: '不能停用自己' });
  await auth.setEnabled(id, !!(req.body || {}).enabled);
  await audit(req.user.id, 'toggle_user', String(id), String(!!(req.body || {}).enabled), clientIp(req));
  json(res, 200, { ok: true });
});

router.delete('/api/admin/users/:id', needLogin, needAdminRole, async (req, res) => {
  const id = parseInt(req.params.id, 10);
  if (id === req.user.id) return json(res, 400, { ok: false, message: '不能删自己' });
  const u = await db.one('SELECT username FROM users WHERE id = ?', [id]);
  await auth.deleteUser(id);
  await audit(req.user.id, 'delete_user', u ? u.username : String(id), '', clientIp(req));
  json(res, 200, { ok: true });
});

router.get('/api/admin/sessions', needLogin, async (req, res) =>
  json(res, 200, { ok: true, sessions: await auth.listSessions(req.user.id) }));

/**
 * 操作审计(分页)。
 *
 * ⚠️ 原来写死 `LIMIT 200` —— 看不到更早的记录,也没法翻。
 *    改成分页:?page=1&size=50,返回 total / pages,前端据此画翻页条。
 */
router.get('/api/admin/audit', needLogin, async (req, res) => {
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
router.get('/admin/', needLogin, (req, res) => {
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
router.get('/admin', needLogin, (req, res) => res.redirect('/admin/'));

module.exports = { router };
