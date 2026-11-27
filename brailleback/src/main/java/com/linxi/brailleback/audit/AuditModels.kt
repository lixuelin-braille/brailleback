/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * N1 转译体检单（125 §3 / 65 N1）：读导出中间态，输出一张可朗读的问题清单。
 *
 * 本包是纯 JVM 逻辑：不读文件、不碰 Android Context、不打日志、不依赖 UI；
 * 入参类型在本包内自定义，调用方（未来的导出/朗读入口）负责把导出中间态
 * 适配成 [AuditInput]，本包不反向修改 BrailleExporter。
 */

package com.linxi.brailleback.audit

/**
 * 体检用版面几何。口径对齐 `BrailleExporter.PageGeometry`：默认 30 方 × 25 行；
 * 这里在 audit 包内自留一份，避免体检单依赖导出器类型。
 */
data class AuditGeometry(
    val columns: Int = DEFAULT_COLUMNS,
    val linesPerPage: Int = DEFAULT_LINES_PER_PAGE,
) {
    init {
        require(columns > 0) { "columns must be > 0" }
        require(linesPerPage > 0) { "linesPerPage must be > 0" }
    }

    companion object {
        const val DEFAULT_COLUMNS = 30
        const val DEFAULT_LINES_PER_PAGE = 25
    }
}

/**
 * 折行后的一行中间态。
 *
 * @param page 1 起的物理页号
 * @param lineInPage 1 起的页内行号
 * @param text 本行中间态文本（盲文格序列；空方为 U+2800 或空格）
 * @param sourceText 本行对应原文（可选；避头尾判定优先取它的首尾字符）
 */
data class AuditLine(
    val page: Int,
    val lineInPage: Int,
    val text: String,
    val sourceText: String? = null,
)

/** 一个语节（分词后的词/音节单位），供缩写密度统计。 */
data class AuditSegment(
    val sourceText: String,
    /** 该语节是否命中简写/缩写（contraction，见 docs/131 §2.3.3）。 */
    val isContraction: Boolean,
)

/** 问题类别。 */
enum class AuditIssueKind {
    /** 未覆盖字符：原文有、盲文表无对应点位。 */
    UNMAPPED_CHARACTER,

    /** 超宽行：超过版面行宽。 */
    OVERWIDE_LINE,

    /** 避头尾点违例：行首闭标点 / 行尾开标点。 */
    PUNCTUATION_AVOIDANCE,

    /** 缩写密度过高。 */
    CONTRACTION_DENSITY,

    /** 末行孤字：段落末行只剩 1 个字。 */
    ORPHAN_LINE,
}

/** 一条可朗读的体检问题。 */
data class AuditIssue(
    val kind: AuditIssueKind,
    /** 一句话播报文案，已带定位（页/行/字/次数）。 */
    val message: String,
    val page: Int? = null,
    val line: Int? = null,
)

/** 体检报告：问题清单 + 按类计数；对外只暴露可朗读结构，不给宽表格。 */
data class AuditReport(
    val issues: List<AuditIssue>,
) {
    val isClean: Boolean get() = issues.isEmpty()

    fun countByKind(kind: AuditIssueKind): Int = issues.count { it.kind == kind }

    /** 可朗读的逐行清单，每条一句话。 */
    fun readableLines(): List<String> = issues.map { it.message }

    companion object {
        val EMPTY = AuditReport(emptyList())
    }
}
