/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.keymap

import android.content.Context
import android.content.SharedPreferences
import com.googlecode.eyesfree.utils.SettingsStore
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.brailleback.R

/**
 * 自定义按键映射的持久化层。
 *
 * 与项目其它设置共用同一个存储：默认偏好文件由 `SettingsStore`（DataStore + 内存快照）
 * 承载（见 `SettingsStore` 顶部注释、`DisplayManager` 构造函数）。整张表只占一个 String 偏好项，
 * 设置页写入后 `DisplayManager.onSharedPreferenceChanged` 会即时重载，无需重启服务。
 *
 * 对应 Google TalkBack 的 `KeyComboPersister` / `BrailleUserPreferences.writeGestureAction`：
 * 默认值不落盘、单条覆盖/删除、一键清空复位。
 */
object CustomKeyMapStore {

    /** 偏好项 key（值定义在 donottranslate.xml，避免被翻译）。 */
    @JvmStatic
    fun preferenceKey(context: Context): String =
        context.getString(R.string.pref_custom_key_map_key)

    private fun prefs(context: Context): SharedPreferences =
        SettingsStore.get(context)

    /** 读取并校验整张映射表；没有任何自定义时返回空表（即全部保持默认）。 */
    @JvmStatic
    fun load(context: Context): Map<String, String> =
        BrailleKeyMapPolicy.parse(prefs(context).getString(preferenceKey(context), ""))

    @JvmStatic
    fun save(context: Context, map: Map<String, String>) {
        prefs(context).edit()
            .putString(preferenceKey(context), BrailleKeyMapPolicy.serialize(map))
            .apply()
    }

    /** 修改单条；目标为 DEFAULT 或与源相同时自动删除该条。 */
    @JvmStatic
    fun setMapping(context: Context, source: String, target: String) {
        save(context, BrailleKeyMapPolicy.withMapping(load(context), source, target))
    }

    /** 恢复全部默认：直接删掉整张表。 */
    @JvmStatic
    fun resetAll(context: Context) {
        prefs(context).edit().remove(preferenceKey(context)).apply()
    }

    /**
     * 转成运行时查表用的 `命令码 → 命令码`。
     *
     * 命令名到命令码的换算统一走 `BrailleInputEvent.stringToCommand`，只使用
     * `CMD_NAMES` 中登记过的标准命令名；兼容 BRLTTY 6.7 追加的常量存在值冲突且未登记，
     * 因此 [BrailleKeyMapPolicy] 的白名单刻意不含它们。解析失败的名字静默跳过。
     */
    @JvmStatic
    fun runtimeMap(context: Context): Map<Int, Int> {
        val result = HashMap<Int, Int>()
        for ((sourceName, targetName) in load(context)) {
            val source = BrailleInputEvent.stringToCommand(sourceName)
            if (source == BrailleInputEvent.CMD_NONE) {
                continue
            }
            // 虚拟动作 NONE（「无动作」）没有真实命令码：翻译成 CMD_NONE 交给
            // DisplayManager 吞掉事件。不能走 stringToCommand，那会返回 CMD_NONE 并被当成坏数据跳过。
            if (targetName == BrailleKeyMapPolicy.NO_ACTION) {
                result[source] = BrailleInputEvent.CMD_NONE
                continue
            }
            val target = BrailleInputEvent.stringToCommand(targetName)
            if (target == BrailleInputEvent.CMD_NONE) {
                continue
            }
            result[source] = target
        }
        return result
    }
}
