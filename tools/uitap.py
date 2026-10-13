#!/usr/bin/env python3
"""按文字点击手机上的元素(自动 dump + 解析 + tap)。

为什么需要:之前用正则解析 uiautomator 的 XML 经常出错(节点嵌套、属性顺序),
导致点击落到错的地方、或静默失败被误判。改用真正的 XML 解析器。

用法:
  python3 tools/uitap.py 流程              # 点文字为「流程」的节点
  python3 tools/uitap.py 流程 --dump       # 只列出匹配节点不点击
  python3 tools/uitap.py "运行" --nth 2    # 点第 2 个匹配
  python3 tools/uitap.py --texts          # 列出当前屏幕所有文字
"""
import re, subprocess, sys, tempfile, os
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", os.path.expanduser("~/app/android_sdk/platform-tools/adb"))

def dump():
    """注意:uiautomator dump 有时会返回**缓存**的旧内容(实测踩过,
    表现是"时钟不走/页签切不动",其实界面是正常的)。
    所以先删旧文件,拿到有效内容才算成功。"""
    import time
    for attempt in range(4):
        subprocess.run([ADB, "shell", "rm", "-f", "/sdcard/_ui.xml"],
                       capture_output=True)
        subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/_ui.xml"],
                       capture_output=True)
        p = tempfile.mktemp(suffix=".xml")
        subprocess.run([ADB, "pull", "/sdcard/_ui.xml", p], capture_output=True)
        if os.path.exists(p) and os.path.getsize(p) > 100:
            try:
                ET.parse(p)
                return p
            except Exception:
                pass
        time.sleep(0.8)
    raise SystemExit("✗ 拿不到有效的界面布局(检查手机是否连着、屏幕是否亮着)")


def center(bounds):
    n = [int(x) for x in re.findall(r'-?\d+', bounds or "")]
    if len(n) != 4: return None
    return ((n[0]+n[2])//2, (n[1]+n[3])//2)

def find(node, text, out, clickable_ancestor=None):
    """找到文字匹配的节点。同时记录「最近的可点祖先」——
    很多界面的可点区域是外层卡片,文字本身不可点(实测踩过)。"""
    a = node.attrib
    ck = a.get('clickable') == 'true'
    me = center(a.get('bounds'))
    anc = (me, a.get('text', '')) if (ck and me) else clickable_ancestor
    t = a.get('text') or ''
    if text in t:
        c = center(a.get('bounds'))
        if c:
            out.append((t, c, a.get('clickable'), anc))
    for ch in node:
        find(ch, text, out, anc)


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__); return
    target = args[0]
    nth = 1
    if "--nth" in args:
        nth = int(args[args.index("--nth")+1])
    only = "--dump" in args

    p = dump()
    root = ET.parse(p).getroot()

    if target == "--texts":
        seen = []
        def walk(n):
            t = n.attrib.get('text','').strip()
            if t and t not in seen: seen.append(t)
            for c in n: walk(c)
        walk(root)
        for t in seen: print(f"  {t[:60]}")
        return

    hits = []
    find(root, target, hits)
    if not hits:
        print("找不到: " + target); return
    for i, (t, c, ck, anc) in enumerate(hits):
        extra = ("  可点祖先=" + str(anc[0])) if (ck != 'true' and anc) else ""
        print("  [" + str(i+1) + "] " + repr(t) + " 中心=" + str(c) + " clickable=" + str(ck) + extra)
    if only: return
    if nth > len(hits):
        print("匹配数不足: " + str(len(hits))); return

    t, c, ck, anc = hits[nth-1]
    # 文字本身不可点 → 点它的可点祖先
    x, y = c if ck == 'true' else (anc[0] if anc else c)
    subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)])
    print("已点击 " + repr(t) + " @ (" + str(x) + "," + str(y) + ")"
          + ("" if ck == 'true' else "  (通过可点祖先)"))


if __name__ == '__main__':
    main()
