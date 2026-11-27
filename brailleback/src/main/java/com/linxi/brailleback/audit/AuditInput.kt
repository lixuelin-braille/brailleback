/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.audit

/**
 * 体检输入：导出中间态的纯数据快照。
 *
 * 所有字段都由调用方在转译/导出完成后适配填入；体检包只读不写，
 * 不回查翻译器、不读文件、不持有 Android 组件引用。
 *
 * @param paragraphs 段落列表；每段由折行后的若干行组成（末行孤字按段落末行判）
 * @param sourceParagraphs 原文段落（未覆盖字符检查的扫描源），与 paragraphs 顺序对应
 * @param coveredChars 盲文表可覆盖字符集；空集表示调用方未提供，跳过未覆盖字符检查
 * @param segments 语节序列；缩写密度统计源
 * @param geometry 版面几何（默认 30 方 × 25 行）
 * @param abbreviationThreshold 缩写密度告警阈值：缩写语节 / 总语节，区间 [0,1]
 */
data class AuditInput(
    val paragraphs: List<List<AuditLine>> = emptyList(),
    val sourceParagraphs: List<String> = emptyList(),
    val coveredChars: Set<Char> = emptySet(),
    val segments: List<AuditSegment> = emptyList(),
    val geometry: AuditGeometry = AuditGeometry(),
    val abbreviationThreshold: Double = DEFAULT_ABBREVIATION_THRESHOLD,
) {
    init {
        require(abbreviationThreshold in 0.0..1.0) { "threshold must be in [0,1]" }
    }

    companion object {
        /**
         * 默认阈值 0.25 的理由：现行盲文二级简写只集中在高频虚词
         * （的/么/你/他/她/它等，见 docs/131 §2.3.3），自然语料里缩写语节
         * 占比通常很低；25% 意味着每 4 个语节就有 1 个缩写，明显偏离自然
         * 口语密度，故取 0.25 作为告警线。调用方可按语种、读物类型（如缩写
         * 较多的二级读物）下调或上调。
         */
        const val DEFAULT_ABBREVIATION_THRESHOLD = 0.25
    }
}
