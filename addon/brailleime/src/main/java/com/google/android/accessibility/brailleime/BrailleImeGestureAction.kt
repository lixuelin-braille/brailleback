/*
 * Copyright 2023 Google Inc.
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

package com.google.android.accessibility.brailleime

import android.content.Context
import androidx.annotation.Nullable
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Gesture
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.input.Swipe.Direction
import com.google.android.accessibility.brailleime.input.UnassignedGesture
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet

/** Collection of braille gestures and actions. */
class BrailleImeGestureAction private constructor() {

  companion object {
    private const val TAG = "BrailleImeGestureAction"
    private const val MAX_SWIPE_TOUCH_POINT = 3
    // We assume users are right-handed by default, meaning they would hold the dots with their left
    // fingers and swipe with their right.
    private val DEFAULT_ACTION_GESTURE: ImmutableMap<BrailleImeAction, Gesture> =
      ImmutableMap.builder<BrailleImeAction, Gesture>()
        .put(BrailleImeAction.MOVE_CURSOR_BACKWARD, Swipe(Direction.UP, /* touchCount= */ 1))
        .put(BrailleImeAction.MOVE_CURSOR_FORWARD, Swipe(Direction.DOWN, /* touchCount= */ 1))
        .put(BrailleImeAction.ADD_SPACE_OR_NEXT_ITEM, Swipe(Direction.RIGHT, /* touchCount= */ 1))
        .put(
          BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM,
          Swipe(Direction.LEFT, /* touchCount= */ 1))
        .put(BrailleImeAction.SUBMIT_TEXT, Swipe(Direction.UP, /* touchCount= */ 2))
        // 双指下滑原本是"隐藏键盘"，枚举里本就标着 not used，系统返回键已经能收键盘；
        // 双指左滑原本是"删词"，与单指左滑的删字重复，而中文是按字输入的。
        // 中文输入以打点为主，这两个低频手势不再默认占用，用户仍可在设置里自行指派。
        .put(BrailleImeAction.ADD_NEWLINE, Swipe(Direction.RIGHT, /* touchCount= */ 2))
        .put(BrailleImeAction.HELP_AND_OTHER_ACTIONS, Swipe(Direction.UP, /* touchCount= */ 3))
        .put(BrailleImeAction.SWITCH_KEYBOARD, Swipe(Direction.DOWN, /* touchCount= */ 3))
        .put(BrailleImeAction.NEXT_GRANULARITY, Swipe(Direction.RIGHT, /* touchCount= */ 3))
        .put(BrailleImeAction.PREVIOUS_GRANULARITY, Swipe(Direction.LEFT, /* touchCount= */ 3))
        .put(
          BrailleImeAction.PREVIOUS_LINE,
          DotHoldSwipe(
            Swipe(Direction.UP, /* touchCount= */ 1), BrailleCharacter(*arrayOf(4))))
        .put(
          BrailleImeAction.NEXT_LINE,
          DotHoldSwipe(
            Swipe(Direction.DOWN, /* touchCount= */ 1), BrailleCharacter(*arrayOf(4))))
        .put(
          BrailleImeAction.SELECT_PREVIOUS_LINE,
          DotHoldSwipe(
            Swipe(Direction.UP, /* touchCount= */ 2), BrailleCharacter(*arrayOf(4))))
        .put(
          BrailleImeAction.SELECT_NEXT_LINE,
          DotHoldSwipe(
            Swipe(Direction.DOWN, /* touchCount= */ 2), BrailleCharacter(*arrayOf(4))))
        .put(
          BrailleImeAction.CUT,
          DotHoldSwipe(
            Swipe(Direction.UP, /* touchCount= */ 3), BrailleCharacter(*arrayOf(4))))
        .put(
          BrailleImeAction.COPY,
          DotHoldSwipe(
            Swipe(Direction.DOWN, /* touchCount= */ 3), BrailleCharacter(*arrayOf(4))))
        .put(
          BrailleImeAction.PASTE,
          DotHoldSwipe(
            Swipe(Direction.RIGHT, /* touchCount= */ 3), BrailleCharacter(*arrayOf(4))))
        .put(
          BrailleImeAction.SELECT_ALL,
          DotHoldSwipe(
            Swipe(Direction.LEFT, /* touchCount= */ 3), BrailleCharacter(*arrayOf(4))))
        .put(
          BrailleImeAction.SELECT_CURRENT_TO_START,
          DotHoldSwipe(
            Swipe(Direction.UP, /* touchCount= */ 2), BrailleCharacter(*arrayOf(4, 5))))
        .put(
          BrailleImeAction.SELECT_CURRENT_TO_END,
          DotHoldSwipe(
            Swipe(Direction.DOWN, /* touchCount= */ 2), BrailleCharacter(*arrayOf(4, 5))))
        .put(
          BrailleImeAction.PREVIOUS_WORD,
          DotHoldSwipe(
            Swipe(Direction.UP, /* touchCount= */ 1), BrailleCharacter(*arrayOf(5))))
        .put(
          BrailleImeAction.NEXT_WORD,
          DotHoldSwipe(
            Swipe(Direction.DOWN, /* touchCount= */ 1), BrailleCharacter(*arrayOf(5))))
        .put(
          BrailleImeAction.SELECT_PREVIOUS_WORD,
          DotHoldSwipe(
            Swipe(Direction.UP, /* touchCount= */ 2), BrailleCharacter(*arrayOf(5))))
        .put(
          BrailleImeAction.SELECT_NEXT_WORD,
          DotHoldSwipe(
            Swipe(Direction.DOWN, /* touchCount= */ 2), BrailleCharacter(*arrayOf(5))))
        .put(
          BrailleImeAction.PREVIOUS_CHARACTER,
          DotHoldSwipe(
            Swipe(Direction.UP, /* touchCount= */ 1), BrailleCharacter(*arrayOf(6))))
        .put(
          BrailleImeAction.NEXT_CHARACTER,
          DotHoldSwipe(
            Swipe(Direction.DOWN, /* touchCount= */ 1), BrailleCharacter(*arrayOf(6))))
        .put(
          BrailleImeAction.SELECT_PREVIOUS_CHARACTER,
          DotHoldSwipe(
            Swipe(Direction.UP, /* touchCount= */ 2), BrailleCharacter(*arrayOf(6))))
        .put(
          BrailleImeAction.SELECT_NEXT_CHARACTER,
          DotHoldSwipe(
            Swipe(Direction.DOWN, /* touchCount= */ 2), BrailleCharacter(*arrayOf(6))))
        .put(
          BrailleImeAction.BEGINNING_OF_PAGE,
          DotHoldSwipe(
            Swipe(Direction.UP, /* touchCount= */ 1), BrailleCharacter(*arrayOf(4, 5))))
        .put(
          BrailleImeAction.END_OF_PAGE,
          DotHoldSwipe(
            Swipe(Direction.DOWN, /* touchCount= */ 1), BrailleCharacter(*arrayOf(4, 5))))
        .buildOrThrow()

    private val DEFAULT_ACTION_GESTURE_MIRRORED: ImmutableMap<BrailleImeAction, ImmutableList<Gesture>> =
      createMapWithMirroredDots()

    private val VALID_HOLD: ImmutableSet<BrailleCharacter> =
      ImmutableSet.of(
        BrailleCharacter(*arrayOf(1)),
        BrailleCharacter(*arrayOf(2)),
        BrailleCharacter(*arrayOf(3)),
        BrailleCharacter(*arrayOf(4)),
        BrailleCharacter(*arrayOf(5)),
        BrailleCharacter(*arrayOf(6)),
        BrailleCharacter(*arrayOf(1, 2)),
        BrailleCharacter(*arrayOf(1, 3)),
        BrailleCharacter(*arrayOf(2, 3)),
        BrailleCharacter(*arrayOf(1, 2, 3)),
        BrailleCharacter(*arrayOf(4, 5)),
        BrailleCharacter(*arrayOf(4, 6)),
        BrailleCharacter(*arrayOf(5, 6)),
        BrailleCharacter(*arrayOf(4, 5, 6)))

    /**
     * Returns the default gesture of the given action. If no match exists, return {@link
     * UnassignedGesture}.
     */
    @JvmStatic
    fun getDefaultGesture(action: BrailleImeAction?): List<Gesture> {
      for (entry in DEFAULT_ACTION_GESTURE_MIRRORED.entries) {
        if (entry.key == action) {
          return ArrayList(entry.value)
        }
      }
      return listOf(UnassignedGesture())
    }

    /** Returns the list of default actions of the given gesture. */
    @JvmStatic
    fun getDefaultAction(gesture: Gesture?): List<BrailleImeAction> {
      val brailleImeActionList = ArrayList<BrailleImeAction>()
      for (entry in DEFAULT_ACTION_GESTURE_MIRRORED.entries) {
        if (entry.value.contains(gesture)) {
          brailleImeActionList.add(entry.key)
        }
      }
      return brailleImeActionList
    }

    /** Gets the list of {@link BrailleImeAction} with the same root. */
    @JvmStatic
    fun getActionWithSameRoot(brailleImeAction: BrailleImeAction?): List<BrailleImeAction> {
      val list = ArrayList<BrailleImeAction>()
      val target = brailleImeAction!!
      if (target.getRootAction() != target) {
        list.add(target.getRootAction())
      }
      for (action in BrailleImeAction.values()) {
        if (action != target &&
            action != target.getRootAction() &&
            action.getRootAction() == target.getRootAction()) {
          list.add(action)
        }
      }
      return list
    }

    /**
     * Returns the gesture of the given action. If no match exists, return {@link UnassignedGesture}.
     */
    @JvmStatic
    fun getGesture(context: Context, action: BrailleImeAction?): List<Gesture> {
      val map = getActionGestureMap(context)
      if (map.containsKey(action)) {
        return ArrayList(map[action]!!)
      }
      return listOf(UnassignedGesture())
    }

    private fun getGesture(
      actioGesturenMap: Map<BrailleImeAction, List<String>>, action: BrailleImeAction,
    ): ImmutableList<Gesture> {
      val gesturesBuilder = ImmutableList.builder<Gesture>()
      for (gestureId in actioGesturenMap[action]!!) {
        val gesture = getGestureById(gestureId)
        if (gesture != null) {
          gesturesBuilder.add(gesture)
        }
      }
      return gesturesBuilder.build()
    }

    /** Returns the gesture of the given action or null if no match exists. */
    @JvmStatic
    fun getAction(context: Context, gesture: Gesture?): List<BrailleImeAction> {
      val map = getActionGestureMap(context)
      val brailleImeActionList = ArrayList<BrailleImeAction>()
      for (entry in map.entries) {
        if (entry.value.contains(gesture)) {
          brailleImeActionList.add(entry.key)
        }
      }
      return brailleImeActionList
    }

    /** Returns true if the gesture is valid. */
    @JvmStatic
    fun isValid(gesture: Gesture?): Boolean {
      if (gesture is Swipe && gesture.touchCount <= MAX_SWIPE_TOUCH_POINT) {
        return true
      }
      return gesture is DotHoldSwipe &&
        gesture.getSwipe().touchCount <= MAX_SWIPE_TOUCH_POINT &&
        VALID_HOLD.contains(gesture.getHeldDots())
    }

    /** Returns the action gesture map. */
    private fun getActionGestureMap(
      context: Context,
    ): ImmutableMap<BrailleImeAction, ImmutableList<Gesture>> {
      if (!FeatureFlagReader.useGestureCustomization(context)) {
        return DEFAULT_ACTION_GESTURE_MIRRORED
      }
      val customizedActioGesturenMap: MutableMap<BrailleImeAction, List<String>> =
        BrailleUserPreferences.readGestureActionMap(context)
      val currentlyUsingActionGestureMap = HashMap<BrailleImeAction, ImmutableList<Gesture>>()
      for (defaultAction in DEFAULT_ACTION_GESTURE_MIRRORED.keys) {
        val list: ImmutableList<Gesture>
        if (customizedActioGesturenMap.containsKey(defaultAction)) {
          list = getGesture(customizedActioGesturenMap, defaultAction)
        } else if (customizedActioGesturenMap.containsKey(defaultAction.getRootAction())) {
          list = getGesture(customizedActioGesturenMap, defaultAction.getRootAction())
        } else {
          list = DEFAULT_ACTION_GESTURE_MIRRORED[defaultAction]!!
        }
        currentlyUsingActionGestureMap[defaultAction] = list
      }
      return ImmutableMap.copyOf(currentlyUsingActionGestureMap)
    }

    @Nullable
    private fun getGestureById(id: String): Gesture? {
      var gesture: Gesture? = null
      try {
        gesture = Swipe(id)
      } catch (e: IllegalArgumentException) {
        BrailleImeLog.w(TAG, "Failed to create Swipe", e)
      }
      if (gesture == null) {
        try {
          gesture = DotHoldSwipe(id)
        } catch (e: IllegalArgumentException) {
          BrailleImeLog.w(TAG, "Failed to create DotHoldSwipe", e)
        }
      }
      if (gesture == null) {
        gesture = UnassignedGesture()
        if (gesture.getId() == id) {
          return gesture
        }
      }
      return gesture
    }

    private fun createMapWithMirroredDots(): ImmutableMap<BrailleImeAction, ImmutableList<Gesture>> {
      val builder = ImmutableMap.builder<BrailleImeAction, ImmutableList<Gesture>>()
      for (action in BrailleImeAction.values()) {
        val gestures: ImmutableList<Gesture>
        if (DEFAULT_ACTION_GESTURE.containsKey(action)) {
          gestures = getDefaultGestures(action)
        } else if (action != action.getRootAction()) {
          gestures = getDefaultGestures(action.getRootAction())
        } else {
          gestures = ImmutableList.of(UnassignedGesture())
        }
        builder.put(action, gestures)
      }
      return builder.buildOrThrow()
    }

    private fun getDefaultGestures(action: BrailleImeAction): ImmutableList<Gesture> {
      val gesturesBuilder = ImmutableList.builder<Gesture>()
      val defaultGesture = DEFAULT_ACTION_GESTURE[action]!!
      gesturesBuilder.add(defaultGesture)
      if (defaultGesture != defaultGesture.mirrorDots()) {
        gesturesBuilder.add(defaultGesture.mirrorDots()!!)
      }
      return gesturesBuilder.build()
    }
  }
}
