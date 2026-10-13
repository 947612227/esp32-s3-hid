# 后台前端:从原生 JS 重构成 Vue 3

## 一、为什么用 CDN 版而不是 vite + SFC

| 方案 | 问题 |
|---|---|
| **vite + SFC** | 服务器是 **CentOS 7 + Node 16**,vite 5 要 Node 18+ ⇒ 被卡住;<br>而且要再养一条构建链,后台只有内部几个人用 |
| **Vue 3 CDN 版(选了)** | `vue.global.prod.js` 154KB,**托管在 `/admin/static/vendor/` 下**,不依赖外网 CDN ✅<br>模板直接写在 HTML 里,Vue 运行时编译器负责编译 ✅ |

**⚠️ 关键**:一定要**下载到本地托管**,不要写 `cdn.jsdelivr.net` ✅
生产服务器不一定能访问外网 CDN,那天页面就整个白屏 ✅

下载源用国内可达的(实测 `registry.npmmirror.com` 秒下 ✅):
```bash
curl -s -o server/public/vendor/vue.global.prod.js \
  https://registry.npmmirror.com/vue/3.5.13/files/dist/vue.global.prod.js
```

---

## 二、文件结构

```
server/public/
  ├── index.html                    模板 + CSS(8 个页面都在这)
  ├── app.js                        Vue 应用(setup + 数据 + 请求)
  ├── vendor/vue.global.prod.js     Vue 3.5.13(本地托管)
  └── index.vanilla.bak.html        重构前的原版(留个底)
```

**⚠️ 静态文件挂在 `/admin/static` 不是 `/`** ✅
（见 `server/index.js` 的 `app.use('/admin/static', express.static(PUBLIC_DIR))`）
所以引用要写:
```html
<script src="/admin/static/vendor/vue.global.prod.js"></script>
<script src="/admin/static/app.js"></script>
```
写成 `/app.js` 会 **404** ✅(我就是这么踩的 ✅)

---

## 三、结构

```
index.html  →  <div id="app">  模板(侧边栏 + 8 个 <section v-show>)
app.js      →  createApp({ setup() { ... } }).mount('#app')
```

**8 个页面**(用 `v-show` 切换,不是路由跳转 —— 切换更快、也不用重拉数据):

| 页面 | hash | 说明 |
|---|---|---|
| 🏠 主页 | `#home` | 版本 / 运行时长 / 统计 + 功能说明 + 各页入口 |
| 🎛 控制中心 | `#control` | 5 个统计卡 + 板子表 + 💡灯 / 详情 / 流程 / 策略 |
| 📱 **设备管理** | `#devices` | **从用户管理拆出来的**(手机信息) |
| 👤 用户管理 | `#users` | 只管后台账号 |
| 📦 APP 管理 | `#apps` | APK 列表 + 上传 |
| 💾 固件管理 | `#firmware` | OTA 固件 + 上传 + 删除 |
| 📋 日志系统 | `#logs` | 操作审计 + **分页(每页 50)** |
| 📊 数据统计 | `#stats` | 成功统计 + 事件明细 + **分页** |

**⚠️ `go()` 在 `setup()` 里,没暴露到 window** ✅
所以**不能用控制台调 `go('control')` 来测** ✅ —— 要改 `location.hash` ✅
（我第一版测试脚本就掉这个坑 ✅:八个页面全报"主页",因为调用静默失败了 ✅）

---

## 四、⚠️ 踩到的两个大坑

### 坑 ① flex 写在 `body` 上,主内容区整个跑到屏幕外

**现象**:侧边栏渲染正常 ✅ 但右侧**一片空白** ✅
而 DOM 里内容都在 ✅(只测 DOM 的脚本查得到"控制中心 / 2 行" ✅)—— **所以骗过了自动化测试** ✅

**根因**:
```css
/* 错的 */
body{display:flex}
/* 但 #side / #main 是 #app 的子元素,body 的直接子元素只有 #app 一个 */
```
⇒ flex 布局**完全不生效** ⇒ `#side{height:100vh}` 独占第一屏 ⇒ `#main` 被挤到**下面** ✅

**修**:
```css
#app{display:flex;min-height:100vh}
```

> **教训:DOM 断言通过 ≠ 页面看起来对。**
> 必须**截图看** ✅ —— 这个 bug 靠 `querySelector` 永远发现不了 ✅

### 坑 ② 一个 SQL 变量用在两个查询上,但只有一个有别名

见 `server/index.js` 的 `/api/admin/event-stats`:

```javascript
// 错的
const since = days > 0 ? `AND e.at >= DATE_SUB(NOW(), INTERVAL ${days} DAY)` : '';
//                        ↑ 写死了别名 e

FROM board_events e ... WHERE e.type='flow_event' ${since}   // ✅ 有别名 e,对
FROM board_events   WHERE type='flow_event' ${since}          // ❌ 没有别名!e.at 不存在
```

**一传 `?days=` 就报** `Unknown column 'e.at' in 'where clause'` ✅

**为什么一直没发现**:旧前端从来不传 `days` ⇒ `since` 是空串 ⇒ bug 潜伏 ✅
我新前端加了 `?days=30` 才踩出来 ✅

**修**:拆成两个变量 ✅
```javascript
const sinceGrp = days > 0 ? `AND e.at >= ...` : '';
const sinceSum = days > 0 ? `AND at  >= ...` : '';
```

**实测**:
```
（不带）   → ✅ 2 组 · 共 3 次
?days=30  → ✅ 2 组 · 共 3 次
?days=365 → ✅ 2 组 · 共 3 次
?days=7   → ✅ 1 组 · 共 1 次     ← 过滤真的生效了
```

> **教训:新前端会暴露老后端的潜伏 bug** —— 因为它会**用老前端没试过的参数组合** ✅

---

## 五、实测(无头 Edge + CDP 真点击)

```
Vue 挂上了吗: object | 应用挂载: true
✅ home      标题=主页       行数=0
✅ control   标题=控制中心    行数=2
✅ devices   标题=设备管理    行数=2       ← 新拆的页
✅ users     标题=用户管理    行数=2
✅ apps      标题=APP 管理   行数=14
✅ firmware  标题=固件管理    行数=1
✅ logs      标题=日志系统    行数=50      ← 分页正常
✅ stats     标题=数据统计    行数=5
JS 错误合计: 0
```

---

## 六、日常改前端要注意

1. **改了 `index.html` / `app.js` 要手动 scp 上去** ✅(没有热更新 ✅)
   ```bash
   scp server/public/index.html root@120.55.183.206:/root/touchpad-server/public/
   scp server/public/app.js     root@120.55.183.206:/root/touchpad-server/public/
   ssh root@120.55.183.206 'chmod 644 /root/touchpad-server/public/index.html /root/touchpad-server/public/app.js; systemctl restart touchpad-server'
   ```
   **⚠️ `chmod 644` 不能忘** —— scp 过去的文件是 `600`,express 读不到 ✅
2. **`/admin/` 已禁用缓存** ✅(`Cache-Control: no-store`)✅ 但还是建议 **Cmd+Shift+R** ✅
3. **写完一定要截图看** ✅ —— 见坑 ①
