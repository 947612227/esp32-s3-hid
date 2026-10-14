# 流程列表:缓存 + 手动刷新

## 用户反馈

> "进入流程后加载流程列表不是很快,而且每次切换到流程都会提示读取中,
>  大概需要 5 秒钟,而且流程列表是不是远程的流程,
>  流程加载一次即可,点击刷新列表重新请求并加载。"

## 先回答问题:流程列表**是远程的**

**流程存在「板子」上,不在 App 里**:
```
App ──蓝牙 /api/scripts──> 板子(流程正文存在板子的 Flash/LittleFS 里)
```
- 蓝牙**一次往返 2~3 秒**(实测)
- 老实现:**每次切到「流程」页都重新读一遍** ⇒ 每次都等 5 秒 + 显示"读取中…"

**⇒ 用户的判断是对的:没必要时时读** ✅

## 改法

| 场景 | 行为 |
|---|---|
| **第一次**进流程页 | 读一次(6.5 秒,显示"读取中…") |
| **切走再切回** | ⚡ **秒开**(直接用缓存,**不发请求**) |
| **点「刷新列表」** | 🔄 强制重新请求(显示"读取中…") |
| **存/删/复制流程之后** | 缓存**自动作废**,下次进来重读 |

### 实现

```java
private boolean scriptsLoaded = false;              // 缓存是否有效
public static volatile boolean scriptsDirty = false; // 别处改过流程

// 显示:有缓存就直接画,不发请求
private void loadScripts() {
    if (scriptListBox == null) return;
    if (scriptsDirty) { scriptsLoaded = false; scriptsDirty = false; }
    if (scriptsLoaded) { renderScripts(); return; }
    fetchScripts();
}

// 「刷新列表」按钮:强制拉
private void refreshScripts() {
    scriptsLoaded = false; scriptsDirty = false;
    lastScriptsSig = "";
    scriptListBox.removeAllViews();
    scriptListBox.addView(hint("读取中…"));
    fetchScripts();
}
```

### ⚠️ 为什么 `scriptsDirty` 是 **static**

流程**编辑页是另一个 Activity**(`FlowEditActivity`)✅ 拿不到 `MainActivity` 的实例 ✅
所以只能用一个**静态量**通知"流程改了" ✅
否则:用户在编辑页存了新流程 ✅ 回到主界面**还显示旧列表** ✅ 得手动点刷新 ✅

**置脏的时机**:
- `FlowEditActivity.doSave()` 保存成功 ✅
- 主界面「复制流程」成功 ✅

**`buildScriptsTab()` 里也要判断缓存** —— 否则切回来还是先显示"读取中…"再闪一下 ✅

## 实测(三星 SM-S918U1 / Android 16)

```
① 第一次进「流程」页 → 6.5 秒
   看到:云端下发的流程 / 向下滚动 / 回桌面 / 曲线滑动

② 切到「状态」再切回「流程」 → 1.2 秒后已显示列表(秒开)✅

③ 点「刷新列表」 → 0.35 秒后出现「读取中…」= 真的重新请求了 ✅
                → 读完列表回来 ✅
```

> **教训:从"远端(板子)"读的数据,只要不是频繁变化,就该缓存。**
> 蓝牙一次往返 2~3 秒,时长时读的代价太大 ✅
> 但**必须有明确的失效时机**(用户点刷新 / 自己改过数据),否则会看到过期内容 ✅
