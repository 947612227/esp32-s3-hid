# 看板显示不对 + 公网 IP / 地理位置 + APK 自动上传

## 你反馈的

> 服务器上的流程队列显示的不对,我上次执行的是 大麦通用版本-智能等待,
> 然后你调试的时候又执行了其他脚本,现在服务器后台显示的还是
> 「下次:大麦 王源 10月11_19:00 10-05 19:30 (已过期)」,
> 最后心跳记录了 NaN 天前,这个我没见过板子离线,或者说板子即使离线了,
> 也是显示在线状态,另外需要记住,apk 改完之后安装到手机,手机上验证没问题了
> 就直接上传到服务器上,如果可以我还想获取板子所处网络的公网 ip 地址以及地理位置信息

**四个 bug,全部修掉了。**

---

# Bug ① 「下次」显示的是**已过期**的旧任务

```sql
ORDER BY fire_at ASC LIMIT 1
```

**取的是"最早"那个** —— 于是一个几天前的过期任务会**永远**占着"下次"的位置。
你实际要跑的是「大麦通用版本-智能等待」,面板却显示「大麦 王源 10月11_19:00」✅

## 修法:优先级改成

1. **未来的最近一个**(`fire_at >= NOW()`)
2. 没有未来的 → **最近更新过的那个**(至少不是最老的)

```sql
-- ① 未来最近
... WHERE board_mac = ? AND mode='at' AND fire_at >= NOW()
    ORDER BY fire_at ASC LIMIT 1
-- ② 兜底:最近改过的
... WHERE board_mac = ? AND fire_at IS NOT NULL
    ORDER BY updated_at DESC, fire_at DESC LIMIT 1
```

**修后**:`下次任务 = 大麦通用版本-智能等待` ✅ 正确了

---

# Bug ② `NaN 天前` —— **传了 Date 对象**

```js
lastSeen: b.last_seen        // ← mysql2 返回的是 Date 对象
```

`JSON.stringify(Date)` → **字符串** `"2026-10-06T05:00:00.000Z"`
前端 `ago(ms)` 做 `Date.now() - ms` → **字符串减法 = NaN** → `"NaN 天前"` ✅

## 修法:服务端统一转时间戳

```js
firstSeen: b.first_seen ? new Date(b.first_seen).getTime() : 0,
lastSeen:  b.last_seen  ? new Date(b.last_seen).getTime()  : 0,
```

**修后**:`lastSeen=1791262223000`(类型 `int`)✅ 显示正常

> **教训**:接口给前端的**时间一律给毫秒时间戳**,
> 不要给 Date 对象或日期字符串 —— 前端算差值时最容易踩这个坑。

---

# Bug ③ 板子离线了,看板还显示**在线**

## 根因:判定窗口比心跳间隔还大

```js
const ONLINE_MS = 90 * 60 * 1000;      // 服务端:90 分钟内算在线
#define HEARTBEAT_MS (60*60*1000)      // 板子:60 分钟才发一次心跳
```

**90 > 60** ⇒ 心跳还没过期,下一次又来了 ⇒ **拔电也永远"在线"** ✅

## 修法:把两个数一起改对

| | 改前 | 改后 |
|---|---|---|
| 板子心跳间隔 | **60 分钟** | **2 分钟** |
| 服务端在线窗口 | **90 分钟** | **5 分钟**(容忍丢 2 次) |

**原则:在线判定窗口必须显著小于心跳间隔。**
一般取 `心跳间隔 × 2.5` 左右。

**修后实测**:

```
把 last_seen 挪回 10 分钟前 → 在线 = False   ✅ 正确显示离线
板子正常心跳              → 在线 = True    ✅
```

> 附带好处:板子挂了**最多 5 分钟**就能发现(以前要 1.5 小时)。

---

# 新功能 ④ 板子所在网络的**公网 IP + 地理位置**

## 为什么不能让板子自己报

板子在**内网**(`192.168.5.37`),这个地址没有定位意义。
**服务端能从心跳的源地址看到它所在网络的公网 IP** —— 那才是能定位的。

## 实现

```js
const pubIp = clientIp(req).replace(/^::ffff:/, '');
if (pubIp && !isPrivateIp(pubIp)) {
  await db.run('UPDATE boards SET pub_ip = ? WHERE mac = ?', [pubIp, mac]);
  // 定位要发网络请求,**不阻塞心跳响应** —— 慢就慢,别让板子等
  geoLookup(pubIp).then(g => { ...写库... });
}
```

用 `ip-api.com`(免费、支持中文),但**必须缓存**:

```js
const gedCache = new Map();          // 同一个 IP 24 小时只查一次
const GEO_TTL_MS = 24 * 3600 * 1000;
```

> 限速 45 次/分钟,而心跳 2 分钟一次 —— 不缓存迟早被限速。

## ⚠️ 踩了个坑:Node 16 没有 `fetch`

第一版用 `fetch(...)`,结果是空的。日志说得很清楚:

```
[geo] 查询失败 117.133.86.219 fetch is not defined
```

`fetch` 是 **Node 18** 才有的全局函数,服务器是 **Node v16**。
改成原生 `http.get` 就好了。

## 实测结果

```
在线      🟢 在线
最后心跳  10-06 13:03:10
内网 IP   192.168.5.37
公网 IP   117.133.86.219
位置      中国 北京市 北京  ·  China Mobile communications corporation
坐标      39.911, 116.395
```

看板上显示为:

```
🌐 117.133.86.219
📍 中国 北京市 北京
   China Mobile communications corporation
```

> **精度说明**:定位到**城市**级别。板子在路由器后面,
> 公网 IP 是整个出口的 —— 精确到楼是不可能的。

---

# 新约定 ⑤ APK 改完 → 验证 → **上传服务器**

## 以后的做法

```bash
# 1. 改代码
# 2. 编译 + 装到手机
cd android-app && ./build.sh && adb install -r build/触摸注入板.apk
# 3. 手机上验证没问题
# 4. 升版本号(改 AndroidManifest 的 versionCode / versionName)
# 5. 上传
./tools/upload-apk.sh -n "这次改了什么"
```

## 脚本做了什么

| 步骤 | 说明 |
|---|---|
| 读版本号 | 从 `AndroidManifest.xml` 读(唯一真相来源) |
| 登录 | 用 admin 账号拿 `tp_sess` 会话 |
| 对比 | 显示服务器上现在是什么版本 |
| 上传 | `POST /api/admin/apk` 带文件 + 版本号 + 更新说明 |
| **校验** | **如果 versionCode 没大于服务器上的,会警告"App 会认为已是最新"** |

只检查不上传:

```bash
./tools/upload-apk.sh -c
```

## 为什么必须有这一步

服务器上原来的 APK 是 **10-05** 的,而之后改了很多东西 ——
**版本号碰巧都是 3**,所以 App 的「检查更新」一直说"已是最新" ✅

**现在**:每次上传前先升 `versionCode`,否则脚本会警告。

本次已执行:

```
本地版本: versionCode=4  versionName=2.2
服务器上: 3|2.1|2026-10-05T10:23:13
✅ 已上传,App 里「检查更新」现在能看到 2.2 (4)
```

---

# 改动的文件

| 文件 | 改了什么 |
|---|---|
| `server/index.js` | `ONLINE_MS` 5 分钟;`geoLookup()`(http.get + 缓存);心跳记 `pub_ip` + 定位;`/api/admin/boards` 的 next 查询 + lastSeen 时间戳 + pubIp/geo |
| `server/public/index.html` | 表格加「网络 / 位置」列;详情弹窗加公网 IP + 地理位置 |
| `secrets.h` | `HEARTBEAT_MS` 60 分钟 → **2 分钟** |
| `android-app/.../AndroidManifest.xml` | `versionCode` 3 → **4**,`versionName` 2.1 → **2.2** |
| `tools/upload-apk.sh` | **新增** —— APK 上传脚本 |
| 数据库 `boards` 表 | 新增 `pub_ip` / `geo_country` / `geo_region` / `geo_city` / `geo_isp` / `geo_lat` / `geo_lon` / `geo_at` |

---

# 教训

## 1. 两个相关的常量必须**一起看**

`ONLINE_MS` 和 `HEARTBEAT_MS` 是一对。
单看哪个都"合理",放一起就是 bug。
**凡是"超时/间隔"类常量,注释里写清它和谁配合。**

## 2. 接口给前端的时间**一律给毫秒时间戳**

Date 对象 → JSON → 字符串 → 前端减法 = NaN。
这类 bug 只在渲染时才暴露,而且显示成 `NaN` 很显眼但难定位。

## 3. 注意运行环境的版本差异

`fetch` / `AbortController` / 可选链……都是新版才有的。
**服务器是 Node 16,写代码前先确认。**

## 4. 版本号不升,发布就是假的

服务器上的 APK 是旧的,但版本号一样 ⇒ App 认为"已是最新"。
**上传脚本专门做了这个校验并警告。**
