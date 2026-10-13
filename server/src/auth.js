/**
 * 账号体系:注册、登录、会话。
 *
 * 密码用 Node 自带的 scrypt 哈希(不需要额外依赖):
 *   存储格式  scrypt$N$r$p$salt(base64)$hash(base64)
 * 校验用 timingSafeEqual,避免时序侧信道。
 *
 * 会话存数据库而不是签名 cookie —— 这样能"踢人下线"、能看登录设备。
 */
const crypto = require('crypto');
const { q, one, run } = require('./db');

const SCRYPT = { N: 16384, r: 8, p: 1, keylen: 64 };
const SESSION_DAYS = 7;

// ---------------------------------------------------------------- 密码

function hashPassword(plain) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(String(plain), salt, SCRYPT.keylen, {
    N: SCRYPT.N, r: SCRYPT.r, p: SCRYPT.p,
  });
  return `scrypt$${SCRYPT.N}$${SCRYPT.r}$${SCRYPT.p}$${salt.toString('base64')}$${hash.toString('base64')}`;
}

function verifyPassword(plain, stored) {
  try {
    const parts = String(stored).split('$');
    if (parts.length !== 6 || parts[0] !== 'scrypt') return false;
    const N = parseInt(parts[1], 10), r = parseInt(parts[2], 10), p = parseInt(parts[3], 10);
    const salt = Buffer.from(parts[4], 'base64');
    const want = Buffer.from(parts[5], 'base64');
    const got = crypto.scryptSync(String(plain), salt, want.length, { N, r, p });
    return got.length === want.length && crypto.timingSafeEqual(got, want);
  } catch (e) {
    return false;
  }
}

// ---------------------------------------------------------------- 账号

async function createUser(username, password, role, displayName) {
  const hash = hashPassword(password);
  const r = await run(
    'INSERT INTO users (username, password_hash, role, display_name) VALUES (?,?,?,?)',
    [username, hash, role || 'viewer', displayName || username]
  );
  return r.insertId;
}

async function findUser(username) {
  return one('SELECT * FROM users WHERE username = ?', [username]);
}

async function countUsers() {
  const r = await one('SELECT COUNT(*) AS n FROM users');
  return r ? r.n : 0;
}

async function listUsers() {
  return q(`SELECT id, username, display_name, role, enabled,
                   created_at, last_login, last_ip
            FROM users ORDER BY id`);
}

async function setPassword(userId, password) {
  await run('UPDATE users SET password_hash = ? WHERE id = ?',
    [hashPassword(password), userId]);
}

async function setEnabled(userId, enabled) {
  await run('UPDATE users SET enabled = ? WHERE id = ?', [enabled ? 1 : 0, userId]);
}

async function deleteUser(userId) {
  await run('DELETE FROM users WHERE id = ?', [userId]);
}

// ---------------------------------------------------------------- 会话

function newToken() {
  return crypto.randomBytes(32).toString('hex');
}

async function login(username, password, ip, ua) {
  const u = await findUser(username);
  if (!u) return { ok: false, message: '用户名或密码不对' };
  if (!u.enabled) return { ok: false, message: '这个账号已被停用' };
  if (!verifyPassword(password, u.password_hash)) {
    return { ok: false, message: '用户名或密码不对' };
  }
  const token = newToken();
  const exp = new Date(Date.now() + SESSION_DAYS * 86400_000);
  await run(
    'INSERT INTO sessions (token, user_id, expires_at, ip, ua) VALUES (?,?,?,?,?)',
    [token, u.id, exp, String(ip || '').slice(0, 64), String(ua || '').slice(0, 255)]
  );
  await run('UPDATE users SET last_login = NOW(), last_ip = ? WHERE id = ?',
    [String(ip || '').slice(0, 64), u.id]);
  // 顺手清掉过期会话
  await run('DELETE FROM sessions WHERE expires_at < NOW()');
  return {
    ok: true, token,
    user: { id: u.id, username: u.username, role: u.role, displayName: u.display_name },
  };
}

async function userBySession(token) {
  if (!token) return null;
  const r = await one(
    `SELECT u.id, u.username, u.role, u.display_name, u.enabled
     FROM sessions s JOIN users u ON u.id = s.user_id
     WHERE s.token = ? AND s.expires_at > NOW()`, [token]);
  if (!r || !r.enabled) return null;
  return r;
}

async function logout(token) {
  if (token) await run('DELETE FROM sessions WHERE token = ?', [token]);
}

async function listSessions(userId) {
  return q(`SELECT token, created_at, expires_at, ip, ua
            FROM sessions WHERE user_id = ? AND expires_at > NOW()
            ORDER BY created_at DESC`, [userId]);
}

module.exports = {
  hashPassword, verifyPassword,
  createUser, findUser, countUsers, listUsers,
  setPassword, setEnabled, deleteUser,
  login, logout, userBySession, listSessions, newToken,
};
