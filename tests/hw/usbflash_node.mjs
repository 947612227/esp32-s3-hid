#!/usr/bin/env node
/**
 * 真机验证后台「USB 刷机」(在接着板子的 Mac 上跑)
 *
 * 和浏览器页面跑的是同一份 server/public/usbflash.js + 同一个 esptool-js bundle,
 * 只是把浏览器的 Web Serial SerialPort 换成这里的垫片(底下是 npm 包 serialport),
 * 刷机文件同样从后台接口 /api/admin/flash/<src> 下载(要先登录拿 cookie)。
 *
 * 用法:
 *   npm i --prefix /tmp/usbflash-deps serialport@12      # 依赖装在临时目录,不进仓库
 *   TP_ADMIN_PASS=后台密码 node tests/hw/usbflash_node.mjs \
 *       --port /dev/cu.usbmodemXXXX --src build-12 --mode erase|keep \
 *       [--base http://120.55.183.206] [--user admin] [--deps /tmp/usbflash-deps]
 *
 * ⚠️ 正在跑流程的正式板子(BLOCKED_PORTS)禁止打开串口 —— 打开串口会让板子复位
 */
import { createRequire } from 'module';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '../..');
const BLOCKED_PORTS = ['5CBC0615521'];   // 正式板子(用户在用),碰都不能碰

// ---------------------------------------------------------------- 参数
const args = {};
for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
const portPath = args.port;                              // 串口
const src = args.src;                                    // fw-<id> / build-<id>
const mode = args.mode || 'keep';                        // erase=全新板子 keep=保留数据
const base = args.base || 'http://120.55.183.206';       // 后台地址
const user = args.user || 'admin';
const deps = args.deps || '/tmp/usbflash-deps';
if (!portPath || !src || !['erase', 'keep'].includes(mode)) {
  console.error('用法:--port <串口> --src <fw-id|build-id> --mode erase|keep');
  process.exit(2);
}
if (BLOCKED_PORTS.some(p => portPath.includes(p))) {
  console.error(`❌ ${portPath} 是正式板子,禁止打开`);
  process.exit(2);
}
if (!process.env.TP_ADMIN_PASS) { console.error('❌ 要设 TP_ADMIN_PASS'); process.exit(2); }

const { SerialPort } = createRequire(path.join(deps, 'package.json'))('serialport');

// ---------------------------------------------------------------- Web Serial 垫片
/** 只实现 esptool-js Transport 用到的:open / close / readable / writable / setSignals / getInfo */
class NodeWebSerialPort {
  constructor(p) {
    this.path = p;
    this.sp = null;
    this.readable = null;
    this.writable = null;
    this.sig = { dtr: true, rts: true };   // node serialport 的 set() 不传的信号会被设回默认,所以记住当前值
  }
  getInfo() { return { usbVendorId: 0x1a86, usbProductId: 0x55d3 }; }
  async open(o) {
    const sp = new SerialPort({ path: this.path, baudRate: o.baudRate, autoOpen: false });
    await new Promise((ok, bad) => sp.open(e => (e ? bad(e) : ok())));
    this.sp = sp;
    let onData;
    this.readable = new ReadableStream({
      start(ctrl) {
        onData = d => ctrl.enqueue(new Uint8Array(d));
        sp.on('data', onData);
        sp.once('close', () => { try { ctrl.close(); } catch (_) { /* 已关 */ } });
      },
      cancel() { sp.off('data', onData); },
    });
    this.writable = new WritableStream({
      write(chunk) {
        return new Promise((ok, bad) => sp.write(Buffer.from(chunk), e => (e ? bad(e) : sp.drain(ok))));
      },
    });
  }
  async setSignals(s) {
    if (s.dataTerminalReady !== undefined) this.sig.dtr = !!s.dataTerminalReady;
    if (s.requestToSend !== undefined) this.sig.rts = !!s.requestToSend;
    await new Promise((ok, bad) => this.sp.set({ dtr: this.sig.dtr, rts: this.sig.rts, brk: !!s.break }, e => (e ? bad(e) : ok())));
  }
  async close() {
    if (!this.sp) return;
    const sp = this.sp;
    this.sp = null;
    if (sp.isOpen) await new Promise(ok => sp.close(() => ok()));
    this.readable = null;
    this.writable = null;
  }
}

// ---------------------------------------------------------------- 登录 + 刷
async function login() {
  const r = await fetch(`${base}/admin/login`, {
    method: 'POST', redirect: 'manual',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `username=${encodeURIComponent(user)}&password=${encodeURIComponent(process.env.TP_ADMIN_PASS)}`,
  });
  const ck = (r.headers.getSetCookie ? r.headers.getSetCookie() : [r.headers.get('set-cookie') || ''])
    .map(c => c.split(';')[0]).filter(c => c.startsWith('tp_sess=')).join('; ');
  if (!ck) throw new Error('登录失败(HTTP ' + r.status + ')');
  return ck;
}

const t0 = Date.now();
const cookie = await login();
console.log('已登录后台', base);
const esptool = await import(pathToFileURL(path.join(REPO, 'server/public/vendor/esptool-js/bundle.js')).href);
const uf = await import(pathToFileURL(path.join(REPO, 'server/public/usbflash.js')).href);
const plan = await uf.fetchFlashPlan(src, u => fetch(u, { headers: { Cookie: cookie } }), base);
console.log(`固件 ${plan.label}:` + plan.files.map(f => `${f.name}@0x${f.address.toString(16)} ${f.data.length}B`).join(' / '));

let lastPct = -10;
const r = await uf.flashBoard({
  esptool, port: new NodeWebSerialPort(portPath), plan, eraseAll: mode === 'erase',
  log: s => console.log('  ' + s),
  onProgress: (done, total, name) => {
    const pct = Math.floor(done * 100 / total);
    if (pct >= lastPct + 10 || pct === 100) { lastPct = pct; console.log(`  进度 ${pct}% ${name}`); }
  },
});
console.log(`✅ 刷机完成:${plan.version} 芯片 ${r.chip} MAC ${r.mac} 用时 ${Math.round((Date.now() - t0) / 1000)} 秒`);
process.exit(0);
