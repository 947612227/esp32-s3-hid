#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
targets.py —— 把"要点哪里"翻译成屏幕坐标

无板方案没有 HID,坐标只能来自:
  1. UI 树(uiautomator dump)—— 有文字/资源 id 的控件可以直接按名字找
  2. 截图 + 人工/程序标定 —— 纯自绘界面(UI 树为空时)只能靠坐标或图像

这里实现第 1 条,并提供第 2 条所需的坐标解析工具。
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from typing import Iterable, Optional

_BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


@dataclass
class UINode:
    """UI 树里的一个节点(只保留我们关心的属性)。"""

    text: str = ""
    resource_id: str = ""
    class_name: str = ""
    package: str = ""
    content_desc: str = ""
    clickable: bool = False
    enabled: bool = True
    visible: bool = True
    bounds: tuple[int, int, int, int] = (0, 0, 0, 0)   # left, top, right, bottom
    children: list["UINode"] = field(default_factory=list)
    parent: Optional["UINode"] = None

    # ---------------------------------------------------------------- 几何
    @property
    def width(self) -> int:
        return self.bounds[2] - self.bounds[0]

    @property
    def height(self) -> int:
        return self.bounds[3] - self.bounds[1]

    @property
    def center(self) -> tuple[int, int]:
        l, t, r, b = self.bounds
        return ((l + r) // 2, (t + b) // 2)

    @property
    def area(self) -> int:
        return max(0, self.width) * max(0, self.height)

    @property
    def label(self) -> str:
        """用于展示的短标签。"""
        if self.text:
            return self.text
        if self.content_desc:
            return f"[desc]{self.content_desc}"
        if self.resource_id:
            return f"[id]{self.resource_id.split('/')[-1]}"
        return f"[{self.class_name.split('.')[-1]}]"

    def __str__(self) -> str:
        flags = []
        if self.clickable:
            flags.append("clickable")
        if not self.enabled:
            flags.append("disabled")
        suffix = (" " + ",".join(flags)) if flags else ""
        return f"{self.center} {self.bounds} {self.label}{suffix}"


# ---------------------------------------------------------------------------
#  解析
# ---------------------------------------------------------------------------
def parse_ui(xml_text: str) -> Optional[UINode]:
    """解析 uiautomator dump 的 XML,返回根节点。"""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return None

    def build(el: ET.Element, parent: Optional[UINode]) -> UINode:
        m = _BOUNDS_RE.match(el.get("bounds", "") or "")
        bounds = (int(m.group(1)), int(m.group(2)),
                  int(m.group(3)), int(m.group(4))) if m else (0, 0, 0, 0)
        node = UINode(
            text=el.get("text", "") or "",
            resource_id=el.get("resource-id", "") or "",
            class_name=el.get("class", "") or "",
            package=el.get("package", "") or "",
            content_desc=el.get("content-desc", "") or "",
            clickable=(el.get("clickable") == "true"),
            enabled=(el.get("enabled", "true") == "true"),
            visible=(el.get("visible-to-user", "true") != "false"),
            bounds=bounds,
            parent=parent,
        )
        for child in el:
            node.children.append(build(child, node))
        return node

    return build(root, None)


def walk(node: Optional[UINode]) -> Iterable[UINode]:
    """深度优先遍历。"""
    if node is None:
        return
    yield node
    for c in node.children:
        yield from walk(c)


def find_all(node: Optional[UINode], *, text: Optional[str] = None,
             contains: Optional[str] = None, resource_id: Optional[str] = None,
             class_name: Optional[str] = None, clickable_only: bool = False,
             visible_only: bool = True) -> list[UINode]:
    """按条件搜索节点。"""
    out: list[UINode] = []
    for n in walk(node):
        if visible_only and not n.visible:
            continue
        if clickable_only and not n.clickable:
            continue
        if text is not None and n.text != text:
            continue
        if contains is not None and contains not in (n.text or ""):
            continue
        if resource_id is not None and not n.resource_id.endswith(resource_id):
            continue
        if class_name is not None and n.class_name != class_name:
            continue
        out.append(n)
    return out


def clickable_ancestor(node: Optional[UINode]) -> Optional[UINode]:
    """向上找最近的可点击祖先(文字节点本身通常不可点)。"""
    cur = node
    while cur is not None:
        if cur.clickable and cur.enabled and cur.area > 0:
            return cur
        cur = cur.parent
    return None


def resolve(node: Optional[UINode], span: str) -> Optional[UINode]:
    """把用户给的目标描述解析成一个可点击节点。

    支持三种写法:
        "确认并知悉"                 → 文本精确匹配
        "text:确认"                  → 文本包含匹配
        "id:btn_confirm"             → resource-id 后缀匹配
    """
    span = span.strip()
    if not span:
        return None

    if span.startswith("id:"):
        pat = span[3:]
        cands = find_all(node, resource_id=pat)
    elif span.startswith("text:"):
        pat = span[5:]
        cands = find_all(node, contains=pat)
    elif span.startswith("desc:"):
        pat = span[5:]
        cands = [n for n in walk(node) if n.visible and pat in (n.content_desc or "")]
    else:
        cands = find_all(node, text=span)
        if not cands:
            # 精确匹配不到就退化为包含匹配
            cands = find_all(node, contains=span)

    # 优先取"本身可点"的,其次取"有可点击祖先"的,最后按面积从小到大(更精确)
    def rank(n: UINode) -> tuple[int, int]:
        self_clickable = 0 if (n.clickable and n.enabled) else 1
        return (self_clickable, n.area)

    for n in sorted(cands, key=rank):
        if n.clickable and n.enabled and n.area > 0:
            return n
    for n in sorted(cands, key=rank):
        anc = clickable_ancestor(n)
        if anc is not None:
            return anc
    # 实在没有可点的,返回第一个可见候选(调用方可以强行点它中心)
    return cands[0] if cands else None


# ---------------------------------------------------------------------------
#  坐标解析
# ---------------------------------------------------------------------------
def parse_coord(spec: str, width: int, height: int) -> tuple[int, int]:
    """把坐标描述解析成像素坐标。

    支持:
        "540,1150"    → 绝对像素
        "50%,50%"     → 百分比
        "50,50%"      → 混合
        "center" / "中心"
        "540,1150" 之外的非法输入会抛 ValueError
    """
    s = spec.strip().lower()
    if s in ("center", "centre", "中心", "c"):
        return width // 2, height // 2

    parts = [p for p in re.split(r"[,\s]+", spec.strip()) if p]
    if len(parts) != 2:
        raise ValueError(f"坐标格式应为 'x,y',收到:{spec!r}")

    def one(p: str, total: int) -> int:
        p = p.strip()
        if p.endswith("%"):
            v = float(p[:-1]) / 100.0
            return int(round(v * total))
        return int(round(float(p)))

    return one(parts[0], width), one(parts[1], height)
