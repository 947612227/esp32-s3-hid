// ============================================================================
//  web_ui.h —— 内嵌网页(单文件,无外部依赖)
//
//  页面能力:
//    · 状态面板:WiFi/内存/运行时长/脚本数/HID/蓝牙连接数,自动刷新
//    · 脚本管理:列表(带步骤明细与备注)、运行、停止、编辑、新建、**复制**、删除
//    · 设备控制:返回/回桌面/最近任务、锁屏唤醒、截屏触发、坐标测试点击
//    · 运行日志:实时轮询 + 自动滚动 + 清空(逐步显示人类语言,如「点击:点我的」)
//    · 系统操作:恢复示例流程、修复蓝牙连接、**OTA 固件升级**、重启、
//                忘记 WiFi(进热点模式)、恢复出厂(清空脚本)
//
//  ⚠️ 这个页面管不了「定时 / 开启状态」——
//     那些存在**手机 App 的本地设置**里(SharedPreferences),不在板子上。
//     网页只能管理流程内容本身和板子的运行。
// ============================================================================
#pragma once

#include <Arduino.h>

static const char PAGE_HTML[] PROGMEM = R"HTML(<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ESP32-S3 触摸注入板 · 控制台</title>
<style>
  :root{--bg:#0d1117;--panel:#161b22;--panel2:#1c2128;--line:#30363d;--fg:#c9d1d9;
        --dim:#8b949e;--ok:#3fb950;--warn:#d29922;--err:#f85149;--acc:#58a6ff}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--fg);
       font:14px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
  header{padding:12px 18px;border-bottom:1px solid var(--line);display:flex;
         align-items:center;gap:10px;flex-wrap:wrap;background:var(--panel);
         position:sticky;top:0;z-index:10}
  h1{font-size:15px;margin:0;font-weight:600;white-space:nowrap}
  .pill{padding:2px 9px;border-radius:10px;font-size:12px;border:1px solid var(--line);
        white-space:nowrap}
  .pill.ok{color:var(--ok);border-color:var(--ok)}
  .pill.warn{color:var(--warn);border-color:var(--warn)}
  .pill.err{color:var(--err);border-color:var(--err)}
  main{padding:16px;max-width:1100px;margin:0 auto}
  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(330px,1fr));gap:14px}
  .card{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:14px}
  .card h2{font-size:13px;margin:0 0 10px;color:var(--dim);font-weight:500;
           display:flex;align-items:center;gap:8px}
  .full{grid-column:1/-1}
  table{width:100%;border-collapse:collapse}
  th,td{text-align:left;padding:6px 7px;border-bottom:1px solid var(--line);
        vertical-align:top}
  th{color:var(--dim);font-weight:500;font-size:12px}
  tr:last-child td{border-bottom:none}
  button{background:#21262d;color:var(--fg);border:1px solid var(--line);
         border-radius:6px;padding:5px 10px;cursor:pointer;font-size:13px;
         white-space:nowrap}
  button:hover{border-color:var(--acc);color:var(--acc)}
  button.primary{background:#1f6feb;border-color:#1f6feb;color:#fff}
  button.primary:hover{background:#388bfd;color:#fff}
  button.danger:hover{border-color:var(--err);color:var(--err)}
  button:disabled{opacity:.45;cursor:not-allowed}
  input,textarea,select{background:#0d1117;color:var(--fg);border:1px solid var(--line);
         border-radius:6px;padding:5px 8px;font-size:13px}
  input,textarea{font-family:ui-monospace,Menlo,monospace}
  textarea{min-height:130px;resize:vertical;line-height:1.5;width:100%}
  #log{background:#010409;border:1px solid var(--line);border-radius:6px;padding:9px;
       height:200px;overflow:auto;font-family:ui-monospace,Menlo,monospace;
       font-size:12px;white-space:pre-wrap;color:var(--dim)}
  .row{display:flex;gap:7px;align-items:center;flex-wrap:wrap}
  .muted{color:var(--dim);font-size:12px}
  .steps{margin:0;padding-left:16px;color:var(--dim);font-size:12px}
  code{background:#010409;padding:1px 5px;border-radius:4px;font-size:12px}
  .kv{display:grid;grid-template-columns:auto 1fr;gap:2px 12px;font-size:13px}
  .kv b{color:var(--dim);font-weight:500}
  .bar{height:6px;background:#010409;border-radius:3px;overflow:hidden;margin-top:4px}
  .bar > i{display:block;height:100%;background:var(--acc);width:0;transition:width .2s}
  .pad{position:relative;background:#010409;border:1px solid var(--line);border-radius:6px;
       height:200px;cursor:crosshair;overflow:hidden}
  .pad i{position:absolute;width:12px;height:12px;margin:-6px 0 0 -6px;border-radius:50%;
         background:var(--acc);opacity:.9;display:none}
  .pad span{position:absolute;left:6px;top:4px;font-size:11px;color:#4b5563}
</style></head><body>
<header>
  <h1>ESP32-S3 触摸注入板</h1>
  <span id="pNet" class="pill">连接中…</span>
  <span id="pHid" class="pill">HID</span>
  <span id="pRun" class="pill">空闲</span>
  <span style="margin-left:auto"></span>
  <button onclick="loadAll();pullLogs();">刷新</button>
</header>
<main>
<div class="grid">

  <!-- ============ 状态 ============ -->
  <div class="card">
    <h2>板子状态</h2>
    <div class="kv" id="sysInfo"><span class="muted">加载中…</span></div>
  </div>

  <!-- ============ 设备控制 ============ -->
  <div class="card">
    <h2>设备控制</h2>
    <div class="row" style="margin-bottom:8px">
      <button onclick="ctl('home')">回桌面</button>
      <button onclick="ctl('back')">返回</button>
      <button onclick="ctl('recents')">最近任务</button>
      <button onclick="ctl('wake')">唤醒屏幕</button>
    </div>
    <div class="row" style="margin-bottom:8px">
      <span class="muted">关键键:</span>
      <button onclick="ctl('power')">电源</button>
      <button onclick="ctl('volup')">音量+</button>
      <button onclick="ctl('voldown')">音量−</button>
      <button onclick="ctl('mute')">静音</button>
    </div>
    <div class="row">
      <span class="muted">滑动:</span>
      <button onclick="swipe(0.5,0.75,0.5,0.25)">上滑</button>
      <button onclick="swipe(0.5,0.25,0.5,0.75)">下滑</button>
      <button onclick="swipe(0.85,0.5,0.15,0.5)">左滑</button>
      <button onclick="swipe(0.15,0.5,0.85,0.5)">右滑</button>
    </div>
  </div>

  <!-- ============ 坐标测试 ============ -->
  <div class="card">
    <h2>坐标测试 / 快速点击</h2>
    <div class="pad" id="pad" onclick="padClick(event)">
      <span>在这里点一下 = 手机屏幕对应位置就被点一下</span><i id="padDot"></i>
    </div>
    <div class="row" style="margin-top:8px">
      <input id="cXY" placeholder="例如 0.5,0.5 或 50%,50%" style="width:180px">
      <button class="primary" onclick="tapXY()">点这个位置</button>
      <button onclick="longXY()">长按</button>
      <span class="muted">刚点过:<span id="lastXY">—</span></span>
    </div>
  </div>

  <!-- ============ 系统操作 ============ -->
  <div class="card">
    <h2>系统操作</h2>
    <div class="row" style="margin-bottom:8px">
      <button onclick="stopScript()">停止脚本</button>
      <button onclick="ctl('releaseall')">抬起全部触点</button>
      <button onclick="clearLogs()">清空日志</button>
    </div>
    <div class="row" style="margin-bottom:8px">
      <span class="muted">频率(每隔一段时间自动重跑一遍):</span>
      <select id="schedSel" style="max-width:150px"></select>
      <input id="schedMs" type="number" value="100" min="10" step="10" style="width:90px">
      <span class="muted">毫秒</span>
      <button class="primary" onclick="schedStart()">开始</button>
      <button onclick="schedStop()">停止</button>
    </div>
    <div class="row" style="margin-bottom:8px">
      <span class="muted">给板子配网(改 WiFi):</span>
      <input id="wifiSsid" placeholder="WiFi 名称" style="width:150px">
      <input id="wifiPass" placeholder="密码" type="password" style="width:130px">
      <button class="primary" onclick="wifiSet()">连接</button>
      <span class="muted">配错会自动回退到原网络</span>
    </div>
    <div class="row" style="margin-bottom:8px">
      <button onclick="seedExamples()">恢复示例流程</button>
      <button onclick="fixBle()">修复蓝牙连接</button>
      <span class="muted">蓝牙显示已连接但注入没反应时点这个</span>
    </div>
    <div class="row" style="margin-bottom:8px">
      <span class="muted">固件升级(OTA,不用插 USB):</span>
      <input type="file" id="fwFile" accept=".bin" style="width:auto">
      <button class="primary" onclick="otaUpload()">上传并重启</button>
      <span class="muted">升级不会动流程</span>
    </div>
    <div class="row">
      <button class="danger" onclick="sysOp('reboot')">重启板子</button>
      <button class="danger" onclick="forgetWifi()">忘记 WiFi(进热点模式)</button>
      <button class="danger" onclick="sysOp('factory')">恢复出厂(清空流程)</button>
    </div>
  </div>

  <!-- ============ 脚本列表 ============ -->
  <div class="card full">
    <h2>流程列表 <span class="muted" id="scriptCount"></span></h2>
    <div class="muted" style="margin-bottom:8px">
      这里管理的是**流程内容**。定时 / 开启状态存在**手机 App** 里,网页管不了。
    </div>
    <table><thead><tr>
      <th style="width:34px">#</th><th>名称 / 步骤</th>
      <th style="width:250px">操作</th>
    </tr></thead><tbody id="scripts">
      <tr><td colspan="3" class="muted">加载中…</td></tr>
    </tbody></table>
  </div>

  <!-- ============ 编辑器 ============ -->
  <div class="card full">
    <h2>新建 / 编辑流程</h2>
    <div class="row" style="margin-bottom:8px">
      <input id="name" placeholder="流程名,可用中文,如 看视频流程" style="max-width:260px">
      <button class="primary" onclick="saveScript()">保存到板子</button>
      <button onclick="$('#body').value='';$('#name').value='';">清空编辑框</button>
      <span class="muted">保存即写入板子 Flash(断电不丢)</span>
    </div>
    <textarea id="body" spellcheck="false" placeholder="每行一个动作,如:
T 0.5 0.5 60 点我的
wait 300 等页面加载
S 0.5 0.75 0.5 0.25 260 上滑列表"></textarea>
    <div class="muted" style="margin-top:6px">
      💡 <b>备注(可选)</b>:写在参数**后面**的文字,例如
      <code>T 0.5 0.5 60 点我的</code> ——
      日志会显示「点击:点我的」而不是一串坐标。不写也行。
    </div>
    <details style="margin-top:8px">
      <summary class="muted" style="cursor:pointer">脚本语法速查</summary>
      <div class="muted" style="margin-top:6px">
        <code>T x y [ms]</code> 点击 · <code>D x y [gap]</code> 双击 ·
        <code>H x y [ms]</code> 长按<br>
        <code>S x1 y1 x2 y2 [ms]</code> 直线滑 ·
        <code>C x1 y1 cx cy x2 y2 [ms]</code> 曲线滑<br>
        <code>P x1 y1 x2 y2 [gap] [ms]</code> 双指滑 ·
        <code>Z cx cy gap1 gap2 [ms]</code> 捏合<br>
        <code>K usage</code> 按键(<b>Android 上不生效</b>) ·
        <code>V usage</code> 多媒体 · <code>wait ms</code> 等待<br>
        <code>repeat n</code> 重复其后全部步骤 · <code>goto label</code> 跳到 <code>#label</code><br>
        <b>页面相关</b>(需手机 App 开启「页面识别」并在运行中):<br>
        <code>waitfor 关键字 [超时ms]</code> 等到页面上出现该关键字再继续(默认最多 15 秒)<br>
        <code>ifpage 关键字</code> … <code>else</code> … <code>endif</code> 按当前页面分支<br>
        关键字:写 Activity 短名即可(<code>NcovSkuActivity</code>),
        加 <code>@</code> 前缀只匹配 Activity;不加则 Activity 和页面文字都找。**包含匹配,忽略大小写**<br>
        坐标 <code>0.5</code> / <code>50%</code> / <code>16384</code>;行首 <code>#</code> 注释<br>
        <b>备注(可选):</b>参数后面接一段文字,如 <code>T 0.5 0.5 60 点我的</code>;
        日志会用「指令:备注」显示(<code>点击:点我的</code>),不写就报坐标<br>
        <b>底部三键导航:</b>最近任务 <code>0.22,0.963</code> ·
        回桌面 <code>0.50,0.963</code> · 返回 <code>0.78,0.963</code>
      </div>
    </details>
  </div>

  <!-- ============ 日志 ============ -->
  <div class="card full">
    <h2>运行日志
      <label class="muted" style="font-weight:400;cursor:pointer">
        <input type="checkbox" id="autoscroll" checked style="width:auto"> 自动滚动
      </label>
      <button style="margin-left:auto" onclick="clearLogs()">清空</button>
    </h2>
    <div id="log">(暂无日志)</div>
  </div>

</div>
</main>
<script>
const $ = s => document.querySelector(s);
let scripts = [], seenLogs = 0, running = false;

function note(msg) {
  const el = $('#log');
  if (el.textContent === '(暂无日志)') el.textContent = '';
  el.textContent += `[页面] ${msg}\n`;
  if ($('#autoscroll').checked) el.scrollTop = el.scrollHeight;
}

async function api(path, opts) {
  try {
    const r = await fetch(path, opts);
    const t = await r.text();
    try { return JSON.parse(t); } catch (e) { return { message: t }; }
  } catch (e) { return { message: '请求失败: ' + e }; }
}

function post(path) { return api(path, {method:'POST'}); }

// ---------------------------------------------------------------- 状态
async function loadAll() {
  const s = await api('/api/status');
  if (s.message && !s.mode) { note(s.message); return; }

  const pn = $('#pNet'), ph = $('#pHid'), pr = $('#pRun');
  pn.textContent = s.mode + ' · ' + s.ip;
  pn.className = 'pill ' + (s.mode === 'STA' ? 'ok' : 'warn');
  ph.textContent = s.hid ? 'HID 就绪' : 'HID 未连接';
  ph.className = 'pill ' + (s.hid ? 'ok' : 'err');

  const r = s.run || {};
  running = !!r.running;
  pr.textContent = r.running
      ? `运行中 ${r.step}/${r.totalSteps}`
      : (r.lastOk === false ? '上次失败' : '空闲');
  pr.className = 'pill ' + (r.running ? 'warn' : (r.lastOk === false ? 'err' : 'ok'));

  const pct = r.running && r.totalSteps ? Math.round(r.step*100/r.totalSteps) : 0;
  $('#sysInfo').innerHTML = `
    <b>网络</b><span>${s.mode} · ${s.ip}</span>
    <b>访问地址</b><span><a href="${s.url}" style="color:var(--acc)">${s.url}</a></span>
    <b>HID</b><span>${s.hid ? '✅ 就绪' : '❌ 未连接(手机没连/没订阅)'}</span>
    <b>蓝牙</b><span id="bleInfo" class="muted">查询中…</span>
    <b>空闲内存</b><span>${(s.heap/1024).toFixed(1)} KB</span>
    <b>运行时长</b><span>${fmtUptime(s.uptime)}</span>
    <b>脚本数</b><span>${s.scriptCount} 个 · Flash 已用 ${s.fsUsedKB} KB</span>
    <b>执行次数</b><span>${r.runCount||0} 次${r.lastName?` · 上次:${r.lastName}`:''}</span>
    ${r.running ? `<b>进度</b><span>第 ${r.step}/${r.totalSteps} 步
      ${r.repeatTotal>1?`(第 ${r.repeatDone}/${r.repeatTotal} 遍)`:''}
      · ${r.elapsedMs}ms<div class="bar"><i style="width:${pct}%"></i></div></span>` : ''}
    ${r.lastError ? `<b>上次错误</b><span style="color:var(--err)">${r.lastError}</span>` : ''}
    ${s.sched && s.sched.active ? `<b>频率</b><span>${s.sched.name} 每 ${s.sched.intervalMs}ms</span>` : ''}
  `;

  if (scripts.length !== s.scriptCount) await loadScripts();
}

// 蓝牙连接诊断:/api/ble?op=info 只在 POST 上,单独拉一次
async function loadBleInfo() {
  try {
    const d = await api('/api/ble?op=info', { method: 'POST' });
    const el = $('#bleInfo');
    if (!el) return;
    if (d.peers === undefined) { el.textContent = '—'; return; }
    const warn = d.peers > 0 && !(d.subs > 0);
    el.innerHTML = `已连 ${d.peers}/${d.maxConnections} 台 · 订阅 ${d.subs||0}`
      + ` · 配对 ${d.bonds}/${d.maxBonds}`
      + (warn ? ' <span style="color:var(--warn)">(连上但没订阅 → 点「修复蓝牙连接」)</span>' : '');
  } catch (e) {
    const el = $('#bleInfo');
    if (el && el.textContent === '查询中…') el.textContent = '—';
  }
}

function fmtUptime(sec) {
  sec = Math.floor(sec||0);
  const h = Math.floor(sec/3600), m = Math.floor(sec%3600/60), s = sec%60;
  return h ? `${h}小时${m}分` : (m ? `${m}分${s}秒` : `${s}秒`);
}

// ---------------------------------------------------------------- 脚本
async function loadScripts() {
  const d = await api('/api/scripts');
  scripts = d.scripts || [];
  $('#scriptCount').textContent = `(共 ${scripts.length} 个)`;

  const sel = $('#schedSel');
  const cur = sel.value;
  sel.innerHTML = scripts.map(s => `<option value="${s.index}">${s.name}</option>`).join('');
  if (cur) sel.value = cur;

  const tb = $('#scripts');
  if (!scripts.length) {
    tb.innerHTML = '<tr><td colspan="3" class="muted">还没有流程,用下面的编辑框新建一个</td></tr>';
    return;
  }
  tb.innerHTML = scripts.map(s => `
    <tr>
      <td class="muted">${s.index}</td>
      <td><b>${s.name}</b>
          <ul class="steps">${(s.stepText||[]).map(x=>`<li>${x}</li>`).join('')}</ul></td>
      <td>
        <div class="row" style="margin-bottom:4px">
          <button class="primary" onclick="runScript(${s.index},1,0)">运行</button>
          <input id="rp${s.index}" type="number" value="1" min="1" max="99"
                 style="width:56px" title="重复次数">
          <span class="muted">次</span>
        </div>
        <div class="row">
          <button onclick="runScript(${s.index}, $('#rp${s.index}').value, 1000)">循环</button>
          <button onclick="editScript(${s.index})">编辑</button>
          <button onclick="copyScript(${s.index}, '${s.name}')">复制</button>
          <button class="danger" onclick="delScript('${s.name}')">删除</button>
        </div>
      </td>
    </tr>`).join('');
}

function runScript(i, repeat, interval) {
  repeat = parseInt(repeat) || 1;
  post(`/api/run?index=${i}&repeat=${repeat}&interval=${interval||0}`)
    .then(r => { note(r.message || JSON.stringify(r)); setTimeout(loadAll, 1200); });
}

function editScript(i) {
  api('/api/script?index=' + i).then(d => {
    $('#name').value = d.name || '';
    $('#body').value = d.text || '';
    note('已把「' + (d.name || '') + '」载入编辑框');
    $('#body').scrollIntoView({behavior:'smooth', block:'center'});
  });
}

function saveScript() {
  const name = $('#name').value.trim();
  if (!name) { alert('请填一个流程名(可以用中文)'); return; }
  api('/api/save?name=' + encodeURIComponent(name),
      {method:'POST', headers:{'Content-Type':'text/plain'}, body: $('#body').value})
    .then(r => { note(r.message || JSON.stringify(r)); if (r.ok) loadAll(); });
}

// 生成不重名的副本名:"原名 副本" / "原名 副本2" / "原名 副本3" …
function uniqueCopyName(base, existing) {
  // 如果本来就叫「XX 副本N」,把后缀摘掉再编号,避免出现「XX 副本 副本2」
  const m = base.match(/^(.*?) 副本\d*$/);
  if (m) base = m[1];

  let cand = base + ' 副本';
  if (!existing.includes(cand)) return cand;
  for (let i = 2; i < 100; i++) {
    cand = base + ' 副本' + i;
    if (!existing.includes(cand)) return cand;
  }
  return base + ' 副本' + Date.now();
}

// 复制流程:读原文 → 用新名字再存一份 → 两者完全独立
// ⚠️ 板子的 /api/save 是"同名即覆盖",所以名字**必须避重**,
//    否则会把已有流程覆盖掉。
async function copyScript(i, name) {
  const all = await api('/api/scripts');
  const names = (all.scripts || []).map(s => s.name);

  let newName = prompt('复制为(新流程):', uniqueCopyName(name, names));
  if (!newName) return;
  newName = newName.trim();
  if (!newName) return;

  // 用户可能改成了已存在的名字 → 再避重一次,别覆盖别人的流程
  if (names.includes(newName)) {
    const fixed = uniqueCopyName(newName, names);
    note('「' + newName + '」已存在,自动改用「' + fixed + '」');
    newName = fixed;
  }

  const d = await api('/api/script?index=' + i);
  const text = d.text || '';
  if (!text.trim()) { note('这个流程内容为空,没得复制'); return; }
  const r = await api('/api/save?name=' + encodeURIComponent(newName), {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: text
  });
  note(r.message || ('已复制为 ' + newName));
  loadScripts();
}

function delScript(name) {
  if (!confirm('删除脚本 ' + name + ' ?')) return;
  post('/api/delete?name=' + encodeURIComponent(name))
    .then(r => { note(r.message || JSON.stringify(r)); loadAll(); });
}

// ---------------------------------------------------------------- 控制
function seedExamples() {
  post('/api/seed').then(r => { note(r.message || JSON.stringify(r)); loadScripts(); });
}
function fixBle() {
  post('/api/ble?op=reset').then(r => note(r.message || JSON.stringify(r)));
}
function forgetWifi() {
  if (!confirm('确定忘记当前 WiFi 吗?\n\n板子会重启并开热点 ESP32-Touch-XXXX(密码 12345678),\n之后要重新配网。')) return;
  post('/api/system?op=forgetwifi').then(r => note(r.message || JSON.stringify(r)));
}
function otaUpload() {
  const f = $('#fwFile').files[0];
  if (!f) { alert('先选一个 .bin 固件'); return; }
  if (!confirm('上传 ' + f.name + ' (' + Math.round(f.size/1024) + ' KB) 并重启板子?')) return;
  const fd = new FormData();
  fd.append('firmware', f);
  note('正在上传固件…(约 20 秒,期间不要断电)');
  fetch('/api/ota', { method: 'POST', body: fd })
    .then(r => r.json())
    .then(r => {
      note(r.message || JSON.stringify(r));
      setTimeout(() => { note('板子重启中,约 15 秒后刷新页面…'); }, 800);
    })
    .catch(e => note('上传失败: ' + e));
}
function ctl(what) {
  post('/api/control?do=' + what).then(r => {
    note('控制 ' + what + ' → ' + (r.message || JSON.stringify(r)));
    setTimeout(loadAll, 900);
  });
}

function swipe(x1,y1,x2,y2) {
  post(`/api/gesture?op=swipe&a=${x1}&b=${y1}&c=${x2}&d=${y2}&ms=300`)
    .then(r => note(r.message || JSON.stringify(r)));
}

function padClick(ev) {
  const pad = $('#pad'), r = pad.getBoundingClientRect();
  const nx = (ev.clientX - r.left) / r.width;
  const ny = (ev.clientY - r.top) / r.height;
  const dot = $('#padDot');
  dot.style.display = 'block';
  dot.style.left = (nx*100) + '%';
  dot.style.top  = (ny*100) + '%';
  $('#lastXY').textContent = nx.toFixed(3) + ',' + ny.toFixed(3);
  post(`/api/gesture?op=tap&a=${nx.toFixed(4)}&b=${ny.toFixed(4)}&ms=60`)
    .then(r => note(r.message || JSON.stringify(r)));
}

function tapXY() {
  const v = $('#cXY').value.trim();
  if (!v) { alert('填个坐标,如 0.5,0.5'); return; }
  post(`/api/gesture?op=tap&xy=${encodeURIComponent(v)}&ms=60`)
    .then(r => note(r.message || JSON.stringify(r)));
}

function longXY() {
  const v = $('#cXY').value.trim();
  if (!v) { alert('填个坐标'); return; }
  post(`/api/gesture?op=long&xy=${encodeURIComponent(v)}&ms=900`)
    .then(r => note(r.message || JSON.stringify(r)));
}

function stopScript() { post('/api/stop').then(r => note(r.message || '已请求停止')); }

function wifiSet() {
  const ssid = document.getElementById('wifiSsid').value.trim();
  const pass = document.getElementById('wifiPass').value;
  if (!ssid) { note('请填 WiFi 名称'); return; }
  note('正在连接,最多等 20 秒…');
  post(`/api/wifi?ssid=${encodeURIComponent(ssid)}&pass=${encodeURIComponent(pass)}`)
    .then(r => note(r.message || '已提交'));
}

function schedStart() {
  const i = parseInt(document.getElementById('schedSel').value, 10);
  if (isNaN(i)) { note('请先选择一个流程'); return; }
  let ms = parseInt(document.getElementById('schedMs').value, 10) || 100;
  if (ms < 10) { ms = 10; note('最小 10 毫秒(实测 BLE 物理下界约 11ms)'); }
  post(`/api/schedule?index=${i}&ms=${ms}`)
    .then(r => note(r.message || '已开始频率执行'));
}

function schedStop() { post('/api/schedule/stop').then(r => note(r.message || '已停止频率执行')); }

function sysOp(op) {
  const msg = op === 'factory'
      ? '确定清空板子上所有流程?(不可撤销,重启后会写回 7 个示例流程)'
      : '确定重启板子?';
  if (!confirm(msg)) return;
  post('/api/system?op=' + op).then(r => note(r.message || JSON.stringify(r)));
}

// ---------------------------------------------------------------- 日志
async function pullLogs() {
  const d = await api('/api/logs');
  const lines = d.lines || [];
  const el = $('#log');
  if (lines.length !== seenLogs) {
    if (lines.length < seenLogs) { el.textContent = ''; seenLogs = 0; }
    for (let i = seenLogs; i < lines.length; i++) el.textContent += lines[i] + '\n';
    seenLogs = lines.length;
    if ($('#autoscroll').checked) el.scrollTop = el.scrollHeight;
  }
}

async function clearLogs() {
  await post('/api/logs/clear');
  $('#log').textContent = ''; seenLogs = 0;
}

// ---------------------------------------------------------------- 启动
loadAll();
loadScripts();
pullLogs();
setInterval(loadAll, 1500);
setInterval(pullLogs, 2000);
setInterval(loadBleInfo, 4000);
loadBleInfo();
</script>
</body></html>)HTML";
