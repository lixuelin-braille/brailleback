/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linxi.brailleback

import android.util.Log
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.utils.LogUtils
import com.linxi.brailleback.ime.BrailleInputPolicy
import com.linxi.brailleback.ime.UnifiedBrailleInput

/**
 * 输入法输入状态机：从 BrailleIME 拆出来的第一半。
 *
 * 统一引擎接线、点位累积/结清/退点、软硬件统一按键入口、composing 落定。
 * 按键语义、攒点规则与候选回退口径原样保留；写成同包 internal 扩展函数，
 * 避免把每个状态字段改写成引擎对象.xxx。
 */

/**
 * 取得唯一输入引擎。所有码表（中文现行 / 英文 / 八点电脑盲文）一律走这里，
 * 由 BrailleLanguages.createEditBuffer 按码表创建对应的 EditBuffer 实现；
 * 不再按 locale 或点数在本类里分流到第二套引擎。
 */
internal fun BrailleIME.unifiedEngine(): UnifiedBrailleInput? {
    if (unifiedBraille == null) {
        unifiedBraille = UnifiedBrailleInput(this, ::getCurrentBrailleTranslator)
    }
    return unifiedBraille?.takeIf { it.isAvailable() }
}

/**
 * 软件 / 硬件统一的输入入口。
 *
 * @return null 表示本次事件不归输入引擎处理（越界点位、前删、导航等），
 *         调用方应继续原来的导航链路；非 null 表示已消费。
 *         没有候选时候选键也返回 null，按键退回原来的光标导航语义。
 */
internal fun BrailleIME.onHardwareInputEvent(command: Int, argument: Int): Boolean? {
    val engine = unifiedEngine() ?: return null
    val route = BrailleInputPolicy.route(command, argument, /* unified= */ true, engine.isEightDot())
    return when (route.action) {
        BrailleInputPolicy.Action.LEGACY -> {
            // 离开输入语义前先把攒着的点交出去，别把用户的点吞掉。
            flushPendingDots(engine)
            null
        }
        BrailleInputPolicy.Action.APPEND_CELL -> accumulateCell(engine, route.cell)
        BrailleInputPolicy.Action.COMMIT -> {
            // 空格既结这一格，也做「上屏」：先交攒点，再提交。
            val appended = flushPendingDots(engine) ?: true
            emitFeedbackOnFailure(appended && engine.commit(), FeedbackManager.TYPE_COMMAND_FAILED)
        }
        BrailleInputPolicy.Action.DELETE -> {
            // 攒点阶段退格只撤最后按下的一个点，不动已上屏内容。
            if (pendingDots != 0) {
                pendingDots = clearLastDot(pendingDots)
                true
            } else {
                emitFeedbackOnFailure(engine.delete(), FeedbackManager.TYPE_COMMAND_FAILED)
            }
        }
        BrailleInputPolicy.Action.ENTER -> {
            val appended = flushPendingDots(engine) ?: true
            emitFeedbackOnFailure(appended && engine.enter(), FeedbackManager.TYPE_COMMAND_FAILED)
        }
        BrailleInputPolicy.Action.NEXT_CANDIDATE -> {
            flushPendingDots(engine)
            if (engine.nextCandidate()) true else null
        }
        BrailleInputPolicy.Action.PREVIOUS_CANDIDATE -> {
            flushPendingDots(engine)
            if (engine.previousCandidate()) true else null
        }
    }
}

/**
 * 点位累积：一次事件只带一个点就继续攒；一次事件本身就带多个点（真正同时按下的
 * chord）才算一格打完，立刻上屏。
 *
 * 注意不要在「攒到第二个点」时就上屏——那样按 1、2、3 会被切成 12 和 3 两格，
 * 逻辑就乱了。攒多少个点由用户决定，结清的一刻只有两个：按空格，或者按回车。
 */
internal fun BrailleIME.echoPressedDots(dots: Int) {
    val manager = getCurrentDisplayManager()
    if (manager != null) {
        manager.showPressedDots(dots)
    } else {
        // 没有宿主时点显器由输入法自己连，回显也得自己画。
        imeDisplay?.showPressedDots(dots)
    }
}

internal fun BrailleIME.accumulateCell(engine: UnifiedBrailleInput, cell: Int): Boolean? {
    if (Integer.bitCount(cell) >= 2) {
        // 一格内多点同时按下：本身就是完整一格，立即提交。
        val mask = pendingDots or cell
        pendingDots = 0
        echoPressedDots(mask)
        return emitFeedbackOnFailure(
            engine.appendCell(mask),
            FeedbackManager.TYPE_COMMAND_FAILED,
        )
    }
    // 单点事件：继续攒，不出声、不提交，等空格或回车结清；先把已攒的点亮到点显器上。
    pendingDots = pendingDots or cell
    echoPressedDots(pendingDots)
    return true
}

/** 把攒着的点作为一格提交；没有攒点时返回 null，调用方可据此跳过。 */
internal fun BrailleIME.flushPendingDots(engine: UnifiedBrailleInput): Boolean? {
    if (pendingDots == 0) {
        return null
    }
    val mask = pendingDots
    pendingDots = 0
    return emitFeedbackOnFailure(
        engine.appendCell(mask),
        FeedbackManager.TYPE_COMMAND_FAILED,
    )
}

/** 撤掉最后按下的那个点（按点号从高到低），用于攒点阶段的退格。 */
internal fun BrailleIME.clearLastDot(mask: Int): Int {
    if (mask == 0) {
        return 0
    }
    return mask and (mask - 1)
}

internal fun BrailleIME.handleBrailleKey(dots: Int): Boolean {
    // 兜底：即使有调用点绕过 onHardwareInputEvent，也只会进同一条链路。
    // 返回 null 的点位（越界、八点表空点位）统一忽略，不再有第二条输入引擎。
    return onHardwareInputEvent(BrailleInputEvent.CMD_BRAILLE_KEY, dots) ?: true
}

/**
 * Cancels the text composition by leaving the already-composed text there
 * and clearing the composition state. Use this when the user tries to
 * interact with the edit field before composition has finished.
 */
internal fun BrailleIME.cancelComposingText(): Boolean {
    val ic = getCurrentInputConnection()
    if (ic == null) {
        LogUtils.log(this, Log.WARN, "missing IC %s", ic)
        return false
    }

    // 统一引擎的 holdings 与输入框 composing 是同一份状态：任何会结束 composing
    // 的动作（前删、方向键、切焦点等）都必须先让缓冲落定，否则输入框的
    // 下划线没了、holdings 还在，下一次输入会重冒出旧字。
    // 与屏幕键盘 BrailleImeActor.performTextAction「动作前先提交」保持同一语义。
    val unified = unifiedEngine()
    if (unified != null && unified.finish()) {
        return true
    }
    return ic.finishComposingText()
}
