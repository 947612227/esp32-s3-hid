/**
 * 把一个文件「上架」成可下发的固件 / APK —— 后台手工上传 和 一键构建的「发布」共用这一套。
 *
 *   入库(firmware / apks 表) → 记审计 → 按保留策略清理旧版本
 *
 * ⚠️ 两条路必须走同一段代码:文件命名、sha256、审计动作名、保留策略都要一致,
 *    不然构建发布的版本和手工上传的版本在下发 / 清理时表现会不一样。
 */
const fs = require('fs');
const path = require('path');
const db = require('./db');
const retention = require('./retention');
const { FW_DIR, APK_DIR, sha256File, audit } = require('./common');

/**
 * 放文件:move=true 时直接 rename(上传的临时文件),否则复制(构建产物要留在构建目录里)
 */
function placeFile(src, dst, move) {
  if (move) fs.renameSync(src, dst);
  else fs.copyFileSync(src, dst);
}

/**
 * 上架固件
 * @param {object} o
 *   src      源文件路径
 *   move     true = 移走源文件
 *   version  固件版本号
 *   channel  stable / beta(其他值一律当 stable)
 *   notes    更新说明
 *   userId / ip   审计用
 *   auditDetail   额外审计说明(构建发布时写上构建号)
 * @returns {{id:number, pruned:object}}
 */
async function addFirmware(o) {
  const name = `fw-${Date.now()}.bin`;
  const dst = path.join(FW_DIR, name);
  placeFile(o.src, dst, o.move);
  const size = fs.statSync(dst).size;
  const r = await db.run(
    `INSERT INTO firmware (version, channel, file, size, sha256, notes, created_by)
     VALUES (?,?,?,?,?,?,?)`,
    [o.version, o.channel === 'beta' ? 'beta' : 'stable', name, size,
     sha256File(dst), String(o.notes || ''), o.userId]);
  await audit(o.userId, 'upload_firmware', o.version,
    `id=${r.insertId}` + (o.auditDetail ? ' ' + o.auditDetail : ''), o.ip);
  const pruned = await retention.pruneAll(o.userId, o.ip, '上传固件 ' + o.version);
  return { id: r.insertId, pruned };
}

/**
 * 上架 APK
 * @param {object} o  src / move / versionCode / versionName / notes / userId / ip / auditDetail
 * @returns {{id:number, pruned:object}}
 */
async function addApk(o) {
  const code = o.versionCode;
  const name = `touchpad-${code}-${Date.now()}.apk`;
  const dst = path.join(APK_DIR, name);
  placeFile(o.src, dst, o.move);
  const size = fs.statSync(dst).size;
  const r = await db.run(
    `INSERT INTO apks (version_code, version_name, file, size, sha256, notes, created_by)
     VALUES (?,?,?,?,?,?,?)`,
    [code, String(o.versionName || code), name, size,
     sha256File(dst), String(o.notes || ''), o.userId]);
  await audit(o.userId, 'upload_apk', String(code), o.auditDetail || '', o.ip);
  const pruned = await retention.pruneAll(o.userId, o.ip, '上传 APK ' + code);
  return { id: r.insertId, pruned };
}

module.exports = { addFirmware, addApk };
