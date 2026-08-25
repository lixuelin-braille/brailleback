/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Sound event types for the earcon (sound effect) system (Kotlin 版).
 * Every event can play a distinct earcon; the sound is chosen per built-in
 * "scheme" (variant).
 */

package com.googlecode.eyesfree.brailleback.sound

import com.googlecode.eyesfree.brailleback.R

/**
 * A sound event that can play a distinct earcon.
 *
 * Each event carries a stable key (used for persistence), a display name
 * resource, and one built-in sound per scheme variant:
 *   variant 0 - "default" (天坦默认)
 *   variant 1 - "apple"   (苹果)
 *   variant 2 - "tatans"  (天坦)
 *
 * Events that only ship with a single sound reuse it for every variant.
 */
enum class SoundEvent(
    /** Stable persistence key for this event. */
    val key: String,
    val nameResId: Int,
    private val defaultResId: Int,
    private val appleResId: Int,
    private val tatansResId: Int
) {

    FOCUS("focus", R.string.sound_name_focus,
            R.raw.tb_focus, R.raw.tb_focus_apple, R.raw.tb_focus_tatans),
    ACTIONABLE_FOCUS("actionable_focus", R.string.sound_name_actionable_focus,
            R.raw.tb_focus_actionable, R.raw.tb_focus_actionable_apple, R.raw.tb_focus_actionable_tatans),
    SCROLL_TONE("scroll_tone", R.string.sound_name_scroll_tone,
            R.raw.tb_scroll_tone, R.raw.tb_scroll_tone_apple, R.raw.tb_scroll_tone_tatans),
    TICK("tick", R.string.sound_name_tick,
            R.raw.tb_tick, R.raw.tb_tick_apple, R.raw.tb_tick_tatans),
    VIEW_ENTERED("view_entered", R.string.sound_name_view_entered,
            R.raw.tb_view_entered, R.raw.tb_view_entered_apple, R.raw.tb_view_entered_tatans),
    WINDOW_STATE("window_state", R.string.sound_name_window_state,
            R.raw.tb_window_state, R.raw.tb_window_state_apple, R.raw.tb_window_state_tatans),
    WINDOW_NAVIGATION("window_navigation", R.string.sound_name_window_navigation,
            R.raw.tb_window_navigation, R.raw.tb_window_navigation, R.raw.tb_window_navigation),
    GESTURE_END("gesture_end", R.string.sound_name_gesture_end,
            R.raw.tb_gesture_end, R.raw.tb_gesture_end_apple, R.raw.tb_gesture_end),
    HYPERLINK("hyperlink", R.string.sound_name_hyperlink,
            R.raw.tb_hyperlink, R.raw.tb_hyperlink_apple, R.raw.tb_hyperlink_tatans),
    LONG_CLICKED("long_clicked", R.string.sound_name_long_clicked,
            R.raw.tb_long_clicked, R.raw.tb_long_clicked_apple, R.raw.tb_long_clicked_tatans),
    CHIME_UP("chime_up", R.string.sound_name_chime_up,
            R.raw.tb_chime_up, R.raw.tb_chime_up_apple, R.raw.tb_chime_up_tatans),
    CHIME_DOWN("chime_down", R.string.sound_name_chime_down,
            R.raw.tb_chime_down, R.raw.tb_chime_down_apple, R.raw.tb_chime_down_tatans),
    COMPLETE("complete", R.string.sound_name_complete,
            R.raw.tb_complete, R.raw.tb_complete_apple, R.raw.tb_complete_tatans),

    // Events that share a single built-in sound across every variant.
    TOAST("toast", R.string.sound_name_toast, R.raw.tb_toast, R.raw.tb_toast, R.raw.tb_toast),
    COPY_SUCCESS("copy_success", R.string.sound_name_copy_success,
            R.raw.tb_copy_success, R.raw.tb_copy_success, R.raw.tb_copy_success),
    SCREENSHOT("screenshot", R.string.sound_name_screenshot,
            R.raw.tb_screenshot, R.raw.tb_screenshot, R.raw.tb_screenshot),
    KEY("key", R.string.sound_name_key, R.raw.tb_key, R.raw.tb_key, R.raw.tb_key),
    EMOJI("emoji", R.string.sound_name_emoji, R.raw.tb_emoji, R.raw.tb_emoji, R.raw.tb_emoji),
    IMAGE_CAPTION("image_caption", R.string.sound_name_image_caption,
            R.raw.tb_image_caption, R.raw.tb_image_caption, R.raw.tb_image_caption),
    LOADING("loading", R.string.sound_name_loading,
            R.raw.tb_loading, R.raw.tb_loading, R.raw.tb_loading),
    RECOGNIZE_COMPLETE("recognize_complete", R.string.sound_name_recognize_complete,
            R.raw.tb_recognize_complete, R.raw.tb_recognize_complete, R.raw.tb_recognize_complete),
    DOUBLE_CLICK("double_click", R.string.sound_name_double_click,
            R.raw.tb_double_click, R.raw.tb_double_click, R.raw.tb_double_click),
    CUSTOM_ACTIONS("custom_actions", R.string.sound_name_custom_actions,
            R.raw.tb_custom_actions, R.raw.tb_custom_actions, R.raw.tb_custom_actions),
    GESTURE_BEGIN("gesture_begin", R.string.sound_name_gesture_begin,
            R.raw.tb_gesture_begin, R.raw.tb_gesture_begin, R.raw.tb_gesture_begin),
    SILENCE("silence", R.string.sound_name_silence,
            R.raw.tb_silence, R.raw.tb_silence, R.raw.tb_silence),
    SCREEN_LOCK("screen_lock", R.string.sound_name_screen_lock,
            R.raw.tb_screen_lock, R.raw.tb_screen_lock, R.raw.tb_screen_lock),
    DING_FOCUS("ding_focus", R.string.sound_name_ding_focus,
            R.raw.tb_ding_focus, R.raw.tb_ding_focus, R.raw.tb_ding_focus),
    ADD_SUCCESS("add_success", R.string.sound_name_add_success,
            R.raw.tb_add_success, R.raw.tb_add_success, R.raw.tb_add_success);

    /** @return the built-in resource id for the given variant, or -1 if none. */
    fun getResForVariant(variant: Int): Int {
        return when (variant) {
            SoundScheme.VARIANT_APPLE -> appleResId
            SoundScheme.VARIANT_TATANS -> tatansResId
            else -> defaultResId
        }
    }

    companion object {
        /** Looks up an event by its stable key. */
        @JvmStatic
        fun fromKey(key: String): SoundEvent? {
            return values().firstOrNull { it.key == key }
        }
    }
}
