/**
 * 发布版本保留策略:APK、固件各只保留最近 KEEP_VERSIONS 个版本,多出来的连文件一起删。
 *
 * 规则:
 *   - APK:按 versionCode 算版本。同一个 versionCode 传过多次的,只留最新那次;
 *          再按 versionCode 从大到小留前 KEEP_VERSIONS 个。
 *   - 固件:按渠道(stable / beta)分别算。按 version 算版本,同一个 version 只留最新那次;
 *          再按上传时间从新到旧留前 KEEP_VERSIONS 个(板子取的"最新"也是按上传时间)。
 *          ⚠️ 被策略(policies.target)指定的固件**永远不删** —— 删了那几块板子就拿不到指定版本。
 *
 * 调用时机:上传成功后、服务启动时。
 */
const fs = require('fs');
const path = require('path');
const db = require('./db');
const { FW_DIR, APK_DIR, audit } = require('./common');

const KEEP_VERSIONS = 5;   // 每类保留的版本数

/** 从一组已排好序(新 → 旧)的记录里挑出要删的:同版本只留第一条,版本数超 keep 的全删 */
function pickDrops(rows, keyOf, keep) {
  const kept = new Set();   // 已保留的版本号
  const drops = [];
  for (const r of rows) {
    const k = keyOf(r);
    if (kept.has(k) || kept.size >= keep) drops.push(r);
    else kept.add(k);
  }
  return drops;
}

/** 删一条记录 + 它的文件,并记审计 */
async function dropOne(table, dir, row, action, label, userId, ip) {
  await db.run(`DELETE FROM ${table} WHERE id = ?`, [row.id]);
  try { fs.unlinkSync(path.join(dir, row.file)); } catch (_) { /* 文件早没了也算删掉 */ }
  await audit(userId, action, label, `id=${row.id} file=${row.file}`, ip);
}

/** APK 只留最近 KEEP_VERSIONS 个版本,返回删掉的 versionCode 列表 */
async function pruneApks(userId, ip) {
  const rows = await db.q(
    'SELECT id, version_code, file FROM apks ORDER BY version_code DESC, created_at DESC, id DESC');
  const drops = pickDrops(rows, r => r.version_code, KEEP_VERSIONS);
  for (const r of drops) {
    await dropOne('apks', APK_DIR, r, 'prune_apk', String(r.version_code), userId, ip);
  }
  return drops.map(r => r.version_code);
}

/** 固件每个渠道只留最近 KEEP_VERSIONS 个版本(策略指定的不删),返回删掉的 "渠道/版本" 列表 */
async function pruneFirmware(userId, ip) {
  const pinned = new Set((await db.q('SELECT target FROM policies WHERE target IS NOT NULL'))
    .map(p => String(p.target)));
  const rows = await db.q(
    'SELECT id, version, channel, file FROM firmware ORDER BY created_at DESC, id DESC');
  const byChannel = {};
  for (const r of rows) (byChannel[r.channel] = byChannel[r.channel] || []).push(r);
  const dropped = [];
  for (const ch of Object.keys(byChannel)) {
    const drops = pickDrops(byChannel[ch], r => r.version, KEEP_VERSIONS)
      .filter(r => !pinned.has(String(r.id)));
    for (const r of drops) {
      await dropOne('firmware', FW_DIR, r, 'prune_firmware', `${ch}/${r.version}`, userId, ip);
      dropped.push(`${ch}/${r.version}`);
    }
  }
  return dropped;
}

/** 两类都清一遍;出错只打日志,不影响上传或启动 */
async function pruneAll(userId, ip, why) {
  try {
    const apks = await pruneApks(userId, ip);
    const fws = await pruneFirmware(userId, ip);
    if (apks.length || fws.length) {
      console.log(`[保留策略] ${why}:清掉 APK ${apks.length} 个(${apks.join(',')}),` +
                  `固件 ${fws.length} 个(${fws.join(',')}),每类保留最近 ${KEEP_VERSIONS} 个版本`);
    }
    return { apks, firmware: fws };
  } catch (e) {
    console.error('[保留策略] 清理失败:', e.message);
    return { apks: [], firmware: [] };
  }
}

module.exports = { KEEP_VERSIONS, pickDrops, pruneApks, pruneFirmware, pruneAll };
