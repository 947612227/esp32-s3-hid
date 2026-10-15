/**
 * USB 整片刷机(浏览器 Web Serial + esptool-js)
 *
 * 后台「USB 刷机」页和 tests/server/usbflash_node.mjs(Node 里接真板子测)共用这一份逻辑。
 * esptool-js 模块由调用方传进来(浏览器从 /admin/static/vendor/esptool-js/bundle.js 加载,
 * Node 测试从同一个文件加载),这里不写死路径。
 *
 * 刷入内容来自 GET /api/admin/flash/<src>(由一键构建产出的 flash.json 生成):
 *   0x0 启动程序 / 0x8000 分区表 / 0xe000 boot_app0 / 0x10000 应用固件
 * 分开写四段而不是写一个合并包 —— 合并包把空隙填 0xFF,会把 0x9000 的 NVS(WiFi 配置)抹掉;
 * 「保留数据」模式要保住 NVS,「全新板子」模式则先整片擦除。
 */

// ---------------------------------------------------------------- MD5
// esptool-js 写完每段后让芯片算一遍 MD5 和本地比,本地这份需要自己算(浏览器没有同步 MD5)
const MD5_S = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
const MD5_K = new Int32Array(64);
for (let i = 0; i < 64; i++) MD5_K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) | 0;

/** 算 Uint8Array 的 MD5,返回小写十六进制(和 esptool-js 读芯片得到的格式一致) */
export function md5Hex(data) {
  const n = data.length;
  const total = (((n + 8) >>> 6) + 1) << 6;          // 补位后长度(64 字节的整数倍)
  const buf = new Uint8Array(total);
  buf.set(data);
  buf[n] = 0x80;
  const dv = new DataView(buf.buffer);
  dv.setUint32(total - 8, (n * 8) >>> 0, true);       // 位长度低 32 位
  dv.setUint32(total - 4, Math.floor(n / 0x20000000), true); // 位长度高 32 位
  let a0 = 0x67452301, b0 = 0xefcdab89 | 0, c0 = 0x98badcfe | 0, d0 = 0x10325476;
  const M = new Int32Array(16);
  for (let off = 0; off < total; off += 64) {
    for (let j = 0; j < 16; j++) M[j] = dv.getInt32(off + j * 4, true);
    let A = a0, B = b0, C = c0, D = d0;
    for (let i = 0; i < 64; i++) {
      let F, g;
      if (i < 16) { F = (B & C) | (~B & D); g = i; }
      else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) & 15; }
      else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) & 15; }
      else { F = C ^ (B | ~D); g = (7 * i) & 15; }
      F = (F + A + MD5_K[i] + M[g]) | 0;
      A = D; D = C; C = B;
      B = (B + ((F << MD5_S[i]) | (F >>> (32 - MD5_S[i])))) | 0;
    }
    a0 = (a0 + A) | 0; b0 = (b0 + B) | 0; c0 = (c0 + C) | 0; d0 = (d0 + D) | 0;
  }
  const out = new DataView(new ArrayBuffer(16));
  [a0, b0, c0, d0].forEach((v, i) => out.setInt32(i * 4, v, true));
  return Array.from(new Uint8Array(out.buffer), x => x.toString(16).padStart(2, '0')).join('');
}

// ---------------------------------------------------------------- 下载刷机文件

/**
 * 拉刷机清单 + 每段文件
 * @param {string} src     fw-<固件id> 或 build-<构建id>
 * @param {Function} fetchFn  fetch 实现(浏览器用 window.fetch,带登录 cookie)
 * @param {string} base    接口前缀(浏览器传 '',Node 测试传 http://host)
 */
export async function fetchFlashPlan(src, fetchFn, base = '') {
  const r = await fetchFn(`${base}/api/admin/flash/${encodeURIComponent(src)}`);
  const meta = await r.json();
  if (!meta.ok) throw new Error(meta.message || '拿不到刷机清单');
  for (const f of meta.files) {
    const fr = await fetchFn(base + f.url);
    if (!fr.ok) throw new Error(`下载 ${f.name} 失败:HTTP ${fr.status}`);
    f.data = new Uint8Array(await fr.arrayBuffer());
    if (f.data.length !== f.size) throw new Error(`${f.name} 大小不对:${f.data.length} ≠ ${f.size}`);
  }
  return meta;
}

// ---------------------------------------------------------------- 刷机

/**
 * 连上板子并写入
 * @param {object} o
 *   esptool    esptool-js 模块(含 Transport / ESPLoader)
 *   port       Web Serial 的 SerialPort(或 Node 测试里的同接口垫片)
 *   plan       fetchFlashPlan 的返回
 *   eraseAll   true=全新板子(整片擦除后写入),false=保留数据(只写四段,NVS 里的 WiFi 等配置不动)
 *   baudrate   刷写波特率(默认 921600)
 *   log(s)     日志回调
 *   onProgress(已写字节, 总字节, 段名)
 * @returns {Promise<{chip:string, mac:string}>}
 */
export async function flashBoard(o) {
  const { esptool, port, plan } = o;
  const log = o.log || (() => {});
  const onProgress = o.onProgress || (() => {});
  const terminal = {
    clean() {},
    writeLine(s) { log(String(s)); },
    write(s) { if (String(s).trim()) log(String(s)); },
  };
  const transport = new esptool.Transport(port, false);
  const loader = new esptool.ESPLoader({
    transport, baudrate: o.baudrate || 921600, romBaudrate: 115200, terminal,
  });
  try {
    log('正在连接板子(进入下载模式)…');
    const chip = await loader.main();
    log('芯片:' + chip);
    if (plan.chip === 'esp32s3' && !/ESP32-S3/i.test(chip)) {
      throw new Error(`芯片不对:板子是 ${chip},固件是给 ESP32-S3 的`);
    }
    let mac = '';
    try { mac = await loader.chip.readMac(loader); } catch (_) { /* 读不到 MAC 不影响刷 */ }
    if (mac) log('MAC:' + mac);

    const total = plan.files.reduce((s, f) => s + f.data.length, 0);
    const done = new Array(plan.files.length).fill(0);
    log(o.eraseAll ? '模式:全新板子(先整片擦除,大约 20~40 秒)' : '模式:保留数据(不擦 WiFi 等配置)');
    await loader.writeFlash({
      fileArray: plan.files.map(f => ({ data: f.data, address: f.address })),
      flashMode: plan.flashMode || 'dio',
      flashFreq: plan.flashFreq || '80m',
      flashSize: plan.flashSize || '8MB',
      eraseAll: !!o.eraseAll,
      compress: true,
      reportProgress: (i, written, size) => {
        // written/size 是压缩前字节;换算到这段原始大小
        done[i] = size ? Math.round(plan.files[i].data.length * written / size) : 0;
        onProgress(done.reduce((s, x) => s + x, 0), total, plan.files[i].name);
      },
      calculateMD5Hash: md5Hex,
    });
    onProgress(total, total, '');
    log('写入完成,每段已核对 MD5;正在重启板子…');
    await loader.after('hard_reset');
    return { chip, mac };
  } finally {
    try { await transport.disconnect(); } catch (_) { /* 已断开 */ }
  }
}
