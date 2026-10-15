#!/usr/bin/env node
/**
 * 后台页面冒烟测试(在 Mac 上用 Edge 无头模式跑,不点任何会改数据的按钮)
 *
 *   1. 登录后打开控制中心:有「固件升级」列、批量按钮;页面没有 JS 报错
 *   2. 打开「USB 刷机」页:
 *      - 不带 flag 启动 ⇒ 提示要改 edge://flags(http 地址没有 Web Serial)
 *      - 带 --unsafely-treat-insecure-origin-as-secure 启动 ⇒ navigator.serial 可用、没有提示;
 *        页面里动态加载 esptool-js 和 usbflash.js,并用 fetchFlashPlan 把一个固件的 4 段下载下来核对大小
 *   截图存到 /tmp/tp-ui-*.png
 *
 * 用法: TP_ADMIN_PASS=后台密码 node tests/hw/admin_ui_edge.mjs [--base http://120.55.183.206] [--src fw-<id>]
 */
import { spawn } from 'child_process';
import fs from 'fs';

const args = {};
for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
const base = args.base || 'http://120.55.183.206';
const src = args.src || '';                          // 要试下载的刷机来源(fw-<id> / build-<id>)
const EDGE = '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge';
let fails = 0;
const ok = (c, m) => { console.log((c ? '  ✅ ' : '  ❌ ') + m); if (!c) fails++; };
const sleep = ms => new Promise(r => setTimeout(r, ms));

/** 起一个 Edge(独立临时配置目录),返回 CDP 会话 */
async function launch(port, flag) {
  const prof = `/tmp/tp-edge-prof-${port}`;
  fs.rmSync(prof, { recursive: true, force: true });
  const a = ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${prof}`,
             '--no-first-run', '--no-default-browser-check', '--window-size=1500,1000', 'about:blank'];
  if (flag) a.unshift(`--unsafely-treat-insecure-origin-as-secure=${base}`);
  const proc = spawn(EDGE, a, { stdio: 'ignore' });
  let ws;
  for (let i = 0; i < 50 && !ws; i++) {
    await sleep(200);
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      const pg = list.find(t => t.type === 'page');
      if (pg) ws = pg.webSocketDebuggerUrl;
    } catch (_) { /* 还没起来 */ }
  }
  if (!ws) throw new Error('Edge 没起来');
  const sock = new WebSocket(ws);
  await new Promise(r => sock.addEventListener('open', r, { once: true }));
  let id = 0;
  const wait = new Map();
  const errors = [];
  sock.addEventListener('message', ev => {
    const m = JSON.parse(ev.data);
    if (m.id && wait.has(m.id)) { wait.get(m.id)(m); wait.delete(m.id); }
    if (m.method === 'Runtime.exceptionThrown') errors.push(m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text);
    if (m.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(m.params.type)) {
      errors.push(m.params.args.map(x => x.value ?? x.description).join(' '));
    }
  });
  const send = (method, params = {}) => new Promise(r => { const i = ++id; wait.set(i, r); sock.send(JSON.stringify({ id: i, method, params })); });
  await send('Runtime.enable');
  await send('Page.enable');
  const evalJs = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.result.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || '页面脚本出错');
    return r.result.result.value;
  };
  const shot = async file => {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(file, Buffer.from(r.result.data, 'base64'));
  };
  // 等 Edge 真退出了再删配置目录(它退出时还在往里写)
  const close = async () => {
    try { sock.close(); } catch (_) { /* 已断 */ }
    const gone = new Promise(r => proc.once('exit', r));
    proc.kill();
    await Promise.race([gone, sleep(5000)]);
    fs.rmSync(prof, { recursive: true, force: true, maxRetries: 5, retryDelay: 300 });
  };
  return { send, evalJs, shot, close, errors };
}

/** 在浏览器里登录(表单提交,拿到会话 cookie) */
async function login(b) {
  await b.send('Page.navigate', { url: `${base}/admin/login` });
  await sleep(1500);
  await b.evalJs(`(async () => {
    const r = await fetch('/admin/login', { method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'username=admin&password=' + encodeURIComponent(${JSON.stringify(process.env.TP_ADMIN_PASS)}) });
    return r.status; })()`);
}

if (!process.env.TP_ADMIN_PASS) { console.error('要设 TP_ADMIN_PASS'); process.exit(2); }

// ---------------------------------------------------------------- 1 + 2a 不带 flag
console.log('1. 控制中心 / USB 刷机页(不带 flag)');
let b = await launch(9341, false);
try {
  await login(b);
  await b.send('Page.navigate', { url: `${base}/admin/#control` });
  await sleep(3500);
  const ctl = await b.evalJs(`(() => {
    const ths = [...document.querySelectorAll('section th')].map(x => x.textContent.trim());
    return { ths, bar: !!document.querySelector('#otaBar'), btn: [...document.querySelectorAll('#otaBar button')].map(x => x.textContent.trim()),
             cells: [...document.querySelectorAll('.ota-cell')].map(x => x.innerText.replace(/\\s+/g,' ').trim()) };
  })()`);
  ok(ctl.ths.includes('固件升级'), '控制中心表头有「固件升级」列');
  ok(ctl.bar && ctl.btn.some(t => t.includes('升级固件')) && ctl.btn.some(t => t.includes('开自动升级')), '批量操作栏:升级固件 / 开关自动升级');
  ok(ctl.cells.length > 0, `每块板子都有升级状态格(${ctl.cells.length} 块):` + ctl.cells.join(' | ').slice(0, 300));
  await b.shot('/tmp/tp-ui-control.png');

  await b.send('Page.navigate', { url: `${base}/admin/#usb` });
  await sleep(2500);
  const u = await b.evalJs(`(() => ({ serial: !!navigator.serial, text: document.querySelector('section[style=""], section:not([style*="none"])')?.innerText || '',
    opts: [...document.querySelectorAll('section select option')].map(o => o.textContent.trim()).filter(t => t && !t.startsWith('—')) }))()`);
  ok(!u.serial, 'http 地址默认没有 Web Serial');
  ok(u.text.includes('unsafely-treat-insecure-origin-as-secure') && u.text.includes(base), '页面提示怎么改 flag(带本站地址)');
  await b.shot('/tmp/tp-ui-usb-noflag.png');
  ok(b.errors.length === 0, '页面没有 JS 报错' + (b.errors.length ? ':' + b.errors.slice(0, 3).join(' / ') : ''));
} finally { await b.close(); }

// ---------------------------------------------------------------- 2b 带 flag
console.log('2. USB 刷机页(带 --unsafely-treat-insecure-origin-as-secure)');
b = await launch(9342, true);
try {
  await login(b);
  await b.send('Page.navigate', { url: `${base}/admin/#usb` });
  await sleep(3000);
  const u = await b.evalJs(`(() => ({ serial: !!navigator.serial, secure: window.isSecureContext,
    warn: [...document.querySelectorAll('.box')].some(x => x.innerText.includes('不能访问串口')),
    opts: [...document.querySelectorAll('section select option')].map(o => o.value).filter(v => /^(fw|build)-\\d+$/.test(v)) }))()`);
  ok(u.serial && u.secure, '加了 flag:navigator.serial 可用、算安全来源');
  ok(!u.warn, '加了 flag:不再显示串口提示');
  ok(u.opts.length > 0, '固件下拉框里有可整片刷的版本:' + u.opts.join(', '));
  const pick = src || u.opts[0];
  if (pick) {
    const r = await b.evalJs(`(async () => {
      const esptool = await import('/admin/static/vendor/esptool-js/bundle.js');
      const uf = await import('/admin/static/usbflash.js');
      const plan = await uf.fetchFlashPlan(${JSON.stringify(pick)}, u => fetch(u, { credentials: 'same-origin' }));
      return { hasLoader: typeof esptool.ESPLoader === 'function' && typeof esptool.Transport === 'function',
               files: plan.files.map(f => f.name + '@' + f.address + ':' + f.data.length + '/' + f.size),
               md5: uf.md5Hex(new TextEncoder().encode('abc')) };
    })()`);
    ok(r.hasLoader, '浏览器里加载 esptool-js 成功(ESPLoader / Transport)');
    ok(r.files.length === 4 && r.files.every(x => { const [a, b2] = x.split(':')[1].split('/'); return a === b2; }),
       `浏览器里下载 ${pick} 的 4 段,大小都对:` + r.files.join(' '));
    ok(r.md5 === '900150983cd24fb0d6963f7d28e17f72', '浏览器里 MD5 计算正确');
  }
  await b.evalJs(`(() => { const s = [...document.querySelectorAll('section select')].find(x => [...x.options].some(o => /^(fw|build)-/.test(o.value))); if (s && s.options.length > 1) { s.selectedIndex = 1; s.dispatchEvent(new Event('change')); } })()`);
  await sleep(500);
  await b.shot('/tmp/tp-ui-usb-flag.png');
  ok(b.errors.length === 0, '页面没有 JS 报错' + (b.errors.length ? ':' + b.errors.slice(0, 3).join(' / ') : ''));
} finally { await b.close(); }

console.log(fails ? `失败 ${fails} 项 ❌` : '全部通过 ✅');
process.exit(fails ? 1 : 0);
