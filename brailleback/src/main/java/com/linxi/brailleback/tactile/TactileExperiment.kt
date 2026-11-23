package com.linxi.brailleback.tactile

import android.content.Context
import com.googlecode.eyesfree.utils.SettingsStore

/**
 * 为什么需要它：
 * 触觉图导出是尚未定型的实验功能：算法、版面、入口都还在并行迭代。默认必须关闭，
 * 不进入现有任何功能路径；只有显式打开后，入口行与跳转才允许出现。开关单独收在一处，
 * 避免界面各处各自读布尔值、默认值漂移。
 */
object TactileExperiment {

    /** 偏好键：tactile 前缀，与既有设置写同一份 [SettingsStore]，不另建偏好文件。 */
    private const val KEY_ENABLED = "tactile_experiment_enabled"

    /** 默认关闭：实验功能不得影响任何现有功能。 */
    private const val DEFAULT_ENABLED = false

    /** 实验开关是否已打开。未写过值时返回默认关闭。 */
    fun isEnabled(context: Context): Boolean =
        SettingsStore.get(context.applicationContext)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /** 写入实验开关，apply 异步落盘；快照先更新，紧接着读也能拿到新值。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        SettingsStore.get(context.applicationContext)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
