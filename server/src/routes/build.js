/**
 * 后台「一键构建」接口 /api/admin/build*(逻辑在 src/builder.js)
 *
 *   GET  /api/admin/build/meta            构建环境是否就绪、分支列表、下一个 versionCode、线上最新版本
 *   GET  /api/admin/builds                构建记录(最近 30 条)
 *   POST /api/admin/builds                开始构建 {kind, branch, versionName, channel, notes}
 *   GET  /api/admin/builds/:id            单条记录
 *   GET  /api/admin/builds/:id/log        日志(?offset= 从第几个字节读,前端轮询追加)
 *   GET  /api/admin/builds/:id/artifact   下载产物
 *   POST /api/admin/builds/:id/publish    发布(写进 APK / 固件列表)
 *   POST /api/admin/builds/:id/cancel     停止正在跑的构建
 *
 * 看:登录即可;开始 / 发布 / 取消:管理员
 */
const express = require('express');
const fs = require('fs');
const db = require('../db');
const builder = require('../builder');
const { json, clientIp, needLogin, needAdminRole } = require('../common');

const router = express.Router();

/** 把 builder 抛出的带状态码错误转成响应 */
function wrap(fn) {
  return async (req, res) => {
    try {
      await fn(req, res);
    } catch (e) {
      if (e.status) return json(res, e.status, { ok: false, message: e.message });
      console.error('[构建接口]', e);
      json(res, 500, { ok: false, message: '服务器出错:' + e.message });
    }
  };
}

router.get('/api/admin/build/meta', needLogin, wrap(async (req, res) => {
  let branches = [];
  let branchError = '';
  try { branches = await builder.listBranches(); } catch (e) { branchError = '读不到分支:' + (e.stderr || e.message); }
  const apk = await db.one('SELECT version_code, version_name FROM apks ORDER BY version_code DESC LIMIT 1');
  const fw = {};
  for (const ch of ['stable', 'beta']) {
    const f = await db.one('SELECT version FROM firmware WHERE channel = ? ORDER BY created_at DESC, id DESC LIMIT 1', [ch]);
    fw[ch] = f ? f.version : '';
  }
  const def = branches.find(b => b.name === builder.DEFAULT_BRANCH) ? builder.DEFAULT_BRANCH
    : (branches[0] ? branches[0].name : '');
  json(res, 200, {
    ok: true,
    env: builder.envStatus(),
    repo: builder.BUILD_REPO,
    branches, branchError, defaultBranch: def,
    nextVersionCode: await builder.nextVersionCode(),
    latest: {
      apk: apk ? { versionCode: apk.version_code, versionName: apk.version_name } : null,
      firmware: fw,
    },
    running: builder.runningId(),
  });
}));

router.get('/api/admin/builds', needLogin, wrap(async (req, res) => {
  json(res, 200, { ok: true, list: await builder.list(req.query.limit), running: builder.runningId() });
}));

router.post('/api/admin/builds', needLogin, needAdminRole, wrap(async (req, res) => {
  const b = req.body || {};
  const r = await builder.start({
    kind: b.kind, branch: b.branch, versionName: b.versionName, channel: b.channel, notes: b.notes,
    userId: req.user.id, ip: clientIp(req),
  });
  json(res, 200, Object.assign({ ok: true }, r));
}));

router.get('/api/admin/builds/:id', needLogin, wrap(async (req, res) => {
  const b = await builder.get(req.params.id);
  if (!b) return json(res, 404, { ok: false, message: '没有这个构建' });
  json(res, 200, { ok: true, build: b });
}));

router.get('/api/admin/builds/:id/log', needLogin, wrap(async (req, res) => {
  const b = await builder.get(req.params.id);
  if (!b) return json(res, 404, { ok: false, message: '没有这个构建' });
  const l = builder.readLog(b.id, req.query.offset);
  json(res, 200, {
    ok: true, status: b.status, done: b.status !== 'running',
    text: l ? l.text : '(日志已清理:每类只保留最近 5 次构建的日志)\n',
    offset: l ? l.offset : 0, size: l ? l.size : 0,
  });
}));

router.get('/api/admin/builds/:id/artifact', needLogin, wrap(async (req, res) => {
  const b = await builder.get(req.params.id);
  if (!b) return res.status(404).send('没有这个构建');
  const p = builder.artifactPath(b);
  if (!p || !fs.existsSync(p)) return res.status(410).send('产物已被清理(每类只保留最近 5 次构建)');
  res.download(p, b.artifact);
}));

router.post('/api/admin/builds/:id/publish', needLogin, needAdminRole, wrap(async (req, res) => {
  const r = await builder.publish(Number(req.params.id), req.user.id, clientIp(req));
  json(res, 200, { ok: true, id: r.id, pruned: r.pruned });
}));

router.post('/api/admin/builds/:id/cancel', needLogin, needAdminRole, wrap(async (req, res) => {
  builder.cancel(Number(req.params.id), req.user.id, clientIp(req));
  json(res, 200, { ok: true });
}));

module.exports = { router };
