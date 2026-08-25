/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 精简后的音效管理器（Kotlin 版）：仅保留内置方案的播放能力
 * （总开关 + 音量 + 事件音效），移除用户自建方案、方案编辑、JSON 持久化等
 * 自定义功能。
 */

package com.googlecode.eyesfree.brailleback.sound

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.googlecode.eyesfree.brailleback.R
import com.googlecode.eyesfree.utils.FeedbackController

/**
 * Singleton manager for the built-in earcon playback.
 *
 * Built-in schemes:
 *   - "default" 天坦默认 (variant 0)
 *   - "apple"   苹果     (variant 1)
 *   - "tatans"  天坦     (variant 2)
 *
 * The active scheme can only be one of the built-ins; the master switch and
 * volume are shared with the XML preferences (pref_sound_enabled / pref_sound_volume).
 */
class SoundSchemeManager private constructor(context: Context) {

    private val mPrefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    private val mBuiltInSchemes: List<SoundScheme> = listOf(
        SoundScheme("default",
            context.getString(R.string.sound_scheme_default),
            SoundScheme.VARIANT_DEFAULT, false),
        SoundScheme("apple",
            context.getString(R.string.sound_scheme_apple),
            SoundScheme.VARIANT_APPLE, false),
        SoundScheme("tatans",
            context.getString(R.string.sound_scheme_tatans),
            SoundScheme.VARIANT_TATANS, false)
    )

    private var mActiveScheme: SoundScheme

    init {
        val activeKey = mPrefs.getString(KEY_ACTIVE, "default") ?: "default"
        mActiveScheme = findBuiltIn(activeKey) ?: mBuiltInSchemes[0]
    }

    companion object {
        private const val KEY_ENABLED = "pref_sound_enabled"
        private const val KEY_VOLUME = "pref_sound_volume"
        private const val KEY_ACTIVE = "pref_sound_scheme"
        private const val DEFAULT_VOLUME = 50

        @Volatile
        private var sInstance: SoundSchemeManager? = null

        @JvmStatic
        fun getInstance(context: Context): SoundSchemeManager {
            var instance = sInstance
            if (instance == null) {
                synchronized(SoundSchemeManager::class.java) {
                    instance = sInstance
                    if (instance == null) {
                        instance = SoundSchemeManager(context)
                        sInstance = instance
                    }
                }
            }
            return instance!!
        }
    }

    /** All built-in schemes, in display order. */
    fun getBuiltInSchemes(): List<SoundScheme> {
        return ArrayList(mBuiltInSchemes)
    }

    fun getActiveScheme(): SoundScheme {
        return mActiveScheme
    }

    fun setActiveScheme(key: String) {
        findBuiltIn(key)?.let {
            mActiveScheme = it
            mPrefs.edit().putString(KEY_ACTIVE, key).apply()
        }
    }

    private fun findBuiltIn(key: String): SoundScheme? {
        return mBuiltInSchemes.firstOrNull { it.key == key }
    }

    // ------------------------------------------------------------------
    // Master controls
    // ------------------------------------------------------------------

    fun isEnabled(): Boolean {
        return mPrefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(enabled: Boolean) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getVolume(): Int {
        return mPrefs.getInt(KEY_VOLUME, DEFAULT_VOLUME)
    }

    fun setVolume(volume: Int) {
        mPrefs.edit().putInt(KEY_VOLUME, Math.max(0, Math.min(100, volume))).apply()
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    /**
     * Resolves what should be played for an event in a scheme.
     *
     * @return the resolved sound, or null if the event is silent.
     */
    fun resolve(scheme: SoundScheme?, event: SoundEvent?): ResolvedSound? {
        if (scheme == null || event == null) {
            return null
        }
        val resId = event.getResForVariant(scheme.variant)
        return if (resId > 0) ResolvedSound(resId) else null
    }

    /** @return true if the event will make a sound under the given scheme. */
    fun isEventAudible(scheme: SoundScheme?, event: SoundEvent?): Boolean {
        return resolve(scheme, event) != null
    }

    /**
     * Plays the earcon for an event in the active scheme (if earcons are
     * enabled and the event is audible).
     */
    fun play(controller: FeedbackController?, event: SoundEvent?) {
        if (controller == null || !isEnabled() || event == null) {
            return
        }
        val sound = resolve(mActiveScheme, event) ?: return
        controller.setVolume(getVolume())
        controller.playSound(sound.resId)
    }

    /** A resolved sound is a built-in resource id. */
    class ResolvedSound internal constructor(val resId: Int)
}
