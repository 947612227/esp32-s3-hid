/**
 * 路由表导出(验证"拆路由"没改变任何接口):
 *   node -r <本文件> index.js > routes.txt
 *
 * 拦住 app.listen():不真正监听,而是把路由表**按匹配顺序**展开打印后退出。
 * 子 Router 递归展开,所以"拆成多个 Router 文件"前后打印结果应当逐行一致。
 * 每行:类型 方法 路径 中间件/处理函数名(匿名函数记为 <匿名>)
 */
const path = require('path');
const express = require(require.resolve('express', { paths: [process.cwd()] }));

// 层的挂载路径:Router 内的路由用 route.path;app.use('/x', ...) 从正则里还原
function mountPath(layer) {
  if (layer.route) return layer.route.path;
  const src = layer.regexp && layer.regexp.source;
  if (!src || layer.regexp.fast_slash) return '/';
  return src.replace(/^\^/, '').replace(/\\\/\?\(\?=\\\/\|\$\)$/i, '')
            .replace(/\\\//g, '/');
}

function walk(stack, prefix, out) {
  for (const layer of stack) {
    const p = mountPath(layer);
    const full = (prefix + (p === '/' ? '' : p)) || '/';
    if (layer.route) {
      const methods = Object.keys(layer.route.methods).map(m => m.toUpperCase()).join(',');
      const names = layer.route.stack.map(l => l.name || '<匿名>').join(' → ');
      out.push(`路由 ${methods} ${layer.route.path === '/' ? full : prefix + layer.route.path} ${names}`);
    } else if (layer.name === 'router' && layer.handle && layer.handle.stack) {
      walk(layer.handle.stack, prefix + (p === '/' ? '' : p), out);   // 子 Router 展开,不单独记
    } else {
      out.push(`中间件 ${full} ${layer.name || '<匿名>'}`);
    }
  }
}

express.application.listen = function () {
  const out = [];
  walk(this._router.stack, '', out);
  process.stdout.write(out.join('\n') + '\n');
  process.exit(0);
};
