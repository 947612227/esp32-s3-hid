/**
 * 手机 App 接口 /api/v1/app/*(APK 更新、手机信息、板子代报)
 */
const express = require('express');
const fs = require('fs');
const path = require('path');
const http = require('http');
const db = require('../db');
const { APK_DIR, json, needApp } = require('../common');
const { applyBoardReport } = require('./board');

const router = express.Router();

// ================================================================ App 接口

router.get('/api/v1/app/version', async (req, res) => {
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

router.get('/api/v1/app/download/:id', async (req, res) => {
  const a = await db.one('SELECT * FROM apks WHERE id = ?', [req.params.id]);
  if (!a) return res.status(404).send('没有这个 APK');
  const p = path.join(APK_DIR, a.file);
  if (!fs.existsSync(p)) return res.status(410).send('APK 文件已丢失');
  res.type('application/vnd.android.package-archive').sendFile(p);
});

router.post('/api/v1/app/phone', needApp, async (req, res) => {
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

router.post('/api/v1/app/apps', needApp, async (req, res) => {
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
router.post('/api/v1/app/link', needApp, async (req, res) => {
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

router.post('/api/v1/app/board-relay', needApp, async (req, res) => {
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


module.exports = { router };
