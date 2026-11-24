#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一次性离线工具：把 Brailix 参考表（只读源）转成 brailleback 模块内入库的 Kotlin 常量。

- 源：D:\\软件\\brailix-test\\brailix-repo-src\\brailix\\resources（与桌面抽取副本一致）
- 产物：brailleback/src/main/java/com/linxi/brailleback/math/MathTables.kt
- 溯源副本：brailleback/src/main/assets/math/*.json

约束：本脚本只在工具期（开发者本机）运行；App 构建与运行期不依赖 Python，
生成物 MathTables.kt 是纯 Kotlin 常量，可直接被 JVM 单测与 Android 代码引用。

点位编码：dot1=bit0 … dot6=bit5（与项目既有 displayDots 契约一致）；
第 7/8 点在 6 点表中恒为 0，8 点设备复用同一套点位。
"""

from __future__ import annotations

import html.entities as html5
import json
import re
import shutil
from pathlib import Path

REPO = Path(r"D:\软件\brailix-test\brailix-repo-src\brailix\resources")
MATH = REPO / "cn" / "current" / "math"
APP_MATH = Path(r"D:\软件\brailleback盲文\brailleback\src\main\java\com\linxi\brailleback\math")
APP_ASSETS = Path(r"D:\软件\brailleback盲文\brailleback\src\main\assets\math")

CODEPOINT_RE = re.compile(r"^U\+([0-9A-Fa-f]{4,6})$")


def load(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def entity_to_char(name: str) -> str:
    m = CODEPOINT_RE.match(name)
    if m:
        return chr(int(m.group(1), 16))
    expanded = html5.html5.get(f"{name};")
    if expanded is None or len(expanded) != 1:
        raise ValueError(f"无法解析符号表键名: {name!r}")
    return expanded


def dots_to_mask(dots: list[int]) -> int:
    mask = 0
    for d in dots:
        mask |= 1 << (d - 1)
    return mask


def kt_char(ch: str) -> str:
    cp = ord(ch)
    if ch == "'":
        return "'\\''"
    if ch == "\\":
        return "'\\\\'"
    if 32 <= cp < 127:
        return f"'{ch}'"
    return f"'\\u{cp:04X}'"


def kt_str(s: str | None) -> str:
    if s is None:
        return "null"
    return json.dumps(s, ensure_ascii=False)


def main() -> None:
    cells_raw = load(REPO / "cells.json")
    symbols_raw = load(MATH / "symbols.json")["symbols"]
    functions_raw = load(MATH / "functions.json")["functions"]
    structures_raw = load(MATH / "structures.json")["structures"]
    digits_lower_raw = load(MATH / "digits_lower.json")["digits_lower"]
    numbers_raw = load(REPO / "numbers.json")
    letters_raw = load(REPO / "latin" / "letters.json")["letters"]
    greek_raw = load(REPO / "greek" / "letters.json")["letters"]

    # c-name -> dots mask
    cells: dict[str, int] = {}
    for name, dots in cells_raw.items():
        if name.startswith("_") or not isinstance(dots, list):
            continue
        cells[name] = dots_to_mask(dots)

    # 先解析符号表条目（cells 引用可能是 c_* 或另一个符号键名，递归展开）
    symbol_resolved: dict[str, dict] = {}

    def resolve_symbol_ref(ref: str, seen: set[str]) -> list[int]:
        if ref.startswith("c_"):
            return [cells[ref]]
        if ref in seen:
            raise ValueError(f"符号引用成环: {ref}")
        if ref not in symbols_raw:
            raise ValueError(f"未知符号引用: {ref}")
        return resolve_symbol_entry(ref, seen | {ref})

    def resolve_symbol_entry(key: str, seen: set[str]) -> list[int]:
        entry = symbols_raw[key]
        out: list[int] = []
        for ref in entry["cells"]:
            out.extend(resolve_symbol_ref(ref, seen))
        return out

    symbols: dict[str, dict] = {}
    for key, entry in symbols_raw.items():
        if key.startswith("_"):
            continue
        chars = entity_to_char(key)
        symbols[chars] = {
            "cells": resolve_symbol_entry(key, {key}),
            "role": entry.get("role"),
            "space_before": bool(entry.get("space_before", False)),
            "space_after": bool(entry.get("space_after", False)),
            "indicator": entry.get("indicator"),
            "accent_mark": entry.get("accent_mark"),
        }

    # 函数表：cells 引用 c_* 或另一个函数键名
    func_resolved: dict[str, list[int]] = {}

    def resolve_func_ref(ref: str, seen: set[str]) -> list[int]:
        if ref.startswith("c_"):
            return [cells[ref]]
        if ref in seen:
            raise ValueError(f"函数引用成环: {ref}")
        return resolve_func_entry(ref, seen | {ref})

    def resolve_func_entry(name: str, seen: set[str]) -> list[int]:
        out: list[int] = []
        for ref in functions_raw[name]["cells"]:
            out.extend(resolve_func_ref(ref, seen))
        return out

    functions: dict[str, list[int]] = {}
    for name in functions_raw:
        functions[name] = resolve_func_entry(name, {name})

    # 结构表：拍平成 dotted name -> masks
    structures: dict[str, list[int]] = {}

    def walk(prefix: str, node: dict) -> None:
        for k, v in node.items():
            if isinstance(v, dict):
                walk(f"{prefix}{k}.", v)
            elif isinstance(v, list):
                masks: list[int] = []
                for ref in v:
                    if not ref.startswith("c_"):
                        raise ValueError(f"结构表引用非 c_*: {prefix}{k} -> {ref}")
                    masks.append(cells[ref])
                structures[f"{prefix}{k}"] = masks

    walk("", structures_raw)

    digits_upper = {ch: cells[ref] for ch, ref in numbers_raw["digits"].items()}
    digits_lower = {ch: cells[ref] for ch, ref in digits_lower_raw.items()}
    letters_lower = {ch: cells[ref] for ch, ref in letters_raw["lower"].items()}
    letters_upper = {ch: cells[ref] for ch, ref in letters_raw["upper"].items()}

    # 希腊字母：值为 c_* 点位名，或同表内别名（ϕ→φ 等），递归展开。
    def resolve_greek(ref: str, table: dict, seen: set[str]) -> int:
        if ref.startswith("c_"):
            return cells[ref]
        if ref in seen:
            raise ValueError(f"希腊字母别名成环: {ref}")
        return resolve_greek(table[ref], table, seen | {ref})

    greek_lower = {
        ch: resolve_greek(ref, greek_raw["lower"], {ch})
        for ch, ref in greek_raw["lower"].items() if not ch.startswith("_")
    }
    greek_upper = {
        ch: resolve_greek(ref, greek_raw["upper"], {ch})
        for ch, ref in greek_raw["upper"].items() if not ch.startswith("_")
    }
    number_sign = cells[numbers_raw["number_sign"]]

    # ---- 生成 Kotlin ----
    lines: list[str] = []
    lines.append("/*")
    lines.append(" * Copyright (C) 2026 盲文服务 (Braille Service)")
    lines.append(" *")
    lines.append(" * 本文件由 tools/math-tables-gen/generate_math_tables.py 离线生成，请勿手改；")
    lines.append(" * 表源为 Brailix 参考表（只读，《中国盲文》6 点），溯源 JSON 见 assets/math/。")
    lines.append(" * 构建与运行期不依赖 Python；点位 dot1=bit0 … dot6=bit5，7/8 点恒 0。")
    lines.append(" */")
    lines.append("")
    lines.append("package com.linxi.brailleback.math")
    lines.append("")
    lines.append("/** 数学符号条目：cells 为裸区分点；分类标记（indicator）由渲染层另发，不烘进 cells。 */")
    lines.append("internal data class MathSymbolEntry(")
    lines.append("    val cells: List<IntArray>,")
    lines.append("    val role: String?,")
    lines.append("    val spaceBefore: Boolean,")
    lines.append("    val spaceAfter: Boolean,")
    lines.append("    val indicator: String?,")
    lines.append("    val accentMark: String?,")
    lines.append(")")
    lines.append("")
    lines.append("/**")
    lines.append(" * 中文数学盲文表（cn_current，6 点，《中国盲文》）。")
    lines.append(" * 键名与 Brailix 表内一致：c_235 这类点位名保留在溯源 JSON 里，本文件只存位掩码。")
    lines.append(" */")
    lines.append("internal object MathTables {")

    def dump_mask_map(title: str, mapping: dict[str, int], var_name: str) -> None:
        lines.append(f"    /** {title} */")
        lines.append(f"    val {var_name}: Map<Char, IntArray> = mapOf(")
        for ch, mask in sorted(mapping.items()):
            lines.append(f"        {kt_char(ch)} to intArrayOf({mask}),")
        lines.append("    )")
        lines.append("")

    dump_mask_map("拉丁小写字母裸点位（不含 class 前缀）。", letters_lower, "lettersLower")
    dump_mask_map("拉丁大写字母裸点位（不含 class 前缀）。", letters_upper, "lettersUpper")
    dump_mask_map("希腊小写字母裸点位（前缀 greek_lower ⠨ 另发）。", greek_lower, "greekLower")
    dump_mask_map("希腊大写字母裸点位（前缀 greek_upper ⠸ 另发）。", greek_upper, "greekUpper")
    dump_mask_map("基线数字点位（⠼ 数字符号之后）。", digits_upper, "digitsUpper")
    dump_mask_map(" Antoine 低位数字点位（纯数字下标/指数、分数分母用）。", digits_lower, "digitsLower")

    lines.append(f"    /** 数字符号 ⠼（c_3456）。 */")
    lines.append(f"    val numberSign: IntArray = intArrayOf({number_sign})")
    lines.append("")

    # 函数表
    lines.append("    /** 函数名缩写；前缀 ⠫（indicator.symbol）由渲染层另发。 */")
    lines.append("    val functions: Map<String, List<IntArray>> = mapOf(")
    for name, masks in sorted(functions.items()):
        arr = ", ".join(f"intArrayOf({m})" for m in masks)
        lines.append(f"        {kt_str(name)} to listOf({arr}),")
    lines.append("    )")
    lines.append("")

    # 结构表
    lines.append("    /** 结构标记（分数/上下标/根号/字母前缀/分类标记/括号/矩阵…），点名为 Brailix dotted 名。 */")
    lines.append("    val structures: Map<String, List<IntArray>> = mapOf(")
    for name, masks in sorted(structures.items()):
        arr = ", ".join(f"intArrayOf({m})" for m in masks)
        lines.append(f"        {kt_str(name)} to listOf({arr}),")
    lines.append("    )")
    lines.append("")

    # 符号表
    lines.append("    /** 数学符号（op/rel/delim/punct/shape/big_op/accent），键为 Unicode 字符。 */")
    lines.append("    val symbols: Map<Char, MathSymbolEntry> = mapOf(")
    for ch, entry in sorted(symbols.items()):
        arr = ", ".join(f"intArrayOf({m})" for m in entry["cells"])
        lines.append(
            "        {c} to MathSymbolEntry(listOf({arr}), {role}, {sb}, {sa}, {ind}, {mark}),".format(
                c=kt_char(ch), arr=arr, role=kt_str(entry["role"]),
                sb="true" if entry["space_before"] else "false",
                sa="true" if entry["space_after"] else "false",
                ind=kt_str(entry["indicator"]),
                mark=kt_str(entry["accent_mark"]),
            )
        )
    lines.append("    )")
    lines.append("}")
    lines.append("")

    APP_MATH.mkdir(parents=True, exist_ok=True)
    out_kt = APP_MATH / "MathTables.kt"
    out_kt.write_text("\n".join(lines), encoding="utf-8")
    print(f"written: {out_kt} ({len(lines)} lines)")

    # ---- 溯源 JSON 副本 ----
    APP_ASSETS.mkdir(parents=True, exist_ok=True)
    copies = [
        (MATH / "symbols.json", "symbols.json"),
        (MATH / "functions.json", "functions.json"),
        (MATH / "structures.json", "structures.json"),
        (MATH / "digits_lower.json", "digits_lower.json"),
        (REPO / "cells.json", "cells.json"),
        (REPO / "numbers.json", "numbers.json"),
        (REPO / "latin" / "letters.json", "letters_latin.json"),
        (REPO / "greek" / "letters.json", "letters_greek.json"),
    ]
    for src, dest in copies:
        shutil.copy2(src, APP_ASSETS / dest)
        print(f"asset: {dest}")

    # 自检：四条硬 golden 用到的条目必须可解析
    for key in ["c_235", "c_36", "c_2356", "c_126", "c_345"]:
        assert key in cells, key
    for name in ["sin", "cos", "tan", "log", "ln"]:
        assert name in functions, name
    for s in ["fraction.open", "fraction.bar", "fraction.close",
              "script.sup", "script.sub", "script.close",
              "sqrt.open", "sqrt.indicator", "sqrt.close",
              "indicator.symbol", "indicator.operation", "indicator.negation",
              "letter_prefix.latin_lower", "letter_prefix.latin_upper",
              "letter_prefix.greek_lower", "letter_prefix.greek_upper",
              "cases.first", "cases.middle", "cases.last",
              "matrix.op_prefix", "accent.over", "accent.under"]:
        assert s in structures, s
    for ch in ["α", "π", "Ω"]:
        assert ch in greek_lower or ch in greek_upper, ch
    print("self-check ok")


if __name__ == "__main__":
    main()
