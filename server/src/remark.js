/**
 * 板子备注(如「大麦1号」)—— **以服务器为准**,不能重名。
 *
 * 三个入口都走 setRemark(先查重再存):
 *   · 后台      POST /api/admin/boards/:mac/remark
 *   · App       POST /api/v1/app/board-remark
 *   · 板子网页  板子转发到 POST /api/v1/board/remark
 * 下发:心跳回复 / App 代报回复里总带 `remark`(服务器这份),板子和服务器不一样就照着改。
 * 板子离线时在板子网页改的:心跳里带 remarkPending,这里查重 —— 重名就不采用、
 * 记进 remark_conflict(后台标出来),回复里给回服务器那份,板子改回去。
 *
 * ⚠️ 名字(ESP32-S3_XXXX)和备注是两回事:名字是固件按 MAC 生成的,不能改;
 *    备注不进蓝牙名(广播包放不下中文)。
 */
const db = require('./db');

const MAX_CHARS = 20;   // 最多 20 个字
const MAX_BYTES = 60;   // 板子 NVS 里留了 60 字节(中文 3 字节 / 字;emoji 4 字节会更早到顶)

/**
 * 规范化 + 校验。
 * ⚠️ 不许引号 / 反斜杠 / 控制字符:板子解析 JSON 是手写的极简版,不处理转义;
 *    尖括号也不许,省得哪个页面忘了转义。
 * @returns {{ok:true, remark:string} | {ok:false, code:number, message:string}}
 */
function normalize(raw) {
  if (raw === undefined || raw === null) return { ok: false, code: 400, message: '缺少 remark' };
  const r = String(raw).trim();
  if ([...r].length > MAX_CHARS) return { ok: false, code: 400, message: `备注最多 ${MAX_CHARS} 个字` };
  if (Buffer.byteLength(r, 'utf8') > MAX_BYTES) return { ok: false, code: 400, message: '备注太长(表情符号很占地方)' };
  if (/["\\<>\u0000-\u001f\u007f\u2028\u2029]/.test(r)) {
    return { ok: false, code: 400, message: '备注不能含引号、反斜杠、尖括号或换行' };
  }
  return { ok: true, remark: r };
}

// 改备注排队执行:「查重 → 写入」中间不能插进别的写入,否则两块板子能同时抢到同一个名字。
// 服务端只有一个 Node 进程,进程内排队就够了。
let queue = Promise.resolve();
function serialized(fn) {
  const p = queue.then(fn, fn);
  queue = p.catch(() => {});
  return p;
}

/**
 * 查重并保存。
 * @param {string} mac  板子 MAC(小写)
 * @param {*}      raw  新备注(空串 = 清掉)
 * @returns {Promise<{ok:boolean, code:number, remark?:string, changed?:boolean, message:string}>}
 */
function setRemark(mac, raw) {
  return serialized(async () => {
    const n = normalize(raw);
    if (!n.ok) return n;
    const b = await db.one('SELECT mac, remark FROM boards WHERE mac = ?', [mac]);
    if (!b) return { ok: false, code: 404, message: '服务器还没有这块板子(等它心跳一次再改)' };
    if (n.remark) {
      // boards 表是 utf8mb4_unicode_ci:比较忽略大小写(「DM01」和「dm01」算重名)
      const dup = await db.one(
        'SELECT mac, name FROM boards WHERE remark = ? AND mac <> ? LIMIT 1', [n.remark, mac]);
      if (dup) {
        return { ok: false, code: 409,
                 message: `「${n.remark}」已经被 ${dup.name || dup.mac}(${dup.mac})用了,换一个` };
      }
    }
    await db.run(
      "UPDATE boards SET remark = ?, remark_conflict = '', remark_conflict_at = NULL WHERE mac = ?",
      [n.remark, mac]);
    const changed = (b.remark || '') !== n.remark;
    if (changed) console.log(`[remark] ${mac} 备注:「${b.remark || ''}」→「${n.remark}」`);
    return { ok: true, code: 200, remark: n.remark, changed,
             message: n.remark ? `备注已改为「${n.remark}」` : '备注已清空' };
  });
}

/**
 * 心跳 / App 代报时调:板子带了「待同步」的备注就查重采用,然后给出服务器这份(回复里下发)。
 * @param {string} mac
 * @param {object} rep  板子状态(心跳 body 或代报的 status):remark / remarkPending
 * @returns {Promise<string>} 服务器这份备注
 */
async function resolveReport(mac, rep) {
  if (rep && rep.remarkPending === true && typeof rep.remark === 'string') {
    const r = await setRemark(mac, rep.remark);
    if (!r.ok && r.code !== 404) {
      console.log(`[remark] ${mac} 板子上改的「${rep.remark}」没采用:${r.message}`);
      await db.run('UPDATE boards SET remark_conflict = ?, remark_conflict_at = NOW() WHERE mac = ?',
        [String(rep.remark).slice(0, 64), mac]);
    }
  }
  const row = await db.one('SELECT remark FROM boards WHERE mac = ?', [mac]);
  return (row && row.remark) || '';
}

/** 后台列表 / 详情用:没采用的备注(板子离线时改的、和别的板子重名) */
function conflictDto(b) {
  return b && b.remark_conflict
    ? { remark: b.remark_conflict, at: b.remark_conflict_at ? new Date(b.remark_conflict_at).getTime() : 0 }
    : null;
}

module.exports = { MAX_CHARS, normalize, setRemark, resolveReport, conflictDto };
