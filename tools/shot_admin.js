#!/usr/bin/env node
/**
 * 用 Edge/Chromium 的无头模式**真实渲染**管理后台并截图。
 *
 * 为什么要这么麻烦:
 *   普通 curl 只拿到 HTML,看不出**布局对不对、JS 有没有报错**。
 *   后台是 SPA(hash 路由 + fetch 渲染),必须真浏览器跑一遍才作数。
 *
 * 做法:CDP(Chrome DevTools Protocol)
 *   ① 先 curl 登录拿 tp_sess cookie
 *   ② 启动 headless Edge,开 remote-debugging-port
 *   ③ 用 WebSocket 调 CDP:设 cookie → 导航 → 截图
 *   (Node 22+ 自带全局 WebSocket,不用装 ws 包)
 *
 * 用法:node tools/shot_admin.js [页面] [输出png]
 *       node tools/shot_admin.js control /tmp/control.png
 */
const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');

const HOST = '120.55.183.206';
const PAGE = process.argv[2] || 'home';
const OUT = process.argv[3] || `/tmp/admin-${PAGE}.png`;
const EDGE = '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge';
const PORT = 9333;

// ---- ① 登录拿 cookie ----
function login() {
  return new Promise((resolve, reject) => {
    const body = 'username=admin&password=7f373c21c1cf';
    const req = http.request({
      host: HOST, port: 80, path: '/admin/login', method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded',
                 'Content-Length': Buffer.byteLength(body) },
    }, (res) => {
      const sc = res.headers['set-cookie'] || [];
      const c = sc.find(s => s.startsWith('tp_sess='));
      res.resume();
      if (!c) return reject(new Error('没拿到 tp_sess cookie'));
      resolve(c.split(';')[0].split('=')[1]);
    });
    req.on('error', reject);
    req.end(body);
  });
}

// ---- CDP 小客户端 ----
class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.waiters = new Map(); }
  static async attach(wsUrl) {
    const ws = new WebSocket(wsUrl);
    await new Promise((ok, err) => { ws.onopen = ok; ws.onerror = err; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.waiters.has(m.id)) {
        const w = c.waiters.get(m.id); c.waiters.delete(m.id);
        m.error ? w.rej(new Error(JSON.stringify(m.error))) : w.ok(m.result);
      }
    };
    return c;
  }
  send(method, params) {
    const id = ++this.id;
    return new Promise((ok, rej) => {
      this.waiters.set(id, { ok, rej });
      this.ws.send(JSON.stringify({ id, method, params: params || {} }));
      setTimeout(() => {
        if (this.waiters.has(id)) { this.waiters.delete(id); rej(new Error(method + ' 超时')); }
      }, 20000);
    });
  }
}

function getJson(path) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: PORT, path }, (res) => {
      let s = ''; res.on('data', d => s += d);
      res.on('end', () => { try { resolve(JSON.parse(s)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

(async () => {
  const token = await login();
  console.log('  登录 OK,cookie 长度', token.length);

  const proc = spawn(EDGE, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${PORT}`,
    '--user-data-dir=/tmp/edge-shot-profile',
    '--window-size=1400,1100',
    'about:blank',
  ], { stdio: 'ignore' });

  // 等调试端口起来
  let ver = null;
  for (let i = 0; i < 40 && !ver; i++) {
    try { ver = await getJson('/json/version'); } catch (e) { await new Promise(r => setTimeout(r, 250)); }
  }
  if (!ver) { proc.kill(); throw new Error('Edge 调试端口没起来'); }
  console.log('  浏览器就绪:', ver['Browser']);

  const targets = await getJson('/json/list');
  const page = targets.find(t => t.type === 'page');
  const cdp = await CDP.attach(page.webSocketDebuggerUrl);

  await cdp.send('Page.enable');
  await cdp.send('Network.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Network.setCookie', {
    name: 'tp_sess', value: token, domain: HOST, path: '/',
  });

  // 收集页面里的 JS 错误 —— 比截图更能说明问题
  const errs = [];
  cdp.ws.addEventListener('message', (ev) => {
    const m = JSON.parse(ev.data);
    if (m.method === 'Runtime.exceptionThrown') {
      errs.push(m.params.exceptionDetails.exception?.description
             || m.params.exceptionDetails.text);
    }
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
      errs.push(m.params.args.map(a => a.value || a.description).join(' '));
    }
  });

  const url = `http://${HOST}/admin/#${PAGE}`;
  await cdp.send('Page.navigate', { url });
  await new Promise(r => setTimeout(r, 6000));      // 等 fetch 渲染完

  // 顺便把页面上的关键文字读出来
  const txt = await cdp.send('Runtime.evaluate', {
    expression: 'document.body.innerText.slice(0, 1200)', returnByValue: true,
  });

  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(OUT, Buffer.from(shot.data, 'base64'));
  console.log('  截图:', OUT);

  console.log('  ── 页面文字 ──');
  String(txt.result.value || '').split('\n').filter(Boolean).slice(0, 30)
    .forEach(l => console.log('    ' + l));

  if (errs.length) {
    console.log('  ⚠️ JS 错误 ' + errs.length + ' 条:');
    errs.slice(0, 8).forEach(e => console.log('    ' + String(e).slice(0, 160)));
  } else {
    console.log('  ✅ 没有 JS 错误');
  }

  cdp.ws.close();
  proc.kill();
  process.exit(0);
})().catch(e => { console.error('  ❌', e.message); process.exit(1); });
