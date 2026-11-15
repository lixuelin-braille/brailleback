/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.text.Spanned

/**
 * 内容指纹：文本 + 三类标记的区间 + 平移/收缩/分段/可编辑标志。
 *
 * 用来识别「无障碍框架重复下发了完全一样的内容」。只比文本不够：
 * 光标在同一文本上移动、选区变化、平移策略从 PAN_KEEP 变 PAN_RESET
 * 都必须重新显示，否则点显器会停在错误位置。
 *
 * 从 `DisplayManager` 抽成顶层函数：它不读任何实例状态，是纯函数，
 * 因此可以在 JVM 单测里直接喂 `Content` 验证“改了哪一项就会变、没改就不变”。
 */internal fun contentFingerprint(content: Content): String {
    val text = content.getText() ?: return ""
    val builder = StringBuilder(text.length + 48)
    builder.append(text)
    builder.append('\u0000').append(content.getPanStrategy())
    builder.append('\u0000').append(content.getContractionMode())
    builder.append('\u0000').append(content.isEditable())
    builder.append('\u0000').append(content.isSplitParagraphs())
    builder.append('\u0000').append(content.isParagraphIndent())
    builder.append('\u0000').append(content.isFirstParagraphIndent())
    builder.append('\u0000').append(content.isHeading())
    val spanned = content.getSpanned()
    if (spanned != null) {
        // 每类标记前必须先落一个类型标记：否则「选区在 2..3」与「焦点在 2..3」
        // 会拼出完全相同的区间串，后者被当成重复帧丢掉，光标就不画了。
        appendSpanRanges(builder, spanned, DisplaySpans.SelectionSpan::class.java, 'S')
        appendSpanRanges(builder, spanned, DisplaySpans.FocusSpan::class.java, 'F')
        appendSpanRanges(builder, spanned, DisplaySpans.BrailleSpan::class.java, 'B')
    }
    return builder.toString()
}

private fun <T> appendSpanRanges(
    builder: StringBuilder,
    spanned: Spanned,
    type: Class<T>,
    kind: Char,
) {
    builder.append('\u0000').append(kind)
    for (span in spanned.getSpans(0, spanned.length, type)) {
        builder.append('\u0000')
            .append(spanned.getSpanStart(span))
            .append(':')
            .append(spanned.getSpanEnd(span))
    }
}
