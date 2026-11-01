#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校验 v1.01 素材包契约（只读，不修改任何文件）。

用法：
  python tools/check_pack_v101.py <modes.json 路径> [...]

检查项：
  1. JSON 合法（org.json 同级的严格解析）
  2. resources 之前的顶层字段齐全（11 项必备）
  3. (functions 叶子 ∪ menuPool ∪ standbyPool ∪ staticPool) ⊆ modes   —— 单向包含，允许冗余
  4. functions 中指向资源的叶子 ⊆ resources                            —— 单向包含
"""
import glob
import io
import json
import os
import sys

# v1.01 必备字段（必须位于 resources 之前）
REQUIRED_TOP = [
    "version", "id", "name", "shortName", "authors",
    "decodeTarget", "anchorRes", "menuPool", "standbyPool", "staticPool", "functions",
]

# functions 中指向 resources 的功能路径（叶子不是模式名而是资源键）
RESOURCE_PATHS = [("feed", "food")]


def walk_functions(node, path, mode_leaves, res_leaves):
    """递归遍历 functions；叶子（list）按路径归属收集。"""
    if isinstance(node, list):
        (res_leaves if tuple(path) in RESOURCE_PATHS else mode_leaves).extend(node)
        return
    if isinstance(node, dict):
        for k, v in node.items():
            walk_functions(v, path + [k], mode_leaves, res_leaves)


def check(path):
    with io.open(path, encoding="utf-8") as f:
        raw = f.read()
    problems, info = [], {}
    try:
        root = json.loads(raw, object_pairs_hook=lambda p: dict(p))
    except Exception as e:
        return ["JSON 解析失败: %s" % e], {}

    keys = list(root.keys())
    info["top_order"] = " -> ".join(keys[:12])

    # 1) resources 之前必备字段
    names = set(keys)
    missing = [k for k in REQUIRED_TOP if k not in names]
    if missing:
        problems.append("缺失必备字段: %s" % ", ".join(missing))
    info["version"] = root.get("version", "?")

    # 2) 位置判定：必备字段应在 resources 之前
    try:
        res_i = keys.index("resources")
    except ValueError:
        res_i = len(keys)
        problems.append("缺少 resources")

    # 3) 引用集
    mode_leaves, res_leaves = [], []
    walk_functions(root.get("functions", {}), [], mode_leaves, res_leaves)
    referenced = set(mode_leaves)
    for pool in ("menuPool", "standbyPool", "staticPool"):
        referenced |= set(root.get(pool, []))

    modes = set(root.get("modes", {}).keys())
    gone = sorted(referenced - modes)
    if gone:
        problems.append("modes 缺少被引用项: %s" % ", ".join(gone))

    resources = set(root.get("resources", {}).keys())
    res_gone = sorted(set(res_leaves) - resources)
    if res_gone:
        problems.append("resources 缺少被引用项: %s" % ", ".join(res_gone))

    # 4) 冗余（仅提示，不判错）
    extra = sorted(modes - referenced)
    info["modes_total"] = str(len(modes))
    info["modes_unused"] = ", ".join(extra) if extra else "(无)"
    info["resources"] = ", ".join(sorted(resources)) if resources else "(无)"
    return problems, info


def main():
    targets = sys.argv[1:]
    if not targets:
        # 不给参数时自动扫描 assets 下所有包。
        # 经 cmd 传中文路径会因控制台编码（GBK）被破坏，故不在命令行传路径。
        here = os.path.dirname(os.path.abspath(__file__))
        base = os.path.join(here, os.pardir, "app", "src", "main", "assets")
        targets = sorted(glob.glob(os.path.join(base, "*", "modes.json")))
        if not targets:
            print(__doc__)
            return 1
    bad = 0
    for p in targets:
        problems, info = check(p)
        print("=== %s ===" % p)
        for k, v in info.items():
            print("  %-14s %s" % (k, v))
        if problems:
            bad += 1
            for m in problems:
                print("  [FAIL] %s" % m)
        else:
            print("  [OK] v1.01 契约通过")
        print()
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
