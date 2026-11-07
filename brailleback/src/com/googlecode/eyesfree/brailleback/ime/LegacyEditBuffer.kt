/*
 * 应急降级用的 EditBuffer 实现——仍然是同一个接口，不是第二套输入引擎。
 *
 * 正常链路的引擎由 BrailleLanguages.createEditBuffer() 按码表创建（软件键盘同款）。
 * 只有当 addon 的翻译库/音节索引加载失败（UnsatisfiedLinkError 等）时，才退回本类：
 * 它把老 BrailleBack 的 host BrailleTranslator.backTranslate 包进 EditBuffer 接口，
 * 于是 BrailleIME 侧的动作分发、按键语义、composing 落定、holdings 回显全都无需分叉。
 *
 * 换句话说：合并后「引擎」在架构上只有一个（EditBuffer），本类只是它的一个实现。
 * 删掉本类与 UnifiedBrailleInput 里的降级分支，不影响主链路任何一行。
 */
package com.googlecode.eyesfree.brailleback.ime

import android.text.TextUtils
import android.view.KeyEvent
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme.ResultForDisplay.HoldingsInfo
import com.google.android.accessibility.braille.common.translate.EditBuffer
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.brailleback.hasUnknownDots
import java.nio.ByteBuffer

class LegacyEditBuffer(
    private val translator: () -> BrailleTranslator?,
) : EditBuffer {

    /** 尚未上屏的点位；与老 mComposingBraille 语义一致。 */
    private val holdings = ArrayList<Byte>(32)

    /** 六点文学盲文要组词，八点电脑盲文一格一字符。 */
    private fun needsComposition(): Boolean =
        translator()?.tableInfo?.isEightDot == false

    override fun appendBraille(imeConnection: ImeConnection, cell: BrailleCharacter): String? {
        val ic = imeConnection.inputConnection ?: return null
        val mask = cell.toByte()
        if (needsComposition()) {
            holdings.add(mask)
            val text = backTranslate(holdings.toByteArray())
            // 空或未知点位用不换行空格占位，保持 composing 下划线（老行为）。
            ic.setComposingText(if (TextUtils.isEmpty(text)) "\u00A0" else text, 1)
            return text
        }
        val text = backTranslate(byteArrayOf(mask)) ?: return null
        if (!TextUtils.isEmpty(text) && !hasUnknownDots(text)) {
            ic.commitText(text, 1)
        }
        return text
    }

    override fun appendSpace(imeConnection: ImeConnection) {
        // 六点表下空点位是「结束组词」而不是「插空格」；holdings 为空时静默忽略，
        // 与老 handleBrailleKeyInternal 的 finishComposingText 语义一致。
        commitHoldings(imeConnection)
    }

    override fun appendNewline(imeConnection: ImeConnection) {
        commitHoldings(imeConnection)
        sendKey(imeConnection, KeyEvent.KEYCODE_ENTER)
    }

    override fun deleteCharacterBackward(imeConnection: ImeConnection) {
        val ic = imeConnection.inputConnection ?: return
        if (holdings.isNotEmpty()) {
            holdings.removeAt(holdings.lastIndex)
            val text = backTranslate(holdings.toByteArray())
            ic.setComposingText(if (TextUtils.isEmpty(text)) "\u00A0" else text, 1)
            if (holdings.isEmpty()) {
                ic.finishComposingText()
            }
            return
        }
        sendKey(imeConnection, KeyEvent.KEYCODE_DEL)
    }

    override fun deleteCharacterForward(imeConnection: ImeConnection) {
        commitHoldings(imeConnection)
        sendKey(imeConnection, KeyEvent.KEYCODE_FORWARD_DEL)
    }

    override fun deleteWord(imeConnection: ImeConnection) {
        if (holdings.isNotEmpty()) {
            holdings.clear()
            imeConnection.inputConnection?.commitText("", 1)
        }
    }

    override fun commit(imeConnection: ImeConnection) {
        commitHoldings(imeConnection)
    }

    override fun moveCursorForward(imeConnection: ImeConnection): Boolean = false

    override fun moveCursorBackward(imeConnection: ImeConnection): Boolean = false

    override fun moveTextFieldCursor(imeConnection: ImeConnection, index: Int): Boolean {
        val ic = imeConnection.inputConnection ?: return false
        ic.setSelection(index, index)
        return true
    }

    override fun getHoldingsInfo(imeConnection: ImeConnection): HoldingsInfo =
        HoldingsInfo.create(
            ByteBuffer.wrap(holdings.toByteArray()),
            holdings.size,
        )

    /** @return true 表示确实提交了组词内容（调用方不应再补空格）。 */
    private fun commitHoldings(imeConnection: ImeConnection): Boolean {
        if (holdings.isEmpty()) {
            return false
        }
        val ic = imeConnection.inputConnection
        val text = backTranslate(holdings.toByteArray())
        holdings.clear()
        if (ic == null) {
            return true
        }
        if (TextUtils.isEmpty(text) || hasUnknownDots(text)) {
            ic.commitText("", 1)
        } else {
            ic.commitText(text, 1)
        }
        return true
    }

    private fun backTranslate(cells: ByteArray): String? = try {
        translator()?.backTranslate(cells)
    } catch (t: Throwable) {
        null
    }

    private fun sendKey(imeConnection: ImeConnection, keyCode: Int) {
        val ic = imeConnection.inputConnection ?: return
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}
