/**
 * 板子接口 /api/v1/board/*(心跳、事件、固件下载)+ 板子状态入库
 */
const express = require('express');
const fs = require('fs');
const path = require('path');
const http = require('http');
const db = require('../db');
const { encrypt } = require('../crypto');
const { FW_DIR, HEARTBEAT_KEEP_DAYS, json, clientIp, needBoard } = require('../common');

const router = express.Router();

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

// ================================================================ 板子接口

router.get('/api/v1/board/health', (req, res) => json(res, 200, { ok: true, ts: Date.now() }));

/**
 * 把板子上报的流程列表同步进 board_flows(只动有变化的行)。
 *
 * ⚠️ 以前是"整表删光再插回",每次心跳都要 DELETE + 20 次 INSERT + 一次 history 清理。
 *    更糟的是:上报**只带名字不带内容**时(App 代报允许这样),
 *    旧内容会被 NULL 覆盖 —— 服务器留底就没了。
 *
 * 规则:
 *   - 列表里没有的名字 → 删
 *   - 新名字           → 插(有内容就带上)
 *   - 已有名字 + 带了内容且不同 → 旧内容进 flow_history,再更新
 *   - 已有名字 + 没带内容       → 不动(保留旧内容)
 *
 * @param {string}   mac    板子 MAC(小写)
 * @param {string[]} names  流程名列表
 * @param {object}   texts  名字 → 内容(可缺)
 * @returns {{added:number, changed:number, removed:number}}
 */
async function syncBoardFlows(mac, names, texts) {
  const tx = (texts && typeof texts === 'object') ? texts : {};
  const wanted = [];                         // 去重、截断后的新名字列表
  for (const n of names.slice(0, 64)) {   // 固件上限 32 个,留余量
    const nm = String(n || '').slice(0, 64);
    if (nm && !wanted.includes(nm)) wanted.push(nm);
  }
  const oldRows = await db.q('SELECT name, text FROM board_flows WHERE mac = ?', [mac]);
  const oldMap = new Map(oldRows.map(r => [r.name, r.text]));
  const st = { added: 0, changed: 0, removed: 0 };

  for (const nm of oldMap.keys()) {
    if (!wanted.includes(nm)) {
      await db.run('DELETE FROM board_flows WHERE mac = ? AND name = ?', [mac, nm]);
      st.removed++;
    }
  }
  let historyAdded = false;
  for (const nm of wanted) {
    const newText = (typeof tx[nm] === 'string') ? tx[nm] : null;
    if (!oldMap.has(nm)) {
      await db.run('INSERT INTO board_flows (mac, name, text) VALUES (?,?,?)', [mac, nm, newText]);
      st.added++;
      continue;
    }
    const oldText = oldMap.get(nm);
    if (newText === null || newText === oldText) continue;
    // 只在**旧内容非空**时存历史 —— 否则会存一堆 NULL
    if (oldText) {
      await db.run('INSERT INTO flow_history (mac, name, text) VALUES (?,?,?)', [mac, nm, oldText]);
      historyAdded = true;
    }
    await db.run('UPDATE board_flows SET text = ? WHERE mac = ? AND name = ?', [newText, mac, nm]);
    st.changed++;
  }
  if (historyAdded) {
    // 只留最近 10 版
    await db.run(
      `DELETE FROM flow_history WHERE mac = ? AND id NOT IN (
         SELECT id FROM (
           SELECT id FROM flow_history WHERE mac = ?
           ORDER BY saved_at DESC LIMIT 10
         ) t)`, [mac, mac]);
  }
  return st;
}

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

  // 流程名字 + 内容:按差异更新(没带 flowNames = 板子说这次没变,整段跳过)
  if (Array.isArray(b.flowNames)) {
    const st = await syncBoardFlows(mac, b.flowNames, b.flowTexts);
    if (st.added || st.changed || st.removed) {
      console.log(`[flows] ${mac} 流程备份:新增 ${st.added} / 修改 ${st.changed} / 删除 ${st.removed}`);
    }
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

router.post('/api/v1/board/heartbeat', needBoard, async (req, res) => {
  try {
    const b = req.body || {};
    const mac = String(b.mac || '').toLowerCase().trim();
    if (!mac) return json(res, 400, { ok: false, message: '缺少 mac' });
    const pubIp = clientIp(req).replace(/^::ffff:/, '');
    const fw = await applyBoardReport(b, mac, pubIp, true);

    // 板子这次没带流程列表(它认为没变),但服务器这边一条都没有
    // (库是新的 / 板子被从看板删过)⇒ 让板子下次心跳带上全量
    let needFlows = false;
    if (!Array.isArray(b.flowNames) && (b.flows || 0) > 0) {
      const c = await db.one('SELECT COUNT(*) AS n FROM board_flows WHERE mac = ?', [mac]);
      needFlows = !c || Number(c.n) === 0;
    }

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
      needFlows,
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
router.post('/api/v1/board/event', needBoard, async (req, res) => {
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

router.get('/api/v1/board/firmware/download/:id', needBoard, async (req, res) => {
  const f = await db.one('SELECT * FROM firmware WHERE id = ?', [req.params.id]);
  if (!f) return res.status(404).send('没有这个固件');
  const p = path.join(FW_DIR, f.file);
  if (!fs.existsSync(p)) return res.status(410).send('固件文件已丢失');
  res.type('application/octet-stream').sendFile(p);
});


module.exports = { router, applyBoardReport };
