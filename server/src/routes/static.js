/**
 * 公开静态资源:固件下载页、后台静态文件、图标、首页跳转
 */
const express = require('express');
const http = require('http');
const auth = require('../auth');
const { PUBLIC_DIR, DOWNLOAD_DIR, needLogin } = require('../common');

const router = express.Router();

// ---------------------------------------------------------------------------
//  固件下载页(公开,不用登录)
//
//  为什么公开:拿到一块新板子的人,第一件事就是烧固件 ——
//  这时候他还没账号、也不想先登录。固件本身不敏感。
//
//  内容由 tools/release-firmware.sh 上传:
//    download/touchpad-{ble,usb,usb_touchtest}-full.bin
//    download/flash-{windows.bat,mac-linux.sh}
//    download/README.txt / version.json
// ---------------------------------------------------------------------------
router.use('/download', express.static(DOWNLOAD_DIR, {
  setHeaders: (res, p) => {
    if (p.endsWith('.bin')) res.setHeader('Content-Type', 'application/octet-stream');
    res.setHeader('Cache-Control', 'no-store');   // 重新发布后要立刻能下到新的
  },
}));

router.use('/admin/static', express.static(PUBLIC_DIR));

// 旧的 /admin/api/* 前缀做个跳转兼容 —— 万一浏览器还跑着老页面,
// 也不会满屏 404(把 auth 情况一并带上)
router.use('/admin/api', needLogin, (req, res) => {
  res.set('Cache-Control', 'no-store');
  res.redirect(307, '/api/admin' + req.url);
});

// 图标:内联 SVG,顺手把浏览器的 /favicon.ico 404 也消掉
const FAVICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
<rect width="32" height="32" rx="7" fill="#1f6feb"/>
<circle cx="16" cy="16" r="6" fill="none" stroke="#fff" stroke-width="2.5"/>
<circle cx="16" cy="16" r="2" fill="#fff"/></svg>`;
const sendIcon = (req, res) => {
  res.type('image/svg+xml').set('Cache-Control', 'public, max-age=86400').send(FAVICON);
};
router.get('/favicon.ico', sendIcon);
router.get('/favicon.svg', sendIcon);

router.get('/', (req, res) => res.redirect('/admin/'));


module.exports = { router };
