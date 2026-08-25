/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.utils

import android.content.Context
import android.text.TextUtils
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding
import com.googlecode.eyesfree.brailleback.R
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator

/**
 * Utility methods for working with [BrailleKeyBinding] (Kotlin 版).
 */
object BrailleKeyBindingUtils {

    /**
     * Returns a sorted list of bindings supported by the display.
     */
    @JvmStatic
    fun getSortedBindingsForDisplay(props: BrailleDisplayProperties): ArrayList<BrailleKeyBinding> {
        val bindings = props.getKeyBindings()
        val sortedBindings = ArrayList<BrailleKeyBinding>(bindings.toList())
        Collections.sort(sortedBindings, COMPARE_BINDINGS)
        return sortedBindings
    }

    /**
     * Returns the binding that matches the specified command in the sorted list
     * of BrailleKeyBindings. Returns null if not found.
     */
    @JvmStatic
    fun getBrailleKeyBindingForCommand(
        command: Int, sortedBindings: ArrayList<BrailleKeyBinding>): BrailleKeyBinding? {
        val dummyBinding = BrailleKeyBinding()
        dummyBinding.setCommand(command)
        var index = Collections.binarySearch(sortedBindings, dummyBinding, COMPARE_BINDINGS_BY_COMMAND)
        if (index < 0) {
            return null
        }
        while (index > 0 && sortedBindings[index - 1].getCommand() == command) {
            index -= 1
        }
        return sortedBindings[index]
    }

    /**
     * Returns the friendly name for the specified key binding.
     */
    @JvmStatic
    fun getFriendlyKeyNamesForCommand(
        binding: BrailleKeyBinding,
        friendlyKeyNames: Map<String, String>,
        context: Context): String {
        val delimiter = context.getString(R.string.help_keyBindingDelimiter)
        var keys = TextUtils.join(delimiter, getFriendlyKeyNames(binding.getKeyNames(), friendlyKeyNames))
        if (binding.isLongPress()) {
            keys = context.getString(R.string.help_longPressTemplate, keys)
        }
        return keys
    }

    /**
     * Returns friendly key names (if available) based on the map.
     */
    @JvmStatic
    fun getFriendlyKeyNames(
        unfriendlyNames: Array<String>, friendlyNames: Map<String, String>): Array<String> {
        val result = arrayOfNulls<String>(unfriendlyNames.size)
        for (i in unfriendlyNames.indices) {
            val friendlyName = friendlyNames[unfriendlyNames[i]]
            if (friendlyName != null) {
                result[i] = friendlyName
            } else {
                result[i] = unfriendlyNames[i]
            }
        }
        @Suppress("UNCHECKED_CAST")
        return result as Array<String>
    }

    /**
     * Compares key bindings by command number, then in an order that is
     * deterministic and that makes sure that the binding that should appear on
     * the help screen comes first.
     */
    @JvmField
    val COMPARE_BINDINGS: Comparator<BrailleKeyBinding> =
        Comparator<BrailleKeyBinding> { lhs, rhs ->
            val command1 = lhs.getCommand()
            val command2 = rhs.getCommand()
            if (command1 != command2) {
                command1 - command2
            } else {
                // Prefer a binding without long press.
                val longPress1 = lhs.isLongPress()
                val longPress2 = rhs.isLongPress()
                if (longPress1 != longPress2) {
                    if (longPress1) 1 else -1
                } else {
                    val names1 = lhs.getKeyNames()
                    val names2 = rhs.getKeyNames()
                    // Prefer fewer keys.
                    if (names1.size != names2.size) {
                        names1.size - names2.size
                    } else {
                        // Compare key names for determinism.
                        for (i in names1.indices) {
                            val res = names1[i].compareTo(names2[i])
                            if (res != 0) {
                                return@Comparator res
                            }
                        }
                        0
                    }
                }
            }
        }

    /**
     * Compares key bindings by command number.  Used for search.
     */
    @JvmField
    val COMPARE_BINDINGS_BY_COMMAND: Comparator<BrailleKeyBinding> =
        Comparator<BrailleKeyBinding> { lhs, rhs ->
            lhs.getCommand() - rhs.getCommand()
        }
}
