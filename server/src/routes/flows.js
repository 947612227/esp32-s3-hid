/**
 * 流程同步(App 拉取/上传/删除、后台流程管理、定时、截图)
 */
const express = require('express');
const db = require('../db');
const { json, clientIp, audit, needApp, needLogin, needAdminRole } = require('../common');

const router = express.Router();

// ============================================================================
//  流程同步(服务器为权威 + App 本地缓存)
//
//  背景 / 为什么要这么设计
//  ────────────────────────────────────────────────────────────────────────
//  原来「流程」有三处真相,谁也不服谁:
//    ① 板子的 spiffs   = 流程正文(权威)
//    ② App 的 SharedPreferences = 流程状态(权威)
//    ③ 服务器 board_flows = **只读备份**(只收不发)
//  ⇒ 换手机就丢状态,换板子就丢流程,群控更无从谈起。
//
//  现在服务器是**权威**,App 和板子都是**缓存**:
//    · App 改动 → 乐观地本地先生效 → 同时推服务器(带 baseVer)
//    · 服务器版本变了 → App 启动时拉增量 → 写回本地和板子
//    · 服务器够不到板子(内网)⇒ 由 App 中转,和点灯那条路一样
//
//  ⚠️ 冲突策略:**最后写入者赢(LWW)+ 服务器保留版本号**。
//     不做自动合并 —— 自动合并是另一个量级的复杂度,
//     而现在只有一两个人在用,冲突概率极低。
//     真冲突了返回 409 + 服务器当前内容,让 App 决定。
//
//  ⚠️ owner 现在用 **deviceId** —— 因为 App 还没有登录(第 ⑦ 项)。
//     等注册登录做完,owner 换成用户 id,历史流程不会有归属问题
//     (owner 只是个字符串,改成 'user:123' 就行)。
// ============================================================================

/** 从请求里取 owner(现在=设备号) */
function flowOwner(req) {
  const o = String((req.body && req.body.deviceId) || req.query.deviceId || '').trim();
  return o.slice(0, 64);
}

// ---- 拉取:返回**所有归属**中「启用」的流程 ----
//
//  ⚠️ 为什么不按 owner 过滤(用户明确要求):
//      "客户端请求列表时,返回所有归属中启用的流程"。
//      流程是**团队共享**的,不该按设备隔离 —— 换台手机也该看到同一批流程。
//      所以 deviceId 只用于**上传**时标记归属,拉取时不参与过滤。
router.get('/api/v1/app/flows', needApp, async (req, res) => {
  try {
    const since = Math.max(Number(req.query.since) || 0, 0);

    // 只给"启用"的:禁用/删除的客户端**拿不到**,也就不用管
    const rows = await db.q(
      `SELECT owner, name, text, meta, ver, updated_at
       FROM flows
       WHERE deleted = 0 AND status = 'enabled'
       ORDER BY ver`);

    const [mx] = await db.q(
      `SELECT COALESCE(MAX(ver),0) AS v FROM flows WHERE deleted = 0 AND status = 'enabled'`);
    const ver = mx ? Number(mx.v) : 0;

    json(res, 200, {
      ok: true, ver, full: since === 0,
      flows: rows.map(r => ({
        owner: r.owner,
        name: r.name,
        text: r.text,
        meta: r.meta,
        ver: Number(r.ver),
        deleted: false,
        status: 'enabled',
        updatedAt: r.updated_at,
      })),
    });
  } catch (e) {
    console.error('[flows] 拉取出错', e.message);
    json(res, 500, { ok: false, message: e.message });
  }
});

// ---- 上传一条(新增/修改)----
router.post('/api/v1/app/flows', needApp, async (req, res) => {
  try {
    const b = req.body || {};
    const owner = flowOwner(req);
    const name = String(b.name || '').trim().slice(0, 64);
    if (!owner) return json(res, 400, { ok: false, message: '缺少 deviceId' });
    if (!name)  return json(res, 400, { ok: false, message: '缺少流程名' });

    const baseVer = Math.max(Number(b.baseVer) || 0, 0);
    const text = typeof b.text === 'string' ? b.text.slice(0, 60000) : null;
    const meta = b.meta ? JSON.stringify(b.meta).slice(0, 20000) : null;

    const cur = await db.one('SELECT ver, deleted FROM flows WHERE owner = ? AND name = ?',
                             [owner, name]);

    // ⚠️ 冲突检测:客户端说"我基于第 N 版改的",但服务器已经到 M>N 了
    //    ⇒ 别人(或另一台设备)改过了。不静默覆盖,返回 409 让 App 决定。
    if (cur && baseVer && Number(cur.ver) > baseVer) {
      const sv = await db.one(
        'SELECT name, text, meta, ver, deleted, updated_at FROM flows WHERE owner=? AND name=?',
        [owner, name]);
      return json(res, 409, {
        ok: false, conflict: true,
        message: `服务器上已是第 ${cur.ver} 版(你基于第 ${baseVer} 版)`,
        server: sv,
      });
    }

    // ⚠️⚠️ ver 必须**单调递增**,不能"每条从 1 开始"。
    //
    //    踩过的坑:原来 INSERT 写死 ver=1,于是两条流程的 ver 都是 1,
    //    MAX(ver)=1 永远不变 ⇒ App 的增量拉取(ver > since)**永远拉不到东西**,
    //    只有首次全量能用。表现就是"服务器新加的流程,App 重启也不下来"。
    //
    //    后来改成"先查 MAX 再 +1",并发保存会拿到同一个号;
    //    现在从全局计数器原子取号(见 db.nextSeq / 迁移 002)。
    const nextVer = await db.nextSeq('flow_ver');

    if (cur) {
      await db.run(
        `UPDATE flows SET text = COALESCE(?, text), meta = COALESCE(?, meta),
                          deleted = 0, ver = ?
         WHERE owner = ? AND name = ?`, [text, meta, nextVer, owner, name]);
    } else {
      await db.run(
        `INSERT INTO flows (owner, name, text, meta, ver, deleted)
         VALUES (?,?,?,?,?,0)`, [owner, name, text, meta, nextVer]);
    }

    const nv = await db.one('SELECT ver FROM flows WHERE owner = ? AND name = ?', [owner, name]);
    // 顺手留一份历史 —— 和 flow_history 一个思路,但按 owner 存
    await db.run(
      `INSERT INTO flow_history (mac, name, text)
       SELECT ?, ?, text FROM flows WHERE owner = ? AND name = ?`,
      [owner, name, owner, name]).catch(() => {});

    json(res, 200, { ok: true, name, ver: Number(nv ? nv.ver : 1) });
  } catch (e) {
    console.error('[flows] 上传出错', e.message);
    json(res, 500, { ok: false, message: e.message });
  }
});

// ---- 软删除 ----
router.delete('/api/v1/app/flows/:name', needApp, async (req, res) => {
  try {
    const owner = flowOwner(req);
    const name = String(req.params.name || '').slice(0, 64);
    if (!owner) return json(res, 400, { ok: false, message: '缺少 deviceId' });
    // ⚠️ 软删除而不是 DELETE —— 别的设备要能拉到"这条被删了"这个事实,
    //    硬删的话增量同步永远不知道它曾经存在。
    // 同上的道理:删除也要拿一个**新的、更大的** ver,否则别的设备增量拉不到
    const delVer = await db.nextSeq('flow_ver');
    await db.run(
      `INSERT INTO flows (owner, name, ver, deleted) VALUES (?,?,?,1)
       ON DUPLICATE KEY UPDATE deleted = 1, ver = ?`, [owner, name, delVer, delVer]);
    const nv = await db.one('SELECT ver FROM flows WHERE owner=? AND name=?', [owner, name]);
    json(res, 200, { ok: true, name, ver: Number(nv ? nv.ver : 1) });
  } catch (e) {
    json(res, 500, { ok: false, message: e.message });
  }
});

// ---- 后台看:某个 owner 的所有流程 ----
/**
 * 改某个流程的状态。
 *   enabled  = 允许客户端下载(会下发正文)
 *   disabled = 不允许下载(客户端把板子上那份移除,服务器保留记录)
 *   deleted  = 删除(客户端也移除)
 * ⚠️ 三种都要 **ver +1** —— 否则客户端拉增量看不到这次变更。
 */
router.post('/api/admin/flows/status', needLogin, needAdminRole, async (req, res) => {
  const b = req.body || {};
  const owner = String(b.owner || '').slice(0, 64);
  const name = String(b.name || '').slice(0, 64);
  const status = ['enabled', 'disabled', 'deleted'].includes(b.status) ? b.status : null;
  if (!owner || !name) return json(res, 400, { ok: false, message: '缺少 owner 或 name' });
  if (!status) return json(res, 400, { ok: false, message: 'status 只能是 enabled/disabled/deleted' });

  const nextVer = await db.nextSeq('flow_ver');
  await db.run(
    'UPDATE flows SET status = ?, deleted = ?, ver = ? WHERE owner = ? AND name = ?',
    [status, status === 'deleted' ? 1 : 0, nextVer, owner, name]);
  await audit(req.user.id, 'flow_status', owner + '/' + name, status, clientIp(req));
  json(res, 200, { ok: true, owner, name, status, ver: nextVer });
});

router.get('/api/admin/flows', needLogin, async (req, res) => {
  const owner = String(req.query.owner || '').slice(0, 64);
  const rows = owner
    ? await db.q(`SELECT owner, name, LENGTH(text) AS size, ver, deleted, status, updated_at
                  FROM flows WHERE owner = ? ORDER BY name`, [owner])
    : await db.q(`SELECT owner, name, LENGTH(text) AS size, ver, deleted, status, updated_at
                  FROM flows ORDER BY owner, name LIMIT 500`);
  const owners = await db.q(
    'SELECT owner, COUNT(*) AS n, MAX(updated_at) AS at FROM flows GROUP BY owner ORDER BY at DESC');
  json(res, 200, { ok: true, flows: rows, owners });
});

router.post('/api/v1/app/schedule', needApp, async (req, res) => {
  try {
    const p = req.body || {};
    const mac = String(p.boardMac || '').toLowerCase().trim();
    const deviceId = String(p.deviceId || '').slice(0, 64);
    if (!mac) return json(res, 400, { ok: false, message: '缺少 boardMac' });
    const tasks = Array.isArray(p.tasks) ? p.tasks.slice(0, 100) : [];

    await db.run('DELETE FROM schedules WHERE board_mac = ? AND device_id = ?', [mac, deviceId]);
    for (const t of tasks) {
      const name = String(t.name || '').slice(0, 64);
      if (!name) continue;
      await db.run(
        `INSERT INTO schedules (board_mac, device_id, flow_name, fire_at, delay_ms,
                                mode, enabled, repeat_count)
         VALUES (?,?,?,?,?,?,?,?)
         ON DUPLICATE KEY UPDATE
           device_id=VALUES(device_id), fire_at=VALUES(fire_at), delay_ms=VALUES(delay_ms),
           mode=VALUES(mode), enabled=VALUES(enabled), repeat_count=VALUES(repeat_count)`,
        [mac, deviceId, name,
         t.fireAt ? new Date(t.fireAt) : null,
         t.delayMs || 0,
         String(t.mode || 'off').slice(0, 16),
         t.enabled ? 1 : 0,
         t.repeatCount || 1]);
    }
    json(res, 200, { ok: true, count: tasks.length });
  } catch (e) {
    console.error('上报定时失败:', e);
    json(res, 500, { ok: false, message: e.message });
  }
});

/**
 * 把服务端留底的流程**发回板子**(误删恢复)。
 *
 * 板子在内网、服务端在公网,所以只能**板子来拉**:
 * 板子下次心跳会带上 "我要恢复" 的意图 —— 这里换个做法:
 * 直接在管理页把内容显示出来,你复制粘贴到板子网页/App 里保存。
 * 同时提供 API 供 App 取。
 */
router.get('/api/admin/flow-backup', needLogin, async (req, res) => {
  const mac = String(req.query.mac || '').toLowerCase();
  if (!mac) return json(res, 400, { ok: false, message: '缺少 mac' });
  const rows = await db.q(
    'SELECT name, text, at FROM board_flows WHERE mac = ? ORDER BY name', [mac]);
  const hist = await db.q(
    `SELECT name, text, saved_at FROM flow_history
     WHERE mac = ? ORDER BY saved_at DESC LIMIT 50`, [mac]);
  json(res, 200, { ok: true, flows: rows, history: hist });
});

// 远程截图:口子留着,暂未实现
router.get('/api/v1/app/screenshot/request', needApp, (req, res) =>
  json(res, 200, { ok: true, enabled: false, pending: false, message: '服务端暂未启用远程截图' }));
router.post('/api/v1/app/screenshot', needApp, (req, res) =>
  json(res, 501, { ok: false, message: '远程截图暂未实现' }));


module.exports = { router };
