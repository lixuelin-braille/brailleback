/*
 * 盲文输入统一层（第 2 层）：唯一输入引擎的持有者。
 *
 * 引擎彻底合并后的链路：
 *   触屏键盘 BrailleIme ─┐
 *                        ├─→ BrailleInputPolicy（按键语义）
 *   点显器 BrailleIME  ──┘      └─→ EditBuffer（本类持有）→ InputConnection
 *
 * 引擎的创建与软件键盘逐行同源，见 BrailleIme.createEditBuffer()（BrailleIme.java:590-604）：
 *   Code           = BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(context)
 *   contractedMode = readContractedMode(context) && code.isSupportsContracted(context)
 *   factory        = BrailleUserPreferences.readTranslatorFactory(context)
 *   EditBuffer     = BrailleLanguages.createEditBuffer(context, Speaker, code, factory, contracted)
 * 于是中文现行盲文自动得到 EditBufferChinese（音节整串识别、候选、按格删、独立上屏、
 * 中文读名播报），其它码表得到各自的 EditBufferXxx——两个 IME 不再各解释一遍点位。
 *
 * 唯一的降级：addon 翻译库加载失败时改用 LegacyEditBuffer（仍是同一个 EditBuffer 接口，
 * 内部委托 host liblouis），动作分发与按键语义不分叉。
 *
 * 边界：不改 JNI、不改 AIDL、不改驱动、不改 addon 里已稳定的中文状态机。
 */
package com.googlecode.eyesfree.brailleback.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.translate.BrailleLanguages
import com.google.android.accessibility.braille.common.translate.EditBuffer
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import java.nio.ByteBuffer

class UnifiedBrailleInput(
    private val ime: InputMethodService,
    /** 仅供 LegacyEditBuffer 降级使用：主模块 host 的 liblouis 翻译器。 */
    private val hostTranslator: () -> BrailleTranslator?,
) {

    private var buffer: EditBuffer? = null
    private var tried = false
    private var eightDot = false
    private var legacy = false

    /** 引擎是否可用。创建只尝试一次，失败后本次会话不再反复加载 native 库。 */
    fun isAvailable(): Boolean {
        if (!tried) {
            tried = true
            buffer = tryCreate()
        }
        return buffer != null
    }

    /** 当前码表是否八点电脑盲文；决定 DOT7/DOT8 是编辑键还是正常点位。 */
    fun isEightDot(): Boolean = eightDot

    /** 是否退到了应急引擎（供日志/诊断，不参与动作分派）。 */
    fun isLegacy(): Boolean = legacy

    private fun tryCreate(): EditBuffer? {
        val context = ime.applicationContext
        if (!isEnabled(context)) {
            // 回退开关：切到应急 EditBuffer（host liblouis），动作分发仍不分叉。
            Log.i(TAG, "统一引擎开关已关闭，使用应急 EditBuffer")
            eightDot = hostTranslator()?.tableInfo?.isEightDot == true
            legacy = true
            return LegacyEditBuffer(hostTranslator)
        }
        val code = try {
            BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(context)
        } catch (t: Throwable) {
            Log.w(TAG, "读取当前盲文码失败，使用应急引擎: $t")
            null
        }
        if (code != null) {
            eightDot = code.isEightDot()
            try {
                val contracted = BrailleUserPreferences.readContractedMode(context) &&
                    code.isSupportsContracted(context)
                val factory = BrailleUserPreferences.readTranslatorFactory(context)
                val created = BrailleLanguages.createEditBuffer(
                    context,
                    BrailleCommonTalkBackSpeaker.getInstance(),
                    code,
                    factory,
                    contracted,
                )
                Log.i(
                    TAG,
                    "统一盲文输入引擎已启用: code=${code.name} " +
                        "eightDot=$eightDot contracted=$contracted " +
                        "buffer=${created.javaClass.simpleName}",
                )
                return created
            } catch (t: Throwable) {
                // 含 UnsatisfiedLinkError：addon 的 louiswrap_tb 可能未随当前进程加载。
                Log.w(TAG, "统一引擎创建失败，改用应急 EditBuffer: $t")
            }
        }
        legacy = true
        return LegacyEditBuffer(hostTranslator)
    }

    private fun connection(): ImeConnection? {
        val ic = ime.currentInputConnection ?: return null
        val ei = ime.currentInputEditorInfo ?: return null
        return ImeConnection(ic, ei, ImeConnection.AnnounceType.NORMAL)
    }

    /** 追加一格点位。mask 已由 BrailleInputPolicy 校验在 0..0xFF。 */
    fun appendCell(mask: Int): Boolean = run("appendCell") { buffer, connection ->
        buffer.appendBraille(connection, BrailleCharacter(mask.toByte()))
        true
    }

    /** 上屏：中文语义为「结束当前输入」，有内容提交且不插空格，无内容才插真空格。 */
    fun commit(): Boolean = run("commit") { buffer, connection ->
        buffer.appendSpace(connection)
        true
    }

    /** 退格：中文按格删，holdings 空时由 EditBuffer 删输入框已有的字。 */
    fun delete(): Boolean = run("delete") { buffer, connection ->
        buffer.deleteCharacterBackward(connection)
        true
    }

    /** 上屏并提交回车，对齐屏幕键盘的 DOT8 / Enter。 */
    fun enter(): Boolean = run("enter") { buffer, connection ->
        buffer.commit(connection)
        val ic = connection.inputConnection
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        true
    }

    /** @return false 表示当前没有候选可换，调用方应把该按键交回原来的导航逻辑。 */
    fun nextCandidate(): Boolean = run("nextCandidate") { buffer, connection ->
        buffer.moveToNextCandidate(connection)
    }

    fun previousCandidate(): Boolean = run("previousCandidate") { buffer, connection ->
        buffer.moveToPreviousCandidate(connection)
    }

    /** 输入会话结束：把还在 composing 的内容落定，避免切走输入框后丢字。 */
    fun finish(): Boolean = run("finish") { buffer, connection ->
        buffer.commit(connection)
        true
    }

    /**
     * 点显器回显用的未上屏点位。
     *
     * DisplaySpans.BrailleSpan 以 ByteBuffer.position() 为有效长度，而
     * EditBuffer.getHoldingsInfo() 返回的是 position=0 的 wrap buffer，
     * 且用 HoldingsInfo.position() 单独给出格数（空时为 -1）。这里做一次归一化，
     * 否则点显器上会看不到正在输入的盲文格。
     */
    fun holdings(): ByteBuffer? {
        val b = buffer ?: return null
        val c = connection() ?: return null
        return try {
            val info = b.getHoldingsInfo(c)
            val cells = info.holdings()
            val count = info.position().coerceIn(0, cells.limit())
            cells.position(count)
            cells
        } catch (t: Throwable) {
            Log.w(TAG, "读取 holdings 失败: $t")
            null
        }
    }

    fun reset() {
        buffer = null
        tried = false
        legacy = false
        eightDot = false
    }

    private inline fun run(
        name: String,
        action: (EditBuffer, ImeConnection) -> Boolean,
    ): Boolean {
        val b = buffer ?: return false
        val c = connection() ?: return false
        return try {
            action(b, c)
        } catch (t: Throwable) {
            Log.w(TAG, "统一输入动作 $name 失败: $t")
            if (t is LinkageError && !legacy) {
                // 主引擎的 native 库在运行时挂了：切到应急 EditBuffer，本次会话不再重试。
                buffer = LegacyEditBuffer(hostTranslator)
                legacy = true
            }
            false
        }
    }

    companion object {
        private const val TAG = "UnifiedBrailleInput"

        /**
         * 可回退开关：关掉后不加载 addon 引擎，改用 LegacyEditBuffer（老 host liblouis
         * 回译逻辑）。按键语义、动作分发、composing 落定仍走统一链路，只是换了个
         * EditBuffer 实现。默认开启。键名沿用上一版，已有设置不会失效。
         */
        const val PREF_UNIFY = "pref_unify_chinese_hardware_input"

        fun isEnabled(context: Context): Boolean =
            BrailleUserPreferences.getSharedPreferences(
                context,
                BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME,
            ).getBoolean(PREF_UNIFY, true)

        /** 锁死 policy 里复制的命令常量与 AIDL 事件定义不漂移。 */
        fun assertCommandContract() {
            require(BrailleInputPolicy.CMD_BRAILLE_KEY == BrailleInputEvent.CMD_BRAILLE_KEY)
            require(BrailleInputPolicy.CMD_KEY_ENTER == BrailleInputEvent.CMD_KEY_ENTER)
            require(BrailleInputPolicy.CMD_KEY_DEL == BrailleInputEvent.CMD_KEY_DEL)
            require(BrailleInputPolicy.CMD_NAV_LINE_PREVIOUS == BrailleInputEvent.CMD_NAV_LINE_PREVIOUS)
            require(BrailleInputPolicy.CMD_NAV_LINE_NEXT == BrailleInputEvent.CMD_NAV_LINE_NEXT)
        }
    }
}
