#!/usr/bin/env python3
"""从 liblouis 中文表提取多音字词组，生成 service/assets/liblouis_phrases.txt。

liblouis 的 `always 词组 盲文格` 规则要求词组连续出现；中文分词一旦在
词组内部插入词间空格，规则就不会触发。分词器用这份词表做保护性合并。

用法（仓库根目录）：python tools/gen_liblouis_phrases.py
"""

from __future__ import annotations

import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TABLE = ROOT / "service/jni/liblouiswrapper/liblouis/tables/zhcn-g1.ctb"
OUT = ROOT / "service/assets/liblouis_phrases.txt"
OVERRIDES = ROOT / "tools/liblouis_phrase_overrides.txt"

ALWAYS_RE = re.compile(r"^always ((?:\\x[0-9A-Fa-f]{4})+) [0-9-]+\s*$")
ESC_RE = re.compile(r"\\x([0-9A-Fa-f]{4})")


def main() -> int:
    if not TABLE.is_file():
        print(f"找不到 liblouis 表: {TABLE}", file=sys.stderr)
        return 1

    phrases: list[str] = []
    for line in TABLE.read_text(encoding="utf-8", errors="replace").splitlines():
        match = ALWAYS_RE.match(line)
        if not match:
            continue
        phrase = "".join(
            chr(int(hex_code, 16)) for hex_code in ESC_RE.findall(match.group(1))
        )
        if 2 <= len(phrase) <= 12 and all("\u4e00" <= ch <= "\u9fff" for ch in phrase):
            phrases.append(phrase)

    # 人工确认的补充词面：用于修正阳光分写等规则造成的确定性错读。
    if OVERRIDES.is_file():
        for line in OVERRIDES.read_text(encoding="utf-8").splitlines():
            phrase = line.strip()
            if not phrase or phrase.startswith("#"):
                continue
            if 2 <= len(phrase) <= 12 and all("\u4e00" <= ch <= "\u9fff" for ch in phrase):
                phrases.append(phrase)

    unique = sorted(set(phrases))
    if not unique:
        print("没有解析到任何词组", file=sys.stderr)
        return 1

    dist = collections.Counter(len(p) for p in unique)
    with OUT.open("w", encoding="utf-8", newline="\n") as out:
        out.write("# liblouis 中文多音字词组（zhcn-g1.ctb + 人工确认覆盖）\n")
        out.write("# 用途：分词时保持词组连续，避免词间空格使 always 规则失效。\n")
        out.write("# 来源：liblouis zhcn-g1.ctb（LGPL-2.1-or-later）+ 人工确认覆盖表。\n")
        out.write(
            "# 生成：python tools/gen_liblouis_phrases.py；共 %d 条。\n" % len(unique)
        )
        for phrase in unique:
            out.write(phrase + "\n")

    print(f"词组 {len(phrases)} 条，去重后 {len(unique)} 条")
    print(f"长度分布: {dict(sorted(dist.items()))}")
    print(f"已写入 {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
