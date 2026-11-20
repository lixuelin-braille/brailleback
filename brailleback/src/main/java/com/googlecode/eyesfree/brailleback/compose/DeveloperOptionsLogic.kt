/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

/**
 * 「开发者选项」子屏的纯逻辑：不依赖 Android Context，便于 JVM 单测。
 *
 * 七个界面项与 res/xml/settings.xml 中同名子屏一一对应，其中只有
 * log_level 与 overlay_enabled 真正写入 SharedPreferences；其余五项
 * 是动作入口（查看 / 分享 / 复制 / 清除日志、启动教程）。
 *
 * 四个必须照搬的旧行为：
 * 1) log_level 是 String 偏好，改动必须经 PreferenceUtils.updateLogLevel；
 *    诊断包里该方法返回 false，此时不能把新值写入偏好；
 * 2) 叠加层被打开时要跑一次 OverlayTutorialActivity.startIfFirstTime；
 * 3) 教程行有 app:dependency=overlay_enabled，叠加层关闭时不可用；
 * 4) 日志正文最多 256KB×4，全文交给读屏线性朗读不可行，
 *    查看对话框只朗读末尾预览，完整内容走复制 / 分享。
 */
object DeveloperOptionsLogic {

    const val KEY_LOG_LEVEL = "log_level"
    const val KEY_VIEW_LOG = "view_log"
    const val KEY_SHARE_LOG = "share_log"
    const val KEY_COPY_LOG = "copy_log"
    const val KEY_CLEAR_LOG = "clear_log"
    const val KEY_OVERLAY = "overlay_enabled"
    const val KEY_OVERLAY_TUTORIAL = "show_overlay_tutorial"

    /** 界面顺序即朗读顺序：日志五项，然后叠加层两项。 */
    val ITEM_KEYS = listOf(
        KEY_LOG_LEVEL,
        KEY_VIEW_LOG,
        KEY_SHARE_LOG,
        KEY_COPY_LOG,
        KEY_CLEAR_LOG,
        KEY_OVERLAY,
        KEY_OVERLAY_TUTORIAL,
    )

    /** 真正持久化的键，顺序稳定，供契约测试与 donottranslate.xml 交叉校验。 */
    val PERSISTED_KEYS = listOf(KEY_LOG_LEVEL, KEY_OVERLAY)

    /** 旧 CheckBoxPreference 的 app:defaultValue="@bool/pref_braille_overlay_default"。 */
    const val OVERLAY_DEFAULT = false

    /** 查看对话框里朗读的最近日志字符数；完整日志仍由复制 / 分享导出。 */
    const val LOG_PREVIEW_MAX_CHARS = 4000

    /** ListPreference 的值是字符串形式的 android.util.Log 级别常量。 */
    fun parseLogLevel(value: String?): Int? = value?.toIntOrNull()

    /**
     * 诊断包（BuildConfig.DIAG_LOG_LEVEL>0）里 updateLogLevel 返回 false，
     * 旧 Fragment 的 onPreferenceChange 也据此返回 false 不保存新值。
     */
    fun shouldPersistLogLevel(parsed: Int?, updateAccepted: Boolean): Boolean =
        parsed != null && updateAccepted

    /** 首次读取或历史脏值时回落到 XML 的 app:defaultValue，避免列表行朗读空白。 */
    fun initialLogLevel(stored: String?, default: String, allowed: List<String>): String =
        if (stored != null && stored in allowed) stored else default

    /** 当前值对应的可读标签；值不在表中时退回第一项，不朗读空白。 */
    fun labelForValue(values: List<String>, entries: List<String>, value: String): String {
        val index = values.indexOf(value)
        return entries.getOrElse(index) { entries.firstOrNull().orEmpty() }
    }

    /** app:dependency 的等价判定：叠加层没开，教程行就不可用。 */
    fun tutorialEnabled(overlayEnabled: Boolean): Boolean = overlayEnabled

    /** 旧 onPreferenceChange 只在 newValue==true 时检查首次教程。 */
    fun shouldCheckFirstTimeTutorial(newValue: Boolean): Boolean = newValue

    fun hasLog(raw: String): Boolean = raw.isNotBlank()

    /**
     * 日志查看对话框的正文：空日志给提示，超长日志只保留最近一段并在开头
     * 用文字说明「完整内容请复制或分享」。不靠滚动位置传达状态。
     */
    fun logPreview(
        raw: String,
        emptyLabel: String,
        truncatedHint: String,
        maxChars: Int = LOG_PREVIEW_MAX_CHARS,
    ): String {
        if (raw.isBlank()) return emptyLabel
        if (raw.length <= maxChars) return raw
        return truncatedHint + "\n" + raw.takeLast(maxChars)
    }
}
