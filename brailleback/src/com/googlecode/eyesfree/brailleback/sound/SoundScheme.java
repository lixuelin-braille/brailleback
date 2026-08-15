/*
 * Copyright (C) 2026 林夕读屏 (Lingxi Screen Reader)
 */

package com.googlecode.eyesfree.brailleback.sound;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named sound scheme: a set of per-event earcons that can be switched as a
 * whole. Built-in schemes map to a variant of bundled resources; user schemes
 * derive from a base variant and layer their own per-event overrides on top.
 */
public class SoundScheme {

    /** Built-in resource variant: 天坦默认. */
    public static final int VARIANT_DEFAULT = 0;
    /** Built-in resource variant: 苹果. */
    public static final int VARIANT_APPLE = 1;
    /** Built-in resource variant: 天坦. */
    public static final int VARIANT_TATANS = 2;

    private final String mKey;
    private final String mName;
    private final int mVariant;
    private final boolean mUserScheme;
    private final Map<String, SoundEntry> mOverrides = new LinkedHashMap<String, SoundEntry>();

    public SoundScheme(String key, String name, int variant, boolean userScheme) {
        mKey = key;
        mName = name;
        mVariant = variant;
        mUserScheme = userScheme;
    }

    public String getKey() {
        return mKey;
    }

    public String getName() {
        return mName;
    }

    public int getVariant() {
        return mVariant;
    }

    public boolean isUserScheme() {
        return mUserScheme;
    }

    /** Override entries for the events the user customized in this scheme. */
    public Map<String, SoundEntry> getOverrides() {
        return mOverrides;
    }

    public SoundEntry getOverride(SoundEvent event) {
        return mOverrides.get(event.getKey());
    }

    public void setOverride(SoundEvent event, SoundEntry entry) {
        if (entry == null) {
            mOverrides.remove(event.getKey());
        } else {
            mOverrides.put(event.getKey(), entry);
        }
    }
}
