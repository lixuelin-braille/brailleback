#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一次性离线工具：用 Brailix 源码后端实跑 20 条数学 golden，与 Kotlin 单测期望值比对。

- 入口：``Pipeline(profile="cn_current").translate_math_inline(mathml, "mathml")``
  （brailix/pipeline/_pipeline.py；mathml 适配器为 stdlib xml，核心无第三方依赖）。
- 源：D:\\软件\\brailix-test\\brailix-repo-src（离线只读参考）。
- 约束：本脚本只在工具期（开发者本机）运行；App 构建与运行期不依赖 Python，
  产物期望值已落在 brailleback 模块的 MathBrailleGoldenTest.kt。

注意：Brailix 后端不受理 <mfenced>（归入 unsupported 兜底、丢弃子节点），
其规范形态是 mrow 内显式 <mo>(</mo>/<mo>)</mo>；本脚本按显式 mo 形态喂给 Brailix，
Kotlin 单测里用等价 <mfenced> 表达同一语义。

运行：
    python tools/math-tables-gen/verify_golden_brailix.py
"""

from __future__ import annotations

import sys
from pathlib import Path

BRAILIX_REPO = Path(r"D:\软件\brailix-test\brailix-repo-src")
sys.path.insert(0, str(BRAILIX_REPO))

from brailix import Pipeline  # noqa: E402

# (标签, Brailix 输入 MathML（显式 mo 括号形态）, Kotlin 单测期望 Unicode 串)
CASES: list[tuple[str, str, str]] = [
    (
        "a²+b²=c²",
        "<math><mrow><msup><mi>a</mi><mn>2</mn></msup><mo>+</mo>"
        "<msup><mi>b</mi><mn>2</mn></msup><mo>=</mo>"
        "<msup><mi>c</mi><mn>2</mn></msup></mrow></math>",
        "⠰⠁⠌⠆⠀⠖⠰⠃⠌⠆⠀⠶⠰⠉⠌⠆",
    ),
    ("1/2", "<math><mfrac><mn>1</mn><mn>2</mn></mfrac></math>", "⠼⠁⠆"),
    ("√2", "<math><msqrt><mn>2</mn></msqrt></math>", "⠩⠱⠼⠃⠹"),
    (
        "sin(x)",
        "<math><mrow><mi>sin</mi><mo>(</mo><mi>x</mi><mo>)</mo></mrow></math>",
        "⠫⠎⠣⠰⠭⠜",
    ),
    (
        "(a+b)/2",
        "<math><mfrac><mrow><mo>(</mo><mi>a</mi><mo>+</mo><mi>b</mi><mo>)</mo></mrow>"
        "<mn>2</mn></mfrac></math>",
        "⠆⠣⠰⠁⠀⠖⠰⠃⠜⠀⠳⠼⠃⠰",
    ),
    ("x^2", "<math><msup><mi>x</mi><mn>2</mn></msup></math>", "⠰⠭⠌⠆"),
    (
        "√(a+b)",
        "<math><msqrt><mrow><mo>(</mo><mi>a</mi><mo>+</mo><mi>b</mi><mo>)</mo></mrow></msqrt></math>",
        "⠩⠱⠣⠰⠁⠀⠖⠰⠃⠜⠹",
    ),
    (
        "f(x,y)",
        "<math><mrow><mi>f</mi><mo>(</mo><mi>x</mi><mo>,</mo><mi>y</mi><mo>)</mo></mrow></math>",
        "⠰⠋⠣⠰⠭⠐⠀⠰⠽⠜",
    ),
    (
        "2+3=5",
        "<math><mrow><mn>2</mn><mo>+</mo><mn>3</mn><mo>=</mo><mn>5</mn></mrow></math>",
        "⠼⠃⠀⠖⠼⠉⠀⠶⠼⠑",
    ),
    (
        "[a,b]",
        "<math><mrow><mo>[</mo><mi>a</mi><mo>,</mo><mi>b</mi><mo>]</mo></mrow></math>",
        "⠷⠰⠁⠐⠀⠰⠃⠾",
    ),
    # —— 新增结构 ——
    (
        "x_i^2 (msubsup)",
        "<math><msubsup><mi>x</mi><mi>i</mi><mn>2</mn></msubsup></math>",
        "⠰⠭⠡⠊⠱⠌⠆",
    ),
    (
        "∛8 (mroot)",
        "<math><mroot><mn>8</mn><mn>3</mn></mroot></math>",
        "⠩⠼⠉⠱⠼⠓⠹",
    ),
    (
        "√√2 (根号嵌套)",
        "<math><msqrt><msqrt><mn>2</mn></msqrt></msqrt></math>",
        "⠩⠱⠩⠱⠼⠃⠹⠹",
    ),
    (
        "matrix 2×2",
        "<math><mrow><mo>(</mo><mtable>"
        "<mtr><mtd><mi>a</mi></mtd><mtd><mi>b</mi></mtd></mtr>"
        "<mtr><mtd><mi>c</mi></mtd><mtd><mi>d</mi></mtd></mtr>"
        "</mtable><mo>)</mo></mrow></math>",
        "⠀⠣⠰⠁⠀⠰⠃⠜⠀⠣⠰⠉⠀⠰⠙⠜⠀",
    ),
    (
        "cases 2 rows",
        "<math><mrow><mo>{</mo><mtable>"
        "<mtr><mtd><mi>x</mi><mo>+</mo><mi>y</mi></mtd></mtr>"
        "<mtr><mtd><mi>x</mi><mo>\u2212</mo><mi>y</mi></mtd></mtr>"
        "</mtable></mrow></math>",
        "⠀⠎⠇⠣⠎⠀⠰⠭⠀⠖⠰⠽⠀⠣⠀⠰⠭⠀⠤⠰⠽⠀",
    ),
    (
        "x̄ (bar accent)",
        "<math><mover><mi>x</mi><mo>\u00AF</mo></mover></math>",
        "⠰⠭⠘⠒",
    ),
    (
        "v⃗ (vec accent)",
        "<math><mover><mi>v</mi><mo>\u2192</mo></mover></math>",
        "⠰⠧⠘⠒⠂",
    ),
    ("greek α", "<math><mi>α</mi></math>", "⠨⠁"),
    ("greek Ω", "<math><mi>Ω</mi></math>", "⠸⠺"),
    (
        "嵌套分数 (1/2)/3",
        "<math><mfrac><mfrac><mn>1</mn><mn>2</mn></mfrac><mn>3</mn></mfrac></math>",
        "⠼⠁⠆⠳⠼⠉",
    ),
]


def main() -> int:
    pipe = Pipeline(profile="cn_current")
    failures = 0
    for label, mathml, expected in CASES:
        actual = pipe.translate_math_inline(mathml, "mathml")
        ok = actual == expected
        if not ok:
            failures += 1
        status = "PASS" if ok else "FAIL"
        print(f"[{status}] {label}")
        print(f"   brailix = {actual!r}")
        print(f"   expect  = {expected!r}")
    print(f"\n{len(CASES) - failures}/{len(CASES)} matched")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
