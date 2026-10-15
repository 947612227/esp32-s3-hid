/**
 * 后台远程升级板子固件(手动下发 + 空闲时自动升级)
 *
 * 通道:服务端够不到内网的板子,只能「板子每 2 分钟来心跳 → 顺路把命令捎回去」。
 *   后台点「升级」→ board_ota.want_fw_id = 目标固件
 *   板子心跳 → decide() 判断现在能不能升 → 能就在回复里带 ota:{version,url,sha256,size}
 *   板子空闲时自己下载、核对 sha256、写入、重启 → 下次心跳 fw 变成目标版本 ⇒ 成功
 *   板子忙 / 下载失败 ⇒ 心跳里带 otaReport 回来 ⇒ 推迟(下次再发)/ 失败(停)
 *
 * 安全规则(怎么都不能打断抢票):
 *   - 板子在跑流程 ⇒ 不发
 *   - 这块板子 15 分钟内有「指定时刻」定时任务(或刚过去 3 分钟)⇒ 不发,等过了再发
 *   - 板子侧执行前再查一遍(流程 / 排队 / 频率循环),忙就报推迟
 *   - 升级策略是「永不升级」的板子不能下发
 *
 * ⚠️ 只有带 caps=ota-remote 的固件(1.4.0 起)认得这个命令。
 *    老固件收到也不会升 —— 下发时直接告诉管理员,不让它干等。
 */
const db = require('./db');

const OTA_CAP = 'ota-remote';
const GUARD_BEFORE_MIN = 15;              // 定时任务前多少分钟内不升级
const GUARD_AFTER_MIN = 3;                // 定时任务刚触发后多少分钟内不升级
const SENT_TIMEOUT_MS = 10 * 60 * 1000;   // 发下去 10 分钟没结果 ⇒ 判失败
const WANT_EXPIRE_MS = 24 * 3600 * 1000;  // 手动命令 24 小时还没执行掉 ⇒ 作废

const STATE_TEXT = {
  pending: '等板子心跳', sent: '已下发,板子升级中', deferred: '推迟',
  success: '已升级', failed: '失败', cancelled: '已取消',
};

/** 固件记录 → 心跳回复里给板子的格式(板子按 hasUpdate/url/sha256/size 解析) */
function fwDto(fw, host) {
  return {
    hasUpdate: true,
    id: fw.id,
    version: fw.version,
    size: Number(fw.size) || 0,
    sha256: fw.sha256 || '',
    notes: fw.notes || '',
    url: `http://${host}/api/v1/board/firmware/download/${fw.id}`,
  };
}

/**
 * 比较版本号:a 比 b 新 ⇒ 正数,一样 ⇒ 0,旧 ⇒ 负数。
 * 按 . 分段比数字(1.10.0 > 1.9.0);数字一样时,带后缀的(1.4.0-test)算比不带的旧。
 * 空版本(板子没报)当作最旧。
 */
function cmpVer(a, b) {
  const split = v => {
    const s = String(v || '');
    const m = s.match(/^(\d+(?:\.\d+)*)(.*)$/);
    return m ? { nums: m[1].split('.').map(Number), rest: m[2] } : { nums: [], rest: s };
  };
  const x = split(a), y = split(b);
  for (let i = 0; i < Math.max(x.nums.length, y.nums.length); i++) {
    const d = (x.nums[i] || 0) - (y.nums[i] || 0);
    if (d) return d;
  }
  if (x.rest === y.rest) return 0;
  if (!x.rest) return 1;
  if (!y.rest) return -1;
  return x.rest < y.rest ? -1 : 1;
}

/** 这块板子的固件认不认得远程升级命令 */
function supportsRemote(caps) {
  return String(caps || '').split(',').map(s => s.trim()).includes(OTA_CAP);
}

async function getRow(mac) {
  return db.one('SELECT * FROM board_ota WHERE mac = ?', [mac]);
}

/** 改一行(没有就建);fields 的 key 就是列名 */
async function setRow(mac, fields) {
  const keys = Object.keys(fields);
  await db.run(
    `INSERT INTO board_ota (mac, ${keys.join(', ')}) VALUES (?${', ?'.repeat(keys.length)})
     ON DUPLICATE KEY UPDATE ${keys.map(k => `${k} = VALUES(${k})`).join(', ')}`,
    [mac, ...keys.map(k => fields[k])]);
}

/** 状态变化统一走这里(顺带记时间) */
function setState(mac, state, msg, extra) {
  return setRow(mac, Object.assign({ state, state_msg: String(msg || '').slice(0, 255), state_at: new Date() },
    extra || {}));
}

/** 15 分钟内要触发(或刚触发)的「指定时刻」定时任务;没有返回 null */
async function upcomingSchedule(mac) {
  return db.one(
    `SELECT flow_name, fire_at FROM schedules
     WHERE board_mac = ? AND mode = 'at' AND enabled = 1 AND fire_at IS NOT NULL
       AND fire_at BETWEEN NOW() - INTERVAL ? MINUTE AND NOW() + INTERVAL ? MINUTE
     ORDER BY fire_at LIMIT 1`, [mac, GUARD_AFTER_MIN, GUARD_BEFORE_MIN]);
}

function fmtHm(d) {
  const t = new Date(d), p = n => String(n).padStart(2, '0');
  return `${p(t.getHours())}:${p(t.getMinutes())}`;
}

/**
 * 板子心跳时调:先处理板子报上来的结果,再决定这次回复里要不要带升级命令。
 *
 * @param {object} b   心跳 body(fw / caps / running / otaReport)
 * @param {string} mac 小写 MAC
 * @param {object|null} autoFw  按渠道 + 策略算出来的「该升到的版本」(firmwareForBoard 的结果)
 * @returns {object|null} 固件记录(要下发)或 null
 */
async function decide(b, mac, autoFw) {
  const row = await getRow(mac);
  if (!row) return null;
  const fw = String(b.fw || '');
  const rep = b.otaReport && typeof b.otaReport === 'object' ? b.otaReport : null;
  const now = Date.now();

  // ① 已经是目标版本了 ⇒ 成功(重启后的第一次心跳就会走到这里)
  if (row.to_fw && fw === row.to_fw && ['pending', 'sent', 'deferred'].includes(row.state)) {
    await setState(mac, 'success', `已从 ${row.from_fw || '?'} 升级到 ${fw}`, { want_fw_id: null });
    console.log(`[ota] ${mac} 升级成功 ${row.from_fw} → ${fw}`);
    return null;
  }

  // ② 板子报上来的结果
  if (rep && rep.state === 'failed' && ['pending', 'sent', 'deferred'].includes(row.state)) {
    const extra = { want_fw_id: null };
    if (row.origin === 'auto') extra.last_fail_fw = String(rep.ver || row.to_fw || '').slice(0, 32);
    await setState(mac, 'failed', '板子报告:' + (rep.msg || '升级失败'), extra);
    console.log(`[ota] ${mac} 升级失败:${rep.msg}`);
    return null;
  }
  if (rep && rep.state === 'deferred' && row.state === 'sent') {
    await setState(mac, 'deferred', '板子忙:' + (rep.msg || '') + ',下次心跳再发');
    row.state = 'deferred';
    row.state_msg = '';
  }

  // ③ 发下去太久没结果 ⇒ 判失败
  if (row.state === 'sent' && row.state_at && now - new Date(row.state_at).getTime() > SENT_TIMEOUT_MS) {
    const extra = { want_fw_id: null };
    if (row.origin === 'auto') extra.last_fail_fw = row.to_fw;
    await setState(mac, 'failed', '下发 10 分钟没有结果(板子可能下载失败或断网了)', extra);
    return null;
  }

  // ④ 这次要升到哪个版本
  let target = null;
  let origin = '';
  if (row.want_fw_id) {
    if (row.want_at && now - new Date(row.want_at).getTime() > WANT_EXPIRE_MS) {
      await setState(mac, 'cancelled', '24 小时内一直没有合适的时机,命令作废', { want_fw_id: null });
      return null;
    }
    target = await db.one('SELECT * FROM firmware WHERE id = ?', [row.want_fw_id]);
    if (!target) {
      await setState(mac, 'failed', '目标固件已被删除', { want_fw_id: null });
      return null;
    }
    origin = 'manual';
  } else if (row.auto && autoFw && autoFw.version !== fw && autoFw.version !== row.last_fail_fw) {
    target = autoFw;
    origin = 'auto';
  }
  if (!target) return null;

  if (target.version === fw) {
    await setState(mac, 'success', `已经是 ${fw} 了`, { want_fw_id: null });
    return null;
  }
  if (!supportsRemote(b.caps)) {
    // 老固件认不得 ⇒ 手动命令直接失败(说清楚怎么办);自动的不刷屏,静默跳过
    if (origin === 'manual') {
      await setState(mac, 'failed',
        `板子固件 ${fw || '?'} 不支持远程升级:先在 App 里点一次「升级板子固件」或用后台「USB 刷机」刷到 1.4.0 以上`,
        { want_fw_id: null });
    }
    return null;
  }

  // ⑤ 忙不忙
  let busy = '';
  if (b.running) busy = `正在跑流程「${b.running}」`;
  if (!busy) {
    const s = await upcomingSchedule(mac);
    if (s) busy = `定时任务「${s.flow_name}」${fmtHm(s.fire_at)} 要执行,等它过了再升`;
  }
  const base = { origin, to_fw: target.version };
  if (row.state !== 'sent' || row.to_fw !== target.version) base.from_fw = fw;
  if (busy) {
    if (row.state !== 'deferred' || row.state_msg !== busy) await setState(mac, 'deferred', busy, base);
    else await setRow(mac, base);
    return null;
  }
  // 已经发过、还在等结果:照样再带一次(上次可能没收到),但**不刷新时间**,不然 10 分钟超时永远到不了
  if (row.state === 'sent' && row.to_fw === target.version) return target;
  await setState(mac, 'sent', `已下发 ${target.version},等板子下载重启(约 30 秒)`, base);
  console.log(`[ota] ${mac} 下发升级 ${fw} → ${target.version}(${origin === 'auto' ? '自动' : '手动'})`);
  return target;
}

/**
 * 后台下发:让这些板子升级到 fwId
 * @returns {Array<{mac, ok, message}>}
 */
async function request(macs, fwId, userId) {
  const fw = await db.one('SELECT * FROM firmware WHERE id = ?', [fwId]);
  if (!fw) { const e = new Error('没有这个固件'); e.status = 404; throw e; }
  const out = [];
  for (const raw of macs) {
    const mac = String(raw || '').toLowerCase().trim();
    if (!mac) continue;
    const board = await db.one('SELECT mac, fw FROM boards WHERE mac = ?', [mac]);
    if (!board) { out.push({ mac, ok: false, message: '没有这块板子' }); continue; }
    const pol = await db.one('SELECT mode FROM policies WHERE mac = ?', [mac]);
    if (pol && pol.mode === 'deny') {
      out.push({ mac, ok: false, message: '升级策略是「永不升级」,先改策略' });
      continue;
    }
    if (board.fw === fw.version) { out.push({ mac, ok: false, message: `已经是 ${fw.version} 了` }); continue; }
    await setState(mac, 'pending', `等板子下次心跳(最多 2 分钟)`, {
      want_fw_id: fw.id, want_at: new Date(), want_by: userId || null,
      origin: 'manual', from_fw: board.fw || '', to_fw: fw.version,
    });
    out.push({ mac, ok: true, message: `已排队:${board.fw || '?'} → ${fw.version}` });
  }
  return out;
}

/** 取消还没执行的命令 */
async function cancel(mac) {
  const row = await getRow(mac);
  if (!row || !['pending', 'deferred', 'sent'].includes(row.state)) return false;
  await setState(mac, 'cancelled',
    row.state === 'sent' ? '已取消(板子如果已经开始下载,这次仍会升完)' : '已取消', { want_fw_id: null });
  return true;
}

/** 开 / 关自动升级;重新打开时清掉「失败过的版本」,允许再试 */
async function setAuto(macs, on) {
  for (const raw of macs) {
    const mac = String(raw || '').toLowerCase().trim();
    if (!mac) continue;
    await setRow(mac, on ? { auto: 1, last_fail_fw: '' } : { auto: 0 });
  }
}

/** 给后台页面看的状态 */
function toDto(row) {
  if (!row) return { auto: false, state: '', stateText: '', msg: '', at: 0 };
  return {
    auto: !!row.auto,
    state: row.state || '',
    stateText: STATE_TEXT[row.state] || '',
    msg: row.state_msg || '',
    at: row.state_at ? new Date(row.state_at).getTime() : 0,
    fromFw: row.from_fw || '', toFw: row.to_fw || '',
    origin: row.origin || '',
    lastFailFw: row.last_fail_fw || '',
  };
}

async function listDto() {
  const rows = await db.q('SELECT * FROM board_ota');
  const m = {};
  for (const r of rows) m[r.mac] = toDto(r);
  return m;
}

module.exports = {
  OTA_CAP, GUARD_BEFORE_MIN, SENT_TIMEOUT_MS,
  cmpVer, fwDto, supportsRemote, decide, request, cancel, setAuto, toDto, listDto, getRow,
};
