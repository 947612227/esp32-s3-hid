/**
 * 把旧的 JSON 文件数据迁移到 MySQL。
 *
 * 只跑一次,幂等:已经存在的记录会跳过(用 INSERT IGNORE / ON DUPLICATE)。
 *
 * 用法:  node tools/migrate-json-to-mysql.js
 */
const fs = require('fs');
const path = require('path');
const db = require('../src/db');
const { encrypt } = require('../src/crypto');

const DATA = path.join(__dirname, '..', 'data');

function readJson(name, def) {
  const p = path.join(DATA, name + '.json');
  if (!fs.existsSync(p)) return def;
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) { return def; }
}

function readJsonl(name) {
  const p = path.join(DATA, name + '.jsonl');
  if (!fs.existsSync(p)) return [];
  return fs.readFileSync(p, 'utf8').split('\n').filter(Boolean)
    .map(l => { try { return JSON.parse(l); } catch (e) { return null; } })
    .filter(Boolean);
}

async function main() {
  await db.initSchema();
  let n = 0;

  // ---- 板子
  const boards = readJson('boards', {});
  for (const mac of Object.keys(boards)) {
    const b = boards[mac];
    const w = b.wifi || {};
    await db.run(
      `INSERT INTO boards
        (mac, name, fw, ip, channel, heap, uptime, hid, flows, running,
         next_name, next_at, wifi_ssid, wifi_rssi, wifi_channel, wifi_pass_enc,
         first_seen, last_seen)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
       ON DUPLICATE KEY UPDATE name=VALUES(name), fw=VALUES(fw), ip=VALUES(ip)`,
      [mac.toLowerCase(), b.name || '', b.fw || '', b.ip || '', b.channel || 'stable',
       b.heap || 0, b.uptime || 0, b.hid ? 1 : 0, b.flows || 0, b.running || '',
       (b.next && b.next.name) || '', (b.next && b.next.at) || 0,
       w.ssid || '', w.rssi || 0, w.channel || 0,
       w.passEnc || (w.pass ? encrypt(w.pass) : null),
       b.firstSeen ? new Date(b.firstSeen) : new Date(),
       b.lastSeen ? new Date(b.lastSeen) : new Date()]);
    n++;
  }
  console.log(`  板子: ${n} 台`);

  // ---- IP 历史
  let ipN = 0;
  for (const mac of Object.keys(boards)) {
    for (const h of (boards[mac].ipHistory || [])) {
      await db.run('INSERT IGNORE INTO board_ips (mac, ip, at) VALUES (?,?,?)',
        [mac.toLowerCase(), h.ip, new Date(h.at || Date.now())]);
      ipN++;
    }
  }
  console.log(`  IP 历史: ${ipN} 条`);

  // ---- 心跳
  const hb = readJsonl('heartbeat');
  let hbN = 0;
  for (const h of hb) {
    await db.run(
      'INSERT INTO heartbeats (mac, ts, heap, uptime, hid, flows, running) VALUES (?,?,?,?,?,?,?)',
      [String(h.mac || '').toLowerCase(), new Date(h.ts || Date.now()),
       h.heap || 0, h.uptime || 0, h.hid ? 1 : 0, h.flows || 0, h.running || '']);
    hbN++;
  }
  console.log(`  心跳: ${hbN} 条`);

  // ---- 手机
  const phones = readJson('phones', {});
  let pN = 0;
  for (const id of Object.keys(phones)) {
    const p = phones[id];
    const w = p.wifi || {};
    await db.run(
      `INSERT INTO phones (device_id, name, model, android, app_version,
                           wifi_ssid, wifi_rssi, first_seen, last_seen)
       VALUES (?,?,?,?,?,?,?,?,?)
       ON DUPLICATE KEY UPDATE name=VALUES(name), model=VALUES(model)`,
      [id, p.name || '', p.model || '', p.android || '', p.appVersion || '',
       w.ssid || '', w.rssi || 0,
       p.firstSeen ? new Date(p.firstSeen) : new Date(),
       p.lastSeen ? new Date(p.lastSeen) : new Date()]);
    for (const a of (p.apps || [])) {
      await db.run(
        'INSERT IGNORE INTO phone_apps (device_id, pkg, name, is_system) VALUES (?,?,?,?)',
        [id, String(a.pkg || '').slice(0, 128), String(a.name || '').slice(0, 128),
         a.system ? 1 : 0]);
    }
    pN++;
  }
  console.log(`  手机: ${pN} 台`);

  // ---- 固件
  const fw = readJson('firmware', []);
  let fN = 0;
  for (const f of fw) {
    await db.run(
      `INSERT INTO firmware (id, version, channel, file, size, sha256, notes, created_at)
       VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE version=VALUES(version)`,
      [f.id, f.version, f.channel || 'stable', f.file, f.size || 0,
       f.sha256 || '', f.notes || '', new Date(f.createdAt || Date.now())]);
    fN++;
  }
  console.log(`  固件: ${fN} 个`);

  // ---- APK
  const apk = readJson('apk', []);
  let aN = 0;
  for (const a of apk) {
    await db.run(
      `INSERT INTO apks (id, version_code, version_name, file, size, sha256, notes, created_at)
       VALUES (?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE version_code=VALUES(version_code)`,
      [a.id, a.versionCode, a.versionName || '', a.file, a.size || 0,
       a.sha256 || '', a.notes || '', new Date(a.createdAt || Date.now())]);
    aN++;
  }
  console.log(`  APK: ${aN} 个`);

  // ---- 策略
  const pol = readJson('policy', {});
  let poN = 0;
  for (const mac of Object.keys(pol)) {
    const p = pol[mac];
    await db.run(
      `INSERT INTO policies (mac, mode, target, at) VALUES (?,?,?,?)
       ON DUPLICATE KEY UPDATE mode=VALUES(mode), target=VALUES(target)`,
      [mac.toLowerCase(), p.mode || 'none', p.target || null,
       p.at ? new Date(p.at) : new Date()]);
    poN++;
  }
  console.log(`  策略: ${poN} 条`);

  console.log('\n迁移完成 ✅');
  await db.close();
}

main().catch(e => { console.error('迁移失败:', e); process.exit(1); });
