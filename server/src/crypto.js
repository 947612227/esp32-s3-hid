/**
 * WiFi 密码的加密存储。
 *
 * ⚠️ 用户明确要求保存 WiFi 密码(用于换网络后恢复),所以:
 *   · 不落明文 —— 用 AES-256-GCM 加密
 *   · 密钥**不写在代码里**,存在 data/secret.key(首次启动自动生成,权限 600)
 *   · 解密的密码**只通过管理接口返回**,板子/App 的普通接口永远拿不到
 *
 * 说清楚边界:这是"防止数据库文件被顺手看到",不是"防止服务器被攻破"。
 * 服务器被 root 了,密钥和密文都在同一台机器上,一样能解。
 * 真要更强,得把密钥放到别处 —— 那就超出这个项目的范围了。
 */
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, '..', 'data');
const KEY_FILE = path.join(DATA_DIR, 'secret.key');

function loadKey() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
  if (fs.existsSync(KEY_FILE)) {
    const k = fs.readFileSync(KEY_FILE);
    if (k.length === 32) return k;
  }
  const k = crypto.randomBytes(32);
  fs.writeFileSync(KEY_FILE, k, { mode: 0o600 });
  return k;
}

let KEY = null;
function key() {
  if (!KEY) KEY = loadKey();
  return KEY;
}

/** 加密 → base64(iv|tag|cipher) */
function encrypt(plain) {
  if (plain === undefined || plain === null || plain === '') return '';
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key(), iv);
  const enc = Buffer.concat([c.update(String(plain), 'utf8'), c.final()]);
  return Buffer.concat([iv, c.getAuthTag(), enc]).toString('base64');
}

/** 解密;失败返回空串(不抛异常,免得把接口打挂) */
function decrypt(b64) {
  if (!b64) return '';
  try {
    const buf = Buffer.from(b64, 'base64');
    const iv = buf.slice(0, 12);
    const tag = buf.slice(12, 28);
    const enc = buf.slice(28);
    const d = crypto.createDecipheriv('aes-256-gcm', key(), iv);
    d.setAuthTag(tag);
    return Buffer.concat([d.update(enc), d.final()]).toString('utf8');
  } catch (e) {
    return '';
  }
}

/** 生成一个设备/管理 token */
function newToken(prefix) {
  return (prefix || 'tk') + '_' + crypto.randomBytes(16).toString('hex');
}

/** 固定长度比较,避免时序侧信道 */
function safeEqual(a, b) {
  const ba = Buffer.from(String(a || ''));
  const bb = Buffer.from(String(b || ''));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

module.exports = { encrypt, decrypt, newToken, safeEqual };
