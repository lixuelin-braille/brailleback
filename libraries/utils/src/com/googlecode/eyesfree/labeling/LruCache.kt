/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.labeling

import java.util.LinkedHashMap
import java.util.Map

/**
 * LRU-like cache used for in-memory representation of custom labels.
 */
class LruCache<K, V>(private val maxEntries: Int) :
    LinkedHashMap<K, V>(maxEntries + 1, 1.0f, true) {

    /**
     * This method does not modify the underlying structure.
     */
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
        return size > maxEntries
    }
}
