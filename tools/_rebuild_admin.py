#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把管理后台重排成「左侧菜单 + 多页面」结构。

思路:不重写 JS,只重排布局 —— 各功能 section 的 HTML 原样搬进对应页面,
再补一个 hash 路由。这样风险最小。
"""
import json
import re

SRC = 'server/public/index.html'
old = open(SRC, encoding='utf-8').read()

# ---- 1. 抽出各 section 的内部 HTML ----
secs = {}
for m in re.finditer(r'<!-- =+ ([^=]+?) =+ -->\s*<section>(.*?)</section>', old, re.S):
    secs[m.group(1).strip()] = m.group(2)

tail_start = old.index('<h2 style="margin-top:22px">\U0001F4CA 成功统计')
tail = old[tail_start:old.index('<script>')]
ev_mark = '<h2 style="margin-top:22px">\U0001F514 事件明细'
stats_html = tail[:tail.index(ev_mark)]
events_html = tail[tail.index(ev_mark):]

# ---- 2. 样式:保留原样式 + 追加侧边栏 ----
style = old[old.index('<style>'):old.index('</style>')]
sidebar_css = style + """
  /* ---------------- 左侧菜单布局 ---------------- */
  body{display:flex;min-height:100vh}
  #side{width:198px;flex:0 0 198px;background:#0d1117;border-right:1px solid var(--line);
        display:flex;flex-direction:column;position:sticky;top:0;height:100vh}
  #side .brand{padding:16px 16px 12px;font-weight:700;font-size:15px;
                border-bottom:1px solid var(--line)}
  #side .brand small{display:block;color:var(--dim);font-weight:400;font-size:11px;margin-top:3px}
  #side nav{flex:1;overflow:auto;padding:8px 0}
  #side a{display:block;padding:10px 16px;color:var(--fg);text-decoration:none;font-size:13.5px;
          border-left:3px solid transparent}
  #side a:hover{background:#161b22}
  #side a.on{background:#161b22;border-left-color:var(--acc);color:var(--acc);font-weight:600}
  #side .foot{padding:10px 14px;border-top:1px solid var(--line);font-size:12px;color:var(--dim)}
  #main{flex:1;min-width:0;display:flex;flex-direction:column}
  #main header{margin:0}
  #pageBody{padding:18px 22px 60px;flex:1}
  .page{display:none}
  .page.on{display:block}
  .kv{display:grid;grid-template-columns:138px 1fr;gap:6px 12px;font-size:13px;margin:8px 0}
  .kv b{color:var(--dim);font-weight:400}
  .tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(225px,1fr));gap:12px;margin:12px 0}
  .tile{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:14px;
        cursor:pointer;text-decoration:none;color:var(--fg);display:block}
  .tile:hover{border-color:var(--acc);background:#1c2430}
  .tile .t{font-weight:600;font-size:14px;margin-bottom:4px}
  .tile .s{color:var(--dim);font-size:12px;line-height:1.55}
  .box{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:14px 16px;
       font-size:13px;line-height:1.85}
  @media (max-width:760px){
    body{display:block}
    #side{width:auto;flex:none;height:auto;position:static;border-right:0;border-bottom:1px solid var(--line)}
    #side nav{display:flex;overflow-x:auto;padding:0}
    #side a{white-space:nowrap;border-left:0;border-bottom:3px solid transparent}
    #side a.on{border-left:0;border-bottom-color:var(--acc)}
  }
</style>"""

# ---- 3. 主页文案 ----
HOME = """<h2>\U0001F3E0 主页</h2>
<div class="box">
  <div style="font-size:15px;font-weight:600;margin-bottom:8px">\U0001F3AF 触摸注入板 · 管理后台</div>
  <div class="kv">
    <b>后台版本</b><span>__VER__ (__BUILT__)</span>
    <b>服务运行</b><span id="homeUptime">—</span>
    <b>当前时间</b><span id="homeTime">—</span>
    <b>当前账号</b><span id="homeWho">—</span>
    <b>板子 / 在线</b><span id="homeBoards">—</span>
    <b>手机</b><span id="homePhones">—</span>
  </div>
</div>

<h3 style="margin:20px 0 6px;font-size:14px">\U0001F4D6 这是什么</h3>
<div class="box">
一套「<b>手机 + 触摸注入板</b>」的自动化系统。板子以 <b>HID 触摸屏</b>的身份接入手机
(蓝牙或 USB),App 按流程指挥板子往手机屏幕上注入点击、滑动。
<br><br>
<b>三条链路要分清:</b><br>
&nbsp;&nbsp;· <b>HID 链路</b>(注入触摸)&nbsp;&nbsp;板子 → 手机,由刷 <code>ble</code> 还是 <code>usb</code> 固件决定<br>
&nbsp;&nbsp;· <b>控制链路</b>(App 读写板子)&nbsp;&nbsp;App ↔ 板子,走 <b>WiFi</b> 或 <b>蓝牙控制通道</b><br>
&nbsp;&nbsp;· <b>上报链路</b>(云端同步)&nbsp;&nbsp;板子和 App → 这台服务器
</div>

<h3 style="margin:20px 0 6px;font-size:14px">\U0001F9ED 各页面入口</h3>
<div class="tiles">
  <a class="tile" href="#control"><div class="t">\U0001F39B 控制中心</div><div class="s">板子列表、在线状态、流程、策略,以及<b>点亮 LED 对号</b></div></a>
  <a class="tile" href="#users"><div class="t">\U0001F464 用户管理</div><div class="s">后台账号,以及已上报的手机</div></a>
  <a class="tile" href="#apps"><div class="t">\U0001F4F1 APP 管理</div><div class="s">APK 版本列表与上传</div></a>
  <a class="tile" href="#firmware"><div class="t">\U0001F4BE 固件管理</div><div class="s">OTA 固件列表与上传</div></a>
  <a class="tile" href="#logs"><div class="t">\U0001F4CB 日志系统</div><div class="s">操作审计:谁在什么时候改了什么</div></a>
  <a class="tile" href="#stats"><div class="t">\U0001F4CA 数据统计</div><div class="s">成功统计 + 事件明细</div></a>
  <a class="tile" href="/download/" target="_blank"><div class="t">\u2B07\uFE0F 固件下载页</div><div class="s">给要烧录的新板子(免登录)</div></a>
</div>

<h3 style="margin:20px 0 6px;font-size:14px">\u2139\uFE0F 接口速查</h3>
""" + secs.get('说明', '')

CARDS = ('<div class="cards" style="margin-bottom:14px">'
         '<div class="card"><div class="n" id="cBoards">-</div><div class="l">板子总数</div></div>'
         '<div class="card"><div class="n" id="cOnline" style="color:var(--ok)">-</div><div class="l">在线</div></div>'
         '<div class="card"><div class="n" id="cPhones">-</div><div class="l">手机</div></div>'
         '<div class="card"><div class="n" id="cFw">-</div><div class="l">固件版本</div></div>'
         '<div class="card"><div class="n" id="cApk">-</div><div class="l">APK 版本</div></div>'
         '</div>')

PAGES = {
    'home':     HOME,
    'control':  CARDS + secs.get('板子', ''),
    'users':    secs.get('账号管理', '') + secs.get('手机', ''),
    'apps':     secs.get('APK', ''),
    'firmware': secs.get('固件', ''),
    'logs':     secs.get('操作审计', ''),
    'stats':    stats_html + events_html,
}
NAV = [('home', '\U0001F3E0', '主页'), ('control', '\U0001F39B', '控制中心'),
       ('users', '\U0001F464', '用户管理'), ('apps', '\U0001F4F1', 'APP 管理'),
       ('firmware', '\U0001F4BE', '固件管理'), ('logs', '\U0001F4CB', '日志系统'),
       ('stats', '\U0001F4CA', '数据统计')]

navhtml = '\n'.join('      <a href="#%s" data-page="%s">%s %s</a>' % (k, k, i, n)
                    for k, i, n in NAV)

body = """<body>
<div id="side">
  <div class="brand">\U0001F3AF 触摸注入板<small>管理后台 v__VER__</small></div>
  <nav>
%s
  </nav>
  <div class="foot">
    <div id="whoami" style="margin-bottom:8px;word-break:break-all"></div>
    <button onclick="loadAll()" style="width:100%%;margin-bottom:6px">\U0001F504 刷新当前页</button>
    <button onclick="location.href='/admin/logout'" style="width:100%%">退出</button>
  </div>
</div>

<div id="main">
  <header>
    <h1 id="pageTitle">主页</h1>
    <span id="clock" class="muted"></span>
    <span style="flex:1"></span>
    <a href="/download/" target="_blank" class="muted" style="text-decoration:none">\u2B07\uFE0F 下载页</a>
  </header>
  <div id="errBar" style="display:none;background:rgba(248,81,73,.15);
       border-bottom:1px solid rgba(248,81,73,.4);color:#f85149;
       padding:10px 20px;font-size:13px"></div>
  <div id="pageBody">
""" % navhtml

for k, _i, _n in NAV:
    body += '    <div class="page" id="page-%s">\n%s\n    </div>\n' % (k, PAGES[k])
body += "  </div>\n</div>\n"

# ---- 4. JS:原样保留 + 加路由 ----
js = old[old.index('<script>'):]

ROUTER = r"""
// ================================================================ 左侧菜单路由
//
// 用 hash 路由:单文件后台,一个 hash 就能切页,不用引路由库、也不刷页面。
const PAGES_TITLE = {home:'主页', control:'控制中心', users:'用户管理', apps:'APP 管理',
                     firmware:'固件管理', logs:'日志系统', stats:'数据统计'};
let curPage = 'home';

function goto(page, push) {
  if (!PAGES_TITLE[page]) page = 'home';
  curPage = page;
  document.querySelectorAll('.page').forEach(el =>
    el.classList.toggle('on', el.id === 'page-' + page));
  document.querySelectorAll('#side nav a').forEach(a =>
    a.classList.toggle('on', a.dataset.page === page));
  const t = document.getElementById('pageTitle');
  if (t) t.textContent = PAGES_TITLE[page];
  if (push !== false && location.hash !== '#' + page) location.hash = page;
  loadPage(page);
}

/** 只加载**当前页**要用的数据 —— 别一次拉全部 */
function loadPage(page) {
  const jobs = [];
  if (page === 'control')  jobs.push(loadBoards(), loadSummary());
  if (page === 'users')    jobs.push(loadUsers(), loadPhones());
  if (page === 'apps')     jobs.push(loadApk());
  if (page === 'firmware') jobs.push(loadFw());
  if (page === 'logs')     jobs.push(loadAudit());
  if (page === 'stats')    jobs.push(loadStats(), loadEvents());
  if (page === 'home')     jobs.push(loadHome());
  return Promise.all(jobs.map(p => Promise.resolve(p).catch(e => console.error(e))));
}

/** 主页上的几个小信息 */
function loadHome() {
  const up = document.getElementById('homeUptime');
  if (up && window.__BOOT_AT) {
    const s = Math.floor((Date.now() - window.__BOOT_AT) / 1000);
    up.textContent = s < 3600 ? Math.floor(s / 60) + ' 分钟'
                   : (s < 86400 ? (s / 3600).toFixed(1) + ' 小时'
                                : (s / 86400).toFixed(1) + ' 天');
  }
  const t = document.getElementById('homeTime');
  if (t) t.textContent = new Date().toLocaleString('zh-CN');
  const w = document.getElementById('homeWho');
  if (w && ME) w.textContent = (ME.display_name || ME.username) + '(' + ME.role + ')';
  return loadSummary().then(() => {
    const b = document.getElementById('homeBoards');
    if (b) {
      const tot = document.getElementById('cBoards').textContent;
      const on = document.getElementById('cOnline').textContent;
      b.textContent = tot + ' 块 / 在线 ' + on + ' 块';
    }
    const p = document.getElementById('homePhones');
    if (p) p.textContent = document.getElementById('cPhones').textContent + ' 台';
  });
}

window.addEventListener('hashchange', () => goto(location.hash.slice(1), false));
"""

js = js.replace('''    async function loadAll() {
      try {
        await Promise.all([loadSummary(), loadBoards(), loadPhones(), loadFw(), loadApk(),
                           loadUsers(), loadAudit()]);
        clearErr();
      } catch (e) {
        console.error(e);      // 具体哪个接口出错,api() 里已经弹横幅了
      }
    }''', '''    /** 刷新**当前页**(不再一次拉全部 —— 页面多了之后没必要) */
    async function loadAll() {
      try {
        await loadPage(curPage);
        clearErr();
      } catch (e) {
        console.error(e);
      }
    }''')

js = js.replace('''    tick(); setInterval(tick, 1000);
    loadAll();
    setInterval(loadAll, 10000);''', '''    tick(); setInterval(tick, 1000);
    goto(location.hash.slice(1) || 'home', false);
    setInterval(loadAll, 10000);''')

js = js.replace('<script>', '<script>\nwindow.__BOOT_AT = __BOOT_AT__;\n' + ROUTER, 1)

new = ('<!DOCTYPE html>\n<html lang="zh-CN">\n<head>\n<meta charset="utf-8">\n'
       '<meta name="viewport" content="width=device-width,initial-scale=1">\n'
       '<title>触摸注入板 · 管理后台</title>\n' + sidebar_css + '\n</head>\n'
       + body + '\n' + js + '\n')

open(SRC, 'w', encoding='utf-8').write(new)
print('  写入完成:%d 字符' % len(new))
print('  页面:%s' % ', '.join(n for _k, _i, n in NAV))
