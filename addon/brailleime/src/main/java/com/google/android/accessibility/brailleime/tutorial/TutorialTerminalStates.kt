/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.brailleime.tutorial

import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State

/**
 * 菜单与收尾步骤：从 [TutorialView] 拆出的最后两个状态。
 *
 * [ContextMenuOpened] 负责拉起上下文菜单；[TutorialFinished] 是终态，不再加载任何视图。
 */

class ContextMenuOpened(private val host: TutorialView) : TutorialView.TutorialState {

  override fun getCurrentState(): State {
    return State.CONTEXT_MENU_OPENED
  }

  override fun nextState(): TutorialView.TutorialState {
    return host.tutorialFinished
  }

  override fun audialAnnouncement(): String {
    return host.resources.getString(R.string.open_context_menu_announcement)
  }

  override fun audialAnnouncementRepeated(): String {
    return host.resources.getString(R.string.open_context_menu_announcement)
  }

  override fun onUtteranceCompleted() {
    host.speakAnnouncement(audialAnnouncementRepeated(), /* delayMs= */ 0)
  }

  override fun loadView() {
    host.addView(host.inputView)
    if (!host.contextMenuDialog.isShowing()) {
      host.openContextMenu()
    }
  }
}

class TutorialFinished : TutorialView.TutorialState {
  override fun getCurrentState(): State {
    return State.NONE
  }

  override fun nextState(): TutorialView.TutorialState {
    return this
  }

  override fun loadView() {}
}
