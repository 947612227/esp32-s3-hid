/**
 * 各路由模块共用的东西:目录、常量、token、工具函数、鉴权中间件、上传。
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const multer = require('multer');
const db = require('./db');
const auth = require('./auth');
const { safeEqual } = require('./crypto');

const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const DATA_DIR = path.join(__dirname, '..', 'data');
const FW_DIR = path.join(DATA_DIR, 'firmware');
const APK_DIR = path.join(DATA_DIR, 'apk');

for (const d of [DATA_DIR, FW_DIR, APK_DIR]) {
  if (!fs.existsSync(d)) fs.mkdirSync(d, { recursive: true });
}

const HEARTBEAT_KEEP_DAYS = 7;

/** 进程启动时刻 —— 主页用它算「服务运行了多久」 */
const BOOT_AT = Date.now();

// 在线判定窗口。
// ⚠️ 必须**小于心跳间隔**才有意义 —— 以前是 90 分钟而心跳 60 分钟,
//    窗口比心跳还大 ⇒ **板子拔电也永远显示"在线"**(用户实测反馈)。
//    现在:心跳 2 分钟一次,窗口 5 分钟(容忍丢 2 次)。
const ONLINE_MS = 5 * 60 * 1000;

// ⚠️ 两个 token 在启动时(boot)才从数据库读出来,
//    所以放在对象里 —— 各路由模块拿到的是同一个对象,读到的永远是最新值。
const tokens = { board: '', app: '' };

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
const isOnline = (lastSeen) =>
  lastSeen && (Date.now() - new Date(lastSeen).getTime()) < ONLINE_MS;

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
  if (safeEqual(t, tokens.board)) return next();
  return json(res, 401, { ok: false, message: '板子 token 不对' });
}

// 手机最近一次被"顺手刷新在线"的时刻(deviceId → 毫秒),节流用
const phoneTouchAt = new Map();
const PHONE_TOUCH_MS = 30 * 1000;   // 同一台手机 30 秒内只写一次库

/**
 * App 的请求顺手刷新这台手机的 last_seen(设备管理的「在线」就看它)。
 *
 * ⚠️ 以前只有 /app/phone、/app/apps 会刷新,而 App 只在启动时、且距上次超过 6 小时才报
 *    ⇒ 开了一整天的手机,报完 5 分钟后就一直显示离线(实测 SM-S918U1 停在 08:43)。
 *    其实 App 一直在发 代报板子 / 定时 / 连接方式 / 拉流程,而且都带 deviceId。
 * 只 UPDATE 已登记的手机,不新建(新手机仍由 /app/phone 登记);写库失败不影响请求本身。
 */
function touchPhone(req) {
  const id = String((req.body && req.body.deviceId) || req.query.deviceId || '').trim().slice(0, 64);
  if (!id) return;
  const now = Date.now();
  if (now - (phoneTouchAt.get(id) || 0) < PHONE_TOUCH_MS) return;
  if (phoneTouchAt.size > 5000) phoneTouchAt.clear();
  phoneTouchAt.set(id, now);
  db.run('UPDATE phones SET last_seen = NOW() WHERE device_id = ?', [id]).catch(() => {});
}

function needApp(req, res, next) {
  const t = req.get('X-Token') || req.query.token || '';
  if (safeEqual(t, tokens.app)) { touchPhone(req); return next(); }
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

// ⚠️ 是 server/download,**不是** DATA_DIR/download ——
//    发布脚本(tools/release-firmware.sh)传到的就是它隔壁的 download/。
//    一开始写成 DATA_DIR/download,结果全部 404(实测)。
const DOWNLOAD_DIR = path.join(__dirname, '..', 'download');

module.exports = {
  PUBLIC_DIR,
  DATA_DIR,
  FW_DIR,
  APK_DIR,
  DOWNLOAD_DIR,
  HEARTBEAT_KEEP_DAYS,
  BOOT_AT,
  ONLINE_MS,
  tokens,
  json,
  sha256File,
  clientIp,
  parseCookies,
  isOnline,
  audit,
  needBoard,
  needApp,
  needLogin,
  needAdminRole,
  uploadFw,
  uploadApk,
};
