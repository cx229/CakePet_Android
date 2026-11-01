#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
一次性脚本：删除 ImageModeManager.kt 中 if (!loaded) { ... } // end if(!loaded) 整块。

背景：v1.01 契约要求动作全部由素材包 modes.json 提供，代码不再写死任何动作名；
包内 modes.json 是必备字段（缺失即导入被拒），故 JSON 解析失败时不应再回退到
内建硬编码动作——那会让新角色（如蜂蜜猫）拿到芝麻酥的动作而串图/卡住。

用法：
  python tools/strip_builtin_modes.py
  python tools/strip_builtin_modes.py --check   # 只检查是否已处理，不改文件
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, os.pardir, "app", "src", "main", "java",
                      "com", "cx", "cakepet", "ImageModeManager.kt")
START_RE = re.compile(r"^\s*if \(!loaded\) \{\s*$")
END_RE = re.compile(r"^\s*\} // end if\(!loaded\)\s*$")


def main():
    path = os.path.normpath(TARGET)
    with io.open(path, encoding="utf-8") as f:
        lines = f.readlines()

    start = end = None
    for i, ln in enumerate(lines):
        if start is None and START_RE.match(ln):
            start = i
        if END_RE.match(ln):
            end = i
            break

    if start is None or end is None:
        print("未找到目标块（可能已处理过）")
        return 0

    if "--check" in sys.argv:
        print("目标块仍存在：行 %d-%d" % (start + 1, end + 1))
        return 1

    # 删除整块（含起止行）
    new = lines[:start] + lines[end + 1:]
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.writelines(new)
    print("已删除内建硬编码动作块：行 %d-%d（共 %d 行）" % (start + 1, end + 1, end - start + 1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
