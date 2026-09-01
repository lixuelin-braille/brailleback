/*
 * Copyright (C) 2013 Google Inc.
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

package com.googlecode.eyesfree.brailleback

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.labeling.CustomLabelManager
import com.googlecode.eyesfree.labeling.Label
import com.googlecode.eyesfree.labeling.LabelOperationUtils
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoRef
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import java.util.ArrayList

/**
 * Navigation mode for going through menu items shown specially on the Braille
 * display. Not to be confused with navigation through normal on screen options
 * menu items. This is a menu that is shown only on the Braille display for
 * unlabeled items for adding, editing, or removing labels.
 */
class BrailleMenuNavigationMode(
    private val displayManager: DisplayManager,
    private val accessibilityService: AccessibilityService,
    private val feedbackManager: FeedbackManager,
    private val labelManager: CustomLabelManager,
    private val brailleMenuListener: BrailleMenuListener
) : NavigationMode {

    companion object {
        private const val TALKBACK_URL_PLAYSTORE =
            "market://details?id=com.google.android.marvin.talkback"
        private const val TALKBACK_URL_WEB =
            "https://play.google.com/store/apps/details?id=com.google.android.marvin.talkback&hl=en"
        private const val SCROLL_MENU_FORWARD = 1
        private const val SCROLL_MENU_BACKWARD = -1
    }

    /** Listener for menu state. */
    interface BrailleMenuListener {
        fun onMenuClosed()
    }

    private val initialNode = AccessibilityNodeInfoRef()

    private var active = false
    private var menuItems: List<MenuItem>? = null
    private var currentIndex = 0

    override fun onActivate() {
        // No point in activating if labeling isn't supported on the OS.
        if (Build.VERSION.SDK_INT < CustomLabelManager.MIN_API_LEVEL) {
            closeMenu()
            return
        }

        active = true

        // If the currently focused node doesn't warrant a menu, deactivate.
        val focused = FocusFinder.getFocusedNode(accessibilityService, false)
        if (!showMenuForNode(focused)) {
            feedbackManager.emitFeedback(FeedbackManager.TYPE_COMMAND_FAILED)
            closeMenu()
            return
        }

        // Save the node.
        initialNode.reset(focused)

        brailleDisplayWithCurrentItem()
    }

    override fun onDeactivate() {
        active = false
        currentIndex = 0
        menuItems = null
        initialNode.clear()
    }

    override fun onObserveAccessibilityEvent(event: AccessibilityEvent) {
        // Nothing to do here.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                closeMenu()
                return true
            }
        }
        // Don't let fall through.
        return true
    }

    override fun onInvalidateAccessibilityNode(node: AccessibilityNodeInfoCompat) {
        // Nothing to do here.
    }

    override fun onPanLeftOverflow(content: DisplayManager.Content): Boolean {
        // When we overflow, want to move to the previous item, if it exists.
        return feedbackManager.emitOnFailure(
            scrollMenu(SCROLL_MENU_BACKWARD),
            FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    override fun onPanRightOverflow(content: DisplayManager.Content): Boolean {
        // When we overflow, want to move to the next item, if it exists.
        return feedbackManager.emitOnFailure(
            scrollMenu(SCROLL_MENU_FORWARD),
            FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    override fun onMappedInputEvent(
        event: BrailleInputEvent,
        content: DisplayManager.Content
    ): Boolean {
        when (event.command) {
            BrailleInputEvent.CMD_NAV_ITEM_NEXT,
            BrailleInputEvent.CMD_NAV_LINE_NEXT ->
                return feedbackManager.emitOnFailure(
                    scrollMenu(SCROLL_MENU_FORWARD),
                    FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
            BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS,
            BrailleInputEvent.CMD_NAV_LINE_PREVIOUS ->
                return feedbackManager.emitOnFailure(
                    scrollMenu(SCROLL_MENU_BACKWARD),
                    FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
            BrailleInputEvent.CMD_ACTIVATE_CURRENT,
            BrailleInputEvent.CMD_ROUTE,
            BrailleInputEvent.CMD_KEY_ENTER ->
                return feedbackManager.emitOnFailure(
                    activateCurrentMenuItem(),
                    FeedbackManager.TYPE_COMMAND_FAILED)
            else ->
                // Don't let other commands fall through.
                return true
        }
    }

    /** Return whether navigation mode is active. */
    fun isActive(): Boolean = active

    /** Activates the currently selected menu item. */
    fun activateCurrentMenuItem(): Boolean {
        // Grab the state we need before closing the menu, since that clears
        // out all state.
        val itemId = menuItems!![currentIndex].id
        val node = initialNode.release()
        return try {
            closeMenu()

            if (node == null) {
                return false
            }

            // This must be checked before we try to grab a label.
            if (itemId == R.string.menu_item_update_talkback) {
                return launchIntentToPlayStoreTalkBack()
            }

            val unwrapped = node.info as AccessibilityNodeInfo
            val existingLabel: Label? =
                labelManager.getLabelForViewIdFromCache(unwrapped.viewIdResourceName)

            when (itemId) {
                R.string.menu_item_label_add ->
                    return LabelOperationUtils.startActivityAddLabelForNode(
                        accessibilityService, unwrapped)
                R.string.menu_item_label_edit ->
                    return LabelOperationUtils.startActivityEditLabel(
                        accessibilityService, existingLabel)
                R.string.menu_item_label_remove ->
                    return LabelOperationUtils.startActivityRemoveLabel(
                        accessibilityService, existingLabel)
            }
            false
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(node)
        }
    }

    /** Fires an intent to open TalkBack in the Play store. */
    private fun launchIntentToPlayStoreTalkBack(): Boolean {
        return try {
            accessibilityService.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(TALKBACK_URL_PLAYSTORE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            accessibilityService.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(TALKBACK_URL_WEB))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    }

    /**
     * Displays a menu for the node and populates [menuItems]. If the node
     * doesn't warrant a menu does nothing. Returns whether a menu was shown.
     */
    private fun showMenuForNode(node: AccessibilityNodeInfoCompat?): Boolean {
        if (node == null) {
            return false
        }
        menuItems = getItemsForNode(node)
        return !menuItems!!.isEmpty()
    }

    /**
     * Calls the listener signaling that the menu has been closed and the
     * navigation mode should be exited.
     */
    private fun closeMenu() {
        brailleMenuListener.onMenuClosed()
    }

    /**
     * Scrolls the menu to the next item in the specified direction. Returns
     * whether the menu successfully scrolled. [direction] should be either
     * [SCROLL_MENU_FORWARD] or [SCROLL_MENU_BACKWARD].
     */
    private fun scrollMenu(direction: Int): Boolean {
        val items = menuItems ?: return false
        if (direction == SCROLL_MENU_FORWARD) {
            if (currentIndex + 1 >= items.size) {
                return false
            }
            currentIndex++
            brailleDisplayWithCurrentItem()
            return true
        } else if (direction == SCROLL_MENU_BACKWARD) {
            if (currentIndex == 0) {
                return false
            }
            currentIndex--
            brailleDisplayWithCurrentItem()
            return true
        }
        return false
    }

    /**
     * Updates the text on the braille display to match the currently selected
     * menu item.
     */
    private fun brailleDisplayWithCurrentItem() {
        val current = menuItems!![currentIndex]
        val content = DisplayManager.Content(current.name)
        content.setPanStrategy(DisplayManager.Content.PAN_RESET)
        displayManager.setContent(content)
    }

    /**
     * Returns a list of menu item strings to be shown for the specified node.
     * May be empty if no items needed (already labeled by developer).
     */
    private fun getItemsForNode(node: AccessibilityNodeInfoCompat): List<MenuItem> {
        val items = ArrayList<MenuItem>()
        val unwrapped = node.info as AccessibilityNodeInfo
        val hasDescription =
            !TextUtils.isEmpty(AccessibilityNodeInfoUtils.getNodeText(node))
        val parsedId = CustomLabelManager.splitResourceName(unwrapped.viewIdResourceName)
        val hasParseableId = parsedId != null

        // TODO: There are a number of views that have a different resource
        // namespace than their parent application. It's likely we'll need to
        // refine the database structure to accommodate these while also
        // allowing the user to modify them through TalkBack settings. For now,
        // we'll simply not allow labeling of such views.
        var isFromKnownApp = false
        if (hasParseableId) {
            try {
                accessibilityService.packageManager.getPackageInfo(parsedId.first, 0)
                isFromKnownApp = true
            } catch (e: PackageManager.NameNotFoundException) {
                // Do nothing.
            }
        }

        // Return empty list if it has a description, has no parseable id since
        // we don't support those in the label manager right now, or if it's id
        // is in a different namespace than a known package.
        if (hasDescription || !hasParseableId || !isFromKnownApp) {
            return items
        }

        // If label manager is not initialized, it is because user has a version
        // of TalkBack that doesn't support labeling. Tell the user to update.
        if (!labelManager.isInitialized) {
            items.add(MenuItem(R.string.menu_item_update_talkback, accessibilityService))
            return items
        }

        val viewLabel = labelManager.getLabelForViewIdFromCache(unwrapped.viewIdResourceName)
        // If no custom label, only have "add" option. If there is already a
        // label we have the "edit" and "remove" options.
        if (viewLabel == null) {
            items.add(MenuItem(R.string.menu_item_label_add, accessibilityService))
        } else {
            items.add(MenuItem(R.string.menu_item_label_edit, accessibilityService))
            items.add(MenuItem(R.string.menu_item_label_remove, accessibilityService))
        }
        return items
    }

    /** Class containing menu item info. */
    private class MenuItem(resId: Int, context: Context) {
        val name: String = context.getString(resId)
        val id: Int = resId
    }
}
