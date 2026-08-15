/*
 * Copyright (C) 2026 林夕读屏 (Lingxi Screen Reader)
 */

package com.googlecode.eyesfree.brailleback.sound;

/**
 * Per-event customization state inside a sound scheme.
 *
 * A null or default entry means "use the scheme's built-in sound".
 * An entry is only persisted when the user has customized it:
 *   - {@code enabled == false} silences the event;
 *   - a non-empty {@code path} plays the given audio file instead of the
 *     built-in sound (only meaningful while {@code enabled == true}).
 */
public class SoundEntry {
    private boolean mEnabled;
    private String mPath;

    public SoundEntry() {
        this(true, null);
    }

    public SoundEntry(boolean enabled, String path) {
        mEnabled = enabled;
        mPath = path;
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /** @return the custom audio file path, or null to use the built-in sound. */
    public String getPath() {
        return mPath;
    }

    public void setPath(String path) {
        mPath = path;
    }
}
