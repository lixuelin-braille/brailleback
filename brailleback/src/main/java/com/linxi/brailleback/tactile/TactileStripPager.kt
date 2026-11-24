package com.linxi.brailleback.tactile

/**
 * 为什么需要它：
 * 成书之后点显器一次只能显示一屏，翻页状态由这个纯 Kotlin 小对象管：当前页序号、
 * 能否前后翻、给读屏的页签文案。纯 JVM 形态才能在单测里把首末页边界逐态跑一遍。
 *
 * 不变量：
 * - 空书不允许构造，直接 [IllegalArgumentException]；
 * - 越界翻页返回 false 且 [index] 保持不变，不循环回绕；
 * - 页字节原样持有，不补长、不重复填充；页码只进播报文案，不进点显字节。
 */
class TactileStripPager(book: StripBook) {

    private val storage: List<StripPage> = book.pages.toList()

    init {
        require(storage.isNotEmpty()) { "书页列表不能为空" }
    }

    /** 总页数（封面 + 图形带 + 封底）。 */
    val pageCount: Int get() = storage.size

    /** 当前页序号（0 基）。 */
    var index: Int = 0
        private set

    /** 当前页。 */
    val current: StripPage get() = storage[index]

    /** 后面还有没有页。 */
    fun canNext(): Boolean = index < storage.size - 1

    /** 前面还有没有页。 */
    fun canPrev(): Boolean = index > 0

    /** 翻到下一页；已在末页时返回 false，序号不变。 */
    fun next(): Boolean {
        if (!canNext()) return false
        index++
        return true
    }

    /** 翻到上一页；已在首页时返回 false，序号不变。 */
    fun prev(): Boolean {
        if (!canPrev()) return false
        index--
        return true
    }

    /** 跳回首页；已在首页时返回 false，序号不变。 */
    fun first(): Boolean {
        if (index == 0) return false
        index = 0
        return true
    }

    /** 跳到末页；已在末页时返回 false，序号不变。 */
    fun last(): Boolean {
        if (index == storage.size - 1) return false
        index = storage.size - 1
        return true
    }

    /**
     * 给 TTS 的播报文案：封面「封面」，封底「封底」，图形页「第 b/N 带」。
     * 页码不写入点显字节。
     */
    fun label(): String = when (storage[index].kind) {
        StripPageKind.COVER -> "封面"
        StripPageKind.BACK -> "封底"
        StripPageKind.GRAPHIC ->
            "第 ${index + 1} 页，第 ${storage[index].bandIndex}/${storage[index].totalBands} 带"
    }
}
