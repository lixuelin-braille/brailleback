/*
 * Copyright (C) 2026 林夕读屏 (Lingxi Screen Reader)
 *
 * Scheme-based sound effect manager. Allows the user to switch between
 * built-in sound schemes, adjust the earcon volume, toggle all earcons and
 * customize individual event sounds (enable/disable and pick a custom audio
 * file). User schemes and per-event overrides are persisted as JSON in
 * SharedPreferences.
 */

package com.googlecode.eyesfree.brailleback.sound;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.googlecode.eyesfree.brailleback.R;
import com.googlecode.eyesfree.utils.FeedbackController;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Singleton manager for the earcon scheme system.
 *
 * Built-in schemes:
 *   - "default" 天坦默认 (variant 0)
 *   - "apple"   苹果     (variant 1)
 *   - "tatans"  天坦     (variant 2)
 *
 * Users can also create their own schemes derived from any built-in scheme,
 * and can override individual events of any scheme (including built-ins).
 */
public class SoundSchemeManager {

    private static final String TAG = "SoundSchemeManager";

    // Uses the app default SharedPreferences so the XML preferences (switch/volume) share state.
    private static final String KEY_ENABLED = "pref_sound_enabled";
    private static final String KEY_VOLUME = "pref_sound_volume";
    private static final String KEY_ACTIVE = "pref_sound_scheme";
    private static final String KEY_JSON = "pref_sound_schemes_json";

    private static final int DEFAULT_VOLUME = 50;

    private static volatile SoundSchemeManager sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;

    /** Built-in schemes in display order. */
    private final List<SoundScheme> mBuiltInSchemes = new ArrayList<SoundScheme>();
    /** User created schemes. */
    private final List<SoundScheme> mUserSchemes = new ArrayList<SoundScheme>();

    private SoundScheme mActiveScheme;

    private SoundSchemeManager(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mBuiltInSchemes.add(new SoundScheme("default", context.getString(R.string.sound_scheme_default), SoundScheme.VARIANT_DEFAULT, false));
        mBuiltInSchemes.add(new SoundScheme("apple", context.getString(R.string.sound_scheme_apple), SoundScheme.VARIANT_APPLE, false));
        mBuiltInSchemes.add(new SoundScheme("tatans", context.getString(R.string.sound_scheme_tatans), SoundScheme.VARIANT_TATANS, false));
        loadPersistedState();
    }

    public static SoundSchemeManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (SoundSchemeManager.class) {
                if (sInstance == null) {
                    sInstance = new SoundSchemeManager(context);
                }
            }
        }
        return sInstance;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void loadPersistedState() {
        // Built-in scheme overrides.
        try {
            String json = mPrefs.getString(KEY_JSON, null);
            if (!TextUtils.isEmpty(json)) {
                JSONObject root = new JSONObject(json);
                JSONObject overrides = root.optJSONObject("overrides");
                if (overrides != null) {
                    Iterator<String> keys = overrides.keys();
                    while (keys.hasNext()) {
                        String schemeKey = keys.next();
                        SoundScheme scheme = findBuiltIn(schemeKey);
                        if (scheme != null) {
                            applyJsonOverrides(scheme, overrides.optJSONObject(schemeKey));
                        }
                    }
                }
                JSONArray userArr = root.optJSONArray("userSchemes");
                if (userArr != null) {
                    for (int i = 0; i < userArr.length(); i++) {
                        JSONObject obj = userArr.optJSONObject(i);
                        if (obj == null) {
                            continue;
                        }
                        String key = obj.optString("key");
                        String name = obj.optString("name");
                        int variant = obj.optInt("variant", SoundScheme.VARIANT_DEFAULT);
                        SoundScheme scheme = new SoundScheme(key, name, variant, true);
                        applyJsonOverrides(scheme, obj.optJSONObject("entries"));
                        mUserSchemes.add(scheme);
                    }
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse sound scheme JSON; using defaults", e);
        }

        String activeKey = mPrefs.getString(KEY_ACTIVE, "default");
        mActiveScheme = findScheme(activeKey);
        if (mActiveScheme == null) {
            mActiveScheme = findBuiltIn("default");
        }
    }

    private void applyJsonOverrides(SoundScheme scheme, JSONObject entries) {
        if (entries == null) {
            return;
        }
        Iterator<String> keys = entries.keys();
        while (keys.hasNext()) {
            String eventKey = keys.next();
            SoundEvent event = SoundEvent.fromKey(eventKey);
            JSONObject entry = entries.optJSONObject(eventKey);
            if (event == null || entry == null) {
                continue;
            }
            boolean enabled = entry.optBoolean("enabled", true);
            String path = entry.optString("path", null);
            scheme.setOverride(event, new SoundEntry(enabled, TextUtils.isEmpty(path) ? null : path));
        }
    }

    private void persist() {
        try {
            JSONObject root = new JSONObject();
            JSONObject overrides = new JSONObject();
            for (SoundScheme scheme : mBuiltInSchemes) {
                if (!scheme.getOverrides().isEmpty()) {
                    overrides.put(scheme.getKey(), toJsonOverrides(scheme));
                }
            }
            if (overrides.length() > 0) {
                root.put("overrides", overrides);
            }
            JSONArray userArr = new JSONArray();
            for (SoundScheme scheme : mUserSchemes) {
                JSONObject obj = new JSONObject();
                obj.put("key", scheme.getKey());
                obj.put("name", scheme.getName());
                obj.put("variant", scheme.getVariant());
                obj.put("entries", toJsonOverrides(scheme));
                userArr.put(obj);
            }
            root.put("userSchemes", userArr);
            mPrefs.edit()
                    .putString(KEY_JSON, root.toString())
                    .putString(KEY_ACTIVE, mActiveScheme.getKey())
                    .apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to persist sound schemes", e);
        }
    }

    private JSONObject toJsonOverrides(SoundScheme scheme) throws JSONException {
        JSONObject entries = new JSONObject();
        for (java.util.Map.Entry<String, SoundEntry> override : scheme.getOverrides().entrySet()) {
            JSONObject entry = new JSONObject();
            entry.put("enabled", override.getValue().isEnabled());
            if (!TextUtils.isEmpty(override.getValue().getPath())) {
                entry.put("path", override.getValue().getPath());
            }
            entries.put(override.getKey(), entry);
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public List<SoundScheme> getBuiltInSchemes() {
        return new ArrayList<SoundScheme>(mBuiltInSchemes);
    }

    public List<SoundScheme> getUserSchemes() {
        return new ArrayList<SoundScheme>(mUserSchemes);
    }

    /** All schemes, built-ins first, in display order. */
    public List<SoundScheme> getAllSchemes() {
        List<SoundScheme> all = new ArrayList<SoundScheme>(mBuiltInSchemes);
        all.addAll(mUserSchemes);
        return all;
    }

    public SoundScheme getActiveScheme() {
        return mActiveScheme;
    }

    private SoundScheme findScheme(String key) {
        SoundScheme scheme = findBuiltIn(key);
        if (scheme != null) {
            return scheme;
        }
        for (SoundScheme user : mUserSchemes) {
            if (user.getKey().equals(key)) {
                return user;
            }
        }
        return null;
    }

    private SoundScheme findBuiltIn(String key) {
        for (SoundScheme scheme : mBuiltInSchemes) {
            if (scheme.getKey().equals(key)) {
                return scheme;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Master controls
    // ------------------------------------------------------------------

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public int getVolume() {
        return mPrefs.getInt(KEY_VOLUME, DEFAULT_VOLUME);
    }

    public void setVolume(int volume) {
        mPrefs.edit().putInt(KEY_VOLUME, Math.max(0, Math.min(100, volume))).apply();
    }

    // ------------------------------------------------------------------
    // Scheme operations
    // ------------------------------------------------------------------

    public void setActiveScheme(String key) {
        SoundScheme scheme = findScheme(key);
        if (scheme != null) {
            mActiveScheme = scheme;
            persist();
        }
    }

    /**
     * Creates a new user scheme derived from the given base scheme.
     *
     * @return the new scheme, or null if the name already exists.
     */
    public SoundScheme createScheme(String name, String baseKey) {
        SoundScheme base = findScheme(baseKey);
        if (base == null) {
            return null;
        }
        for (SoundScheme existing : getAllSchemes()) {
            if (existing.getName().equals(name)) {
                return null;
            }
        }
        String key = "user_" + System.currentTimeMillis();
        SoundScheme scheme = new SoundScheme(key, name, base.getVariant(), true);
        mUserSchemes.add(scheme);
        persist();
        return scheme;
    }

    public boolean renameScheme(String key, String newName) {
        SoundScheme scheme = findScheme(key);
        if (scheme == null || scheme.isUserScheme() == false) {
            return false;
        }
        // Reflect new name on the object by copying it (SoundScheme name is final).
        mUserSchemes.remove(scheme);
        SoundScheme renamed = new SoundScheme(scheme.getKey(), newName, scheme.getVariant(), true);
        renamed.getOverrides().putAll(scheme.getOverrides());
        mUserSchemes.add(renamed);
        persist();
        return true;
    }

    public boolean deleteScheme(String key) {
        SoundScheme scheme = findScheme(key);
        if (scheme == null || !scheme.isUserScheme()) {
            return false;
        }
        mUserSchemes.remove(scheme);
        if (mActiveScheme == scheme) {
            mActiveScheme = findBuiltIn("default");
        }
        persist();
        return true;
    }

    /** Resets every per-event override of a scheme back to its built-in sound. */
    public void resetScheme(String key) {
        SoundScheme scheme = findScheme(key);
        if (scheme == null) {
            return;
        }
        scheme.getOverrides().clear();
        persist();
    }

    // ------------------------------------------------------------------
    // Per-event customization
    // ------------------------------------------------------------------

    public void setEventEnabled(SoundScheme scheme, SoundEvent event, boolean enabled) {
        SoundEntry entry = scheme.getOverride(event);
        if (entry == null) {
            entry = new SoundEntry(enabled, null);
        } else {
            entry.setEnabled(enabled);
        }
        scheme.setOverride(event, entry);
        persist();
    }

    public void setEventPath(SoundScheme scheme, SoundEvent event, String path) {
        SoundEntry entry = scheme.getOverride(event);
        if (entry == null) {
            entry = new SoundEntry(true, path);
        } else {
            entry.setPath(TextUtils.isEmpty(path) ? null : path);
        }
        if (!TextUtils.isEmpty(path)) {
            entry.setEnabled(true);
        }
        scheme.setOverride(event, entry);
        persist();
    }

    /** @return true if the event will make a sound under the given scheme. */
    public boolean isEventAudible(SoundScheme scheme, SoundEvent event) {
        return resolve(scheme, event) != null;
    }

    /**
     * Resolves what should be played for an event in a scheme.
     *
     * @return the resolved sound, or null if the event is silent.
     */
    public ResolvedSound resolve(SoundScheme scheme, SoundEvent event) {
        if (scheme == null || event == null) {
            return null;
        }
        SoundEntry override = scheme.getOverride(event);
        if (override != null && !override.isEnabled()) {
            return null;
        }
        if (override != null && !TextUtils.isEmpty(override.getPath())) {
            return new ResolvedSound(override.getPath());
        }
        int resId = event.getResForVariant(scheme.getVariant());
        if (resId > 0) {
            return new ResolvedSound(resId);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    /**
     * Plays the earcon for an event in the active scheme (if earcons are
     * enabled and the event is audible).
     */
    public void play(FeedbackController controller, SoundEvent event) {
        if (controller == null || !isEnabled() || event == null) {
            return;
        }
        ResolvedSound sound = resolve(mActiveScheme, event);
        if (sound == null) {
            return;
        }
        if (sound.hasPath()) {
            controller.playSoundPath(sound.getPath(), getVolume() / 100.0f);
        } else {
            controller.setVolume(getVolume());
            controller.playSound(sound.getResId());
        }
    }

    /** Plays a sound for a specific scheme/event (used by the scheme editor). */
    public void playPreview(SoundScheme scheme, SoundEvent event, FeedbackController controller) {
        if (controller == null || event == null) {
            return;
        }
        ResolvedSound sound = resolve(scheme, event);
        if (sound == null) {
            return;
        }
        if (sound.hasPath()) {
            controller.playSoundPath(sound.getPath(), 1.0f);
        } else {
            controller.playSound(sound.getResId());
        }
    }

    /** A resolved sound is either a built-in resource id or a file path. */
    public static class ResolvedSound {
        private final int mResId;
        private final String mPath;

        ResolvedSound(int resId) {
            mResId = resId;
            mPath = null;
        }

        ResolvedSound(String path) {
            mResId = -1;
            mPath = path;
        }

        public int getResId() {
            return mResId;
        }

        public String getPath() {
            return mPath;
        }

        public boolean hasPath() {
            return mPath != null;
        }
    }
}
