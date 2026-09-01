/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.googlecode.eyesfree.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.Engine
import android.text.TextUtils
import com.googlecode.eyesfree.compat.provider.SettingsCompatUtils.SecureCompatUtils
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.Locale

/**
 * Support class for querying the list of available engines on the device and
 * deciding which one to use etc.
 *
 * Comments in this class the use the shorthand "system engines" for engines
 * that are a part of the system image.
 *
 * Based on hidden framework class `android.speech.tts.TtsEngines`.
 */
object TtsEngineUtils {
    /** The default delimiter for [Locale] strings. */
    private const val LOCALE_DELIMITER = "-"

    /**
     * Returns the package name of the default TTS engine. If the user has set
     * a default, and the engine is available on the device, the default is
     * returned. Otherwise, the highest ranked engine is returned. If no system
     * engine is present, returns `null`.
     *
     * @return the package name of the default TTS engine, or `null` if no
     *         default or system engine is present.
     */
    @JvmStatic
    fun getDefaultEngine(context: Context): String? {
        val resolver = context.contentResolver
        val defaultEngine = Settings.Secure.getString(
            resolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        if (isEngineInstalled(context, defaultEngine)) {
            return defaultEngine
        }

        // Fall back on the highest-ranked system engine.
        val engine = getHighestRankedEngine(context) ?: return null
        return engine.name
    }

    /**
     * Returns the default locale for a given TTS engine. Attempts to read the
     * value from [SecureCompatUtils.TTS_DEFAULT_LOCALE], failing which the old
     * style value from [Settings.Secure.TTS_DEFAULT_LANG] is read. If both
     * these values are empty, the default phone locale is returned.
     *
     * @param engineName the engine to return the locale for.
     * @return the locale string preference for this engine. Will be non null
     *         and non empty.
     */
    @JvmStatic
    fun getDefaultLocaleForEngine(context: Context, engineName: String): Locale {
        val resolver = context.contentResolver
        val defaultLocalePref = Settings.Secure.getString(
            resolver, SecureCompatUtils.TTS_DEFAULT_LOCALE)
        val locale = parseEngineLocalePrefFromList(defaultLocalePref, engineName)
        if (locale != null) {
            return locale
        }

        // If the new-style setting is not set, return the old-style setting.
        return getV1Locale(resolver)
    }

    /**
     * Gets a list of all installed TTS engines sorted by priority (see
     * [ENGINE_PRIORITY_COMPARATOR]).
     *
     * @return A sorted list of engine info objects. The list can be empty, but
     *         never `null`.
     */
    @JvmStatic
    fun getEngines(context: Context): List<TtsEngineInfo> {
        val pm = context.packageManager
        val intent = Intent(Engine.INTENT_ACTION_TTS_SERVICE)
        val resolveInfos = pm.queryIntentServices(
            intent, PackageManager.MATCH_DEFAULT_ONLY)
        val engines = ArrayList<TtsEngineInfo>(resolveInfos.size)

        for (resolveInfo in resolveInfos) {
            val engine = getEngineInfo(resolveInfo, pm)
            if (engine != null) {
                engines.add(engine)
            }
        }

        Collections.sort(engines, ENGINE_PRIORITY_COMPARATOR)

        return Collections.unmodifiableList(engines)
    }

    /**
     * Returns whether speech synthesis using a specific language on the
     * specified TTS requires a network connection.
     *
     * @param tts The TTS engine to check.
     * @param language The language to check.
     * @return `true` if the language requires a network connection.
     */
    @JvmStatic
    fun isNetworkRequiredForSynthesis(tts: TextToSpeech, language: Locale): Boolean {
        val features = tts.getFeatures(language)
        return features.contains(Engine.KEY_FEATURE_NETWORK_SYNTHESIS) &&
            !features.contains(Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
    }

    /**
     * Parses the contents of [Engine.EXTRA_AVAILABLE_VOICES] and returns a
     * unmodifiable list of [Locale]s sorted by display name. See
     * [LOCALE_COMPARATOR] for sorting information.
     *
     * @param availableLanguages A list of locale strings in the form
     *            `language-country-variant`.
     * @return A sorted, unmodifiable list of [Locale]s.
     */
    @JvmStatic
    fun parseAvailableLanguages(availableLanguages: List<String>): List<Locale> {
        val results = ArrayList<Locale>(availableLanguages.size)

        for (availableLang in availableLanguages) {
            val langCountryVar = availableLang.split(LOCALE_DELIMITER)
            val loc: Locale?
            when (langCountryVar.size) {
                1 -> loc = Locale(langCountryVar[0])
                2 -> loc = Locale(langCountryVar[0], langCountryVar[1])
                3 -> loc = Locale(langCountryVar[0], langCountryVar[1], langCountryVar[2])
                else -> continue
            }
            results.add(loc)
        }

        // Sort by display name, ascending case-insensitive.
        Collections.sort(results, LOCALE_COMPARATOR)

        return Collections.unmodifiableList(results)
    }

    /**
     * Returns the engine info for a given engine name. Note that engines are
     * identified by their package name.
     */
    @JvmStatic
    fun getEngineInfo(context: Context, packageName: String?): TtsEngineInfo? {
        if (packageName == null) {
            return null
        }

        val pm = context.packageManager
        val intent = Intent(Engine.INTENT_ACTION_TTS_SERVICE).setPackage(packageName)
        val resolveInfos = pm.queryIntentServices(
            intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfos.isEmpty()) {
            return null
        }

        // Note that the current API allows only one engine per package name.
        // Since the "engine name" is the same as the package name.
        return getEngineInfo(resolveInfos[0], pm)
    }

    /**
     * @return true if a given engine is installed on the system.
     */
    private fun isEngineInstalled(context: Context, engine: String?): Boolean {
        if (engine == null) {
            return false
        }
        return getEngineInfo(context, engine) != null
    }

    /**
     * @return information for the highest ranked system engine or `null` if no
     *         TTS engines were present in the system image.
     */
    private fun getHighestRankedEngine(context: Context): TtsEngineInfo? {
        val sortedEngines = getEngines(context)
        for (engine in sortedEngines) {
            if (engine.system) {
                return engine
            }
        }
        return null
    }

    private fun getEngineInfo(resolve: ResolveInfo, pm: PackageManager): TtsEngineInfo? {
        val service = resolve.serviceInfo ?: return null

        val engine = TtsEngineInfo()

        // Using just the package name isn't great, since it disallows having
        // multiple engines in the same package, but that's what the existing
        // API does.
        engine.name = service.packageName

        val label = service.loadLabel(pm)
        engine.label = if (TextUtils.isEmpty(label)) engine.name else label.toString()
        engine.icon = service.iconResource
        engine.priority = resolve.priority
        engine.system = isSystemEngine(service)

        return engine
    }

    private fun isSystemEngine(info: ServiceInfo): Boolean {
        val appInfo = info.applicationInfo ?: return false
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    /**
     * @return the old style locale string constructed from
     *         [Settings.Secure.TTS_DEFAULT_LANG],
     *         [Settings.Secure.TTS_DEFAULT_COUNTRY] and
     *         [Settings.Secure.TTS_DEFAULT_VARIANT]. If no such locale is set,
     *         then return the default phone locale.
     */
    @Suppress("DEPRECATION")
    private fun getV1Locale(resolver: ContentResolver): Locale {
        val language = Settings.Secure.getString(
            resolver, Settings.Secure.TTS_DEFAULT_LANG)
        if (TextUtils.isEmpty(language)) {
            return Locale.getDefault()
        }

        val country = Settings.Secure.getString(
            resolver, Settings.Secure.TTS_DEFAULT_COUNTRY)
        val variant = Settings.Secure.getString(
            resolver, Settings.Secure.TTS_DEFAULT_VARIANT)
        val locale = constructLocaleString(language, country, variant)

        return Locale(locale)
    }

    private fun constructLocaleString(
        language: String?,
        country: String?,
        variant: String?
    ): String {
        val builder = StringBuilder(language)
        if (TextUtils.isEmpty(language)) {
            return builder.toString()
        }
        builder.append(language)
        if (TextUtils.isEmpty(country)) {
            return builder.toString()
        }
        builder.append(LOCALE_DELIMITER)
        builder.append(country)
        if (TextUtils.isEmpty(variant)) {
            return builder.toString()
        }
        builder.append(LOCALE_DELIMITER)
        builder.append(variant)
        return builder.toString()
    }

    /**
     * Parses a comma separated list of engine locale preferences. The list is
     * of the form `"engine_name_1:locale_1,engine_name_2:locale2"` and so on
     * and so forth. Returns null if the list is empty, malformed or if there
     * is no engine specific preference in the list.
     */
    private fun parseEngineLocalePrefFromList(prefValue: String?, engineName: String): Locale? {
        if (TextUtils.isEmpty(prefValue)) {
            return null
        }

        val prefValues = prefValue!!.split(",")
        for (value in prefValues) {
            val delimiter = value.indexOf(':')
            if (delimiter > 0) {
                if (engineName == value.substring(0, delimiter)) {
                    return Locale(value.substring(delimiter + 1))
                }
            }
        }
        return null
    }

    /**
     * Compares locales in case-insensitive ascending order based on their
     * display name.
     */
    @JvmField
    val LOCALE_COMPARATOR: Comparator<Locale> = Comparator { lhs, rhs ->
        val lhsName: String = lhs.getDisplayName()!!
        val rhsName: String = rhs.getDisplayName()!!
        lhsName.compareTo(rhsName, ignoreCase = true)
    }

    /**
     * Engines that are a part of the system image are always lesser than those
     * that are not. Within system engines / non system engines the engines are
     * sorted in order of their declared priority.
     */
    @JvmField
    val ENGINE_PRIORITY_COMPARATOR: Comparator<TtsEngineInfo> = Comparator { lhs, rhs ->
        if (lhs.system && !rhs.system) {
            -1
        } else if (rhs.system && !lhs.system) {
            1
        } else {
            // Either both system engines, or both non system engines. Note:
            // this isn't a typo. Higher priority numbers imply higher
            // priority, but are "lower" in the sort order.
            rhs.priority - lhs.priority
        }
    }

    /**
     * Information about an installed text-to-speech engine. Cloned from
     * [TextToSpeech.EngineInfo].
     *
     * @see TextToSpeech.getEngines
     * @see TextToSpeech.EngineInfo
     */
    class TtsEngineInfo {
        /** Engine package name. */
        var name: String? = null

        /** Localized label for the engine. */
        var label: String? = null

        /** Icon for the engine. */
        var icon: Int = 0

        /** Whether this engine is a part of the system image. */
        var system: Boolean = false

        /**
         * The priority the engine declares for the intent filter
         * `android.intent.action.TTS_SERVICE`.
         */
        var priority: Int = 0

        override fun toString(): String {
            return "TtsEngineInfo{name=" + name + "}"
        }
    }
}
