/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
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

import com.googlecode.eyesfree.utils.SharedPreferencesUtils
import com.linxi.brailleback.wrapping.SimpleWrapStrategy
import com.linxi.brailleback.wrapping.WordWrapStrategy

/**
 * 折行策略选择与偏好回填：从 [DisplayManager] 拆出的策略层。
 *
 * 按「当前内容是否可编辑 + 输入法是否展开」在编辑折行与偏好折行之间切换；
 * 偏好变更后重建策略并请求重译。写成同包 internal 扩展函数，方法体与拆分前
 * 逐字一致。
 */

internal fun DisplayManager.updateWrapStrategyFromPreferences() {
    val wrap =
        SharedPreferencesUtils.getBooleanPref(
            sharedPreferences,
            context.resources,
            R.string.pref_braille_word_wrap_key,
            R.bool.pref_braille_word_wrap_default)

    preferredWrapStrategy = if (wrap) WordWrapStrategy() else SimpleWrapStrategy()
    updateWrapStrategy()
    displayHandler.retranslate()
}

internal fun DisplayManager.updateWrapStrategy() {
    val contentEditable = currentContent.isEditable()
    val imeMode = context.imeNavigationMode
    val imeOpen = imeMode != null && imeMode.isImeOpen()
    val editing = contentEditable && imeOpen
    wrapStrategy = if (editing) editingWrapStrategy else preferredWrapStrategy
}
