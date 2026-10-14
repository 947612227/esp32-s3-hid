/**
 * 触摸注入板 · 控制中心(MySQL 版)
 *  存储:MySQL 8(touchpad 库),账号存 users 表(scrypt 哈希),会话存库
 *
 *  代码结构:
 *    index.js              入口:中间件、挂路由、启动
 *    src/common.js         目录/常量/token/工具/鉴权中间件/上传
 *    src/routes/board.js   /api/v1/board/*   板子:心跳、事件、固件
 *    src/routes/app.js     /api/v1/app/*     手机 App:APK 更新、手机信息、板子代报
 *    src/routes/flows.js   流程同步(App + 后台)
 *    src/routes/admin.js   /admin/login、/api/admin/*、/admin/ 看板
 *    src/routes/static.js  /download、/admin/static、图标
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
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const db = require('./src/db');
const auth = require('./src/auth');
const { DATA_DIR, tokens } = require('./src/common');

const PORT = process.env.PORT || 80;

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


// 挂路由 —— ⚠️ 顺序就是匹配顺序,和拆分前保持一致
app.use(require('./src/routes/board').router);
app.use(require('./src/routes/app').router);
app.use(require('./src/routes/flows').router);
app.use(require('./src/routes/admin').router);
app.use(require('./src/routes/static').router);

// ================================================================ 启动

async function boot() {
  console.log('初始化数据库…');
  await db.initSchema();
  await require('./src/migrate').migrate();
  await require('./src/retention').pruneAll(null, '', '启动');

  tokens.board = await db.getSetting('boardToken', '');
  tokens.app = await db.getSetting('appToken', '');

  // 从旧的 config.json 继承 token(如果还在)
  let oldCfg = {};
  try {
    const p = path.join(DATA_DIR, 'config.json');
    if (fs.existsSync(p)) oldCfg = JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch (e) { /* 忽略 */ }

  if (!tokens.board) {
    tokens.board = oldCfg.boardToken || ('board_' + crypto.randomBytes(16).toString('hex'));
    await db.setSetting('boardToken', tokens.board);
  }
  if (!tokens.app) {
    tokens.app = oldCfg.appToken || ('app_' + crypto.randomBytes(16).toString('hex'));
    await db.setSetting('appToken', tokens.app);
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
    console.log('  板子token: ' + tokens.board);
    console.log('  App token: ' + tokens.app);
    console.log('════════════════════════════════════════════');
  });
}

boot().catch(e => {
  console.error('启动失败:', e);
  process.exit(1);
});
