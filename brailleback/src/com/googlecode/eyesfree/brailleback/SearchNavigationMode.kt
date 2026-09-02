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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.brailleback.utils.LabelingUtils
import com.googlecode.eyesfree.labeling.CustomLabelManager
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoRef
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.NodeFocusFinder

/**
 * Navigation mode that is based on incremental search.
 * Used as a separate navigation mode in the BrailleBackService.
 */
internal class SearchNavigationMode @JvmOverloads constructor(
    private val mDisplayManager: DisplayManager,
    private val mAccessibilityService: AccessibilityService,
    private val mFeedbackManager: FeedbackManager,
    private val mTranslatorManager: TranslatorManager,
    private val mSelfBrailleManager: SelfBrailleManager,
    private val mNodeBrailler: NodeBrailler,
    private val mSearchStateListener: SearchStateListener,
    private val mLabelManager: CustomLabelManager? = null,
    // 仅供测试构造器注入；为 null 时下面会走正常构造路径。
    private val overlayOverride: SearchOverlay? = null
) : NavigationMode {

    /** Listener for when search has started or finished.  */
    interface SearchStateListener {
        fun onSearchStarted()
        fun onSearchFinished()
    }

    private val mQueryText = StringBuilder()
    private val mSearchOverlay: SearchOverlay =
            overlayOverride ?: SearchOverlay(mAccessibilityService, mQueryText)

    private var mActive = false

    private val mInitialNode = AccessibilityNodeInfoRef()

    // Track node we last matched since it may not be the same as
    // the currently focused node.
    private val mMatchedNode = AccessibilityNodeInfoRef()

    /** 测试用构造器：不提供 CustomLabelManager，改为外部注入 SearchOverlay。 */
    constructor(
        displayManager: DisplayManager,
        accessibilityService: AccessibilityService,
        feedbackManager: FeedbackManager,
        translatorManager: TranslatorManager,
        selfBrailleManager: SelfBrailleManager,
        nodeBrailler: NodeBrailler,
        searchStateListener: SearchStateListener,
        searchOverlay: SearchOverlay
    ) : this(displayManager, accessibilityService, feedbackManager,
            translatorManager, selfBrailleManager, nodeBrailler,
            searchStateListener, null, searchOverlay)

    override fun onActivate() {
        mActive = true

        // In case the initial node is no longer focused (such as exiting the
        // tutorial), we want to try to focus it here.
        if (!AccessibilityNodeInfoRef.isNull(mInitialNode)) {
            // Intentionally ignoring return value.
            mInitialNode.get()?.performAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }

        // Save the currently focused node.
        mSearchStateListener.onSearchStarted()

        // Update display.
        brailleMatchedOrFocusedNode()
        mSearchOverlay.show()
    }

    override fun onDeactivate() {
        mActive = false
        mInitialNode.clear()
        mMatchedNode.clear()
        mQueryText.setLength(0)
        mSearchOverlay.hide()
    }

    /**
     * Intended to be called before [onActivate] so that an initial node
     * is set and can be re-focused if search mode is escaped.
     */
    fun setInitialNodeToCurrent() {
        val focused = FocusFinder.getFocusedNode(
                mAccessibilityService, false)
        mInitialNode.reset(focused)
    }

    fun isActive(): Boolean {
        return mActive
    }

    override fun onObserveAccessibilityEvent(event: AccessibilityEvent) {
        // Nothing to do here.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> {
                val content = formatEventToBraille(event)
                if (content != null) {
                    mDisplayManager.setContent(content)
                }
                return true
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                finishSearch()
                // Let it fall through so other navigation mode can
                // receive the window_state_changed event.
                return false
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // This will re-evaluate the search and refocus if necessary.
                mMatchedNode.reset(AccessibilityNodeInfoUtils.refreshNode(
                        mMatchedNode.get()))
                evaluateSearch()
                return true
            }
        }
        // Don't let fall through.
        return true
    }

    override fun onInvalidateAccessibilityNode(node: AccessibilityNodeInfoCompat) {
        mMatchedNode.reset(AccessibilityNodeInfoUtils.refreshNode(
                mMatchedNode.get()))
        evaluateSearch()
    }

    override fun onPanLeftOverflow(content: DisplayManager.Content): Boolean {
        // When we overflow, want to move to the previous result, if it exists.
        return mFeedbackManager.emitOnFailure(
                nextResult(NodeFocusFinder.SEARCH_BACKWARD),
                FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    override fun onPanRightOverflow(content: DisplayManager.Content): Boolean {
        // When we overflow, want to move to the next result, if it exists.
        return mFeedbackManager.emitOnFailure(
                nextResult(NodeFocusFinder.SEARCH_FORWARD),
                FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }

    override fun onMappedInputEvent(event: BrailleInputEvent,
                                    content: DisplayManager.Content): Boolean {
        when (event.command) {
            BrailleInputEvent.CMD_TOGGLE_INCREMENTAL_SEARCH -> {
                syncBackToInitial()
                finishSearch()
                return true
            }
            BrailleInputEvent.CMD_NAV_ITEM_NEXT, BrailleInputEvent.CMD_NAV_LINE_NEXT ->
                return mFeedbackManager.emitOnFailure(
                        nextResult(NodeFocusFinder.SEARCH_FORWARD),
                        FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
            BrailleInputEvent.CMD_NAV_ITEM_PREVIOUS, BrailleInputEvent.CMD_NAV_LINE_PREVIOUS ->
                return mFeedbackManager.emitOnFailure(
                        nextResult(NodeFocusFinder.SEARCH_BACKWARD),
                        FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
            BrailleInputEvent.CMD_KEY_DEL -> return handleDelete()
            BrailleInputEvent.CMD_ACTIVATE_CURRENT, BrailleInputEvent.CMD_KEY_ENTER -> {
                finishSearch()
                return true
            }
            BrailleInputEvent.CMD_BRAILLE_KEY -> return handleBrailleKey(event.argument)
            else ->
                // Don't let other commands fall through.
                return true
        }
    }

    /**
     * Syncs accessibility focus back to the node focused when search mode
     * first activated.
     */
    private fun syncBackToInitial() {
        val focused = FocusFinder.getFocusedNode(
                mAccessibilityService, false) ?: return
        try {
            mInitialNode.reset(AccessibilityNodeInfoUtils.refreshNode(
                    mInitialNode.get()))
            if (!AccessibilityNodeInfoRef.isNull(mInitialNode)) {
                val initial = mInitialNode.get()
                if (initial?.isAccessibilityFocused == true) {
                    return
                }
                mFeedbackManager.emitOnFailure(
                        initial?.performAction(
                                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) == true,
                        FeedbackManager.TYPE_COMMAND_FAILED)
            } else {
                mFeedbackManager.emitOnFailure(
                        focused.performAction(AccessibilityNodeInfoCompat
                                .ACTION_CLEAR_ACCESSIBILITY_FOCUS),
                        FeedbackManager.TYPE_COMMAND_FAILED)
            }
        } finally {
            focused.recycle()
        }
    }

    /**
     * Calls the listener.
     * Leaves accessibility focus where it is (on the result).
     */
    private fun finishSearch() {
        mSearchStateListener.onSearchFinished()
    }

    /**
     * Translates braille dots into a character sequence and appends it
     * to the current query text.
     */
    private fun handleBrailleKey(dots: Int): Boolean {
        // TODO: handle non-computer braille.
        val translator = mTranslatorManager.getUncontractedTranslator()

        if (translator == null) {
            LogUtils.log(this, Log.WARN, "missing translator %s",
                    translator)
            mFeedbackManager.emitFeedback(FeedbackManager.TYPE_COMMAND_FAILED)
            return true
        }

        val text = translator.backTranslate(byteArrayOf(dots.toByte()))
        if (!TextUtils.isEmpty(text) && text != null) {
            return mFeedbackManager.emitOnFailure(tryAddQueryText(text),
                    FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
        }

        return true
    }

    private fun tryAddQueryText(newText: CharSequence): Boolean {
        val initLength = mQueryText.length
        mQueryText.append(newText)
        if (evaluateSearch()) {
            mSearchOverlay.refreshOverlay()
            return true
        }
        // Search failed, go back to old text.
        mQueryText.delete(initLength, mQueryText.length)
        return false
    }

    /**
     * Evaluates the search with the current query and returns whether a match
     * was found searching forward. Tries to match with last matched item.
     */
    private fun evaluateSearch(): Boolean {
        // First check if current selected result still matches.
        if (nodeMatchesQuery(mMatchedNode.get())) {
            // Make sure cursor is updated if search text changed.
            brailleMatchedOrFocusedNode()
            return true
        }
        return nextResult(NodeFocusFinder.SEARCH_FORWARD)
    }

    /**
     * Searches for the next result matching the current search query in the
     * specified direction. Ordering of results taken from linear navigation.
     * Returns whether there is another result in that direction.
     */
    private fun nextResult(direction: Int): Boolean {
        val next = AccessibilityNodeInfoRef()
        next.reset(NodeFocusFinder.focusSearch(
                getCurrentNode(), direction))
        var focusableNext: AccessibilityNodeInfoCompat? = null
        return try {
            while (next.get() != null) {
                val candidate = next.get()
                if (nodeMatchesQuery(candidate)) {
                    // Even if the text matches, we need to make sure the node
                    // should be focused or has a parent that should be focused.
                    focusableNext = AccessibilityNodeInfoUtils.findFocusFromHover(
                            mAccessibilityService, candidate)

                    // Only count this as a match if it doesn't lead to the same
                    // parent.
                    if (focusableNext != null &&
                            !focusableNext.isAccessibilityFocused) {
                        break
                    }
                }
                next.reset(NodeFocusFinder.focusSearch(next.get(), direction))
            }

            val focusable = focusableNext ?: return false

            mMatchedNode.reset(next)
            focusable.performAction(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(focusableNext)
            next.recycle()
        }
    }

    /**
     * Will delete the last entered character if it exists. If not, will exit
     * incremental search mode without syncing to the selected result.
     */
    private fun handleDelete(): Boolean {
        val length = mQueryText.length

        if (length > 0) {
            mQueryText.deleteCharAt(mQueryText.length - 1)
            brailleMatchedOrFocusedNode()
            mSearchOverlay.refreshOverlay()
        } else {
            syncBackToInitial()
            finishSearch()
        }
        return true
    }

    /**
     * Returns whether the specified node's description matches the current
     * query text (case insensitive).
     */
    private fun nodeMatchesQuery(node: AccessibilityNodeInfoCompat?): Boolean {
        // When no query text, consider everything a match.
        if (TextUtils.isEmpty(mQueryText)) {
            return AccessibilityNodeInfoUtils.shouldFocusNode(
                    mAccessibilityService, node)
        }

        if (node == null || mSelfBrailleManager.hasContentForNode(node)) {
            return false
        }

        val nodeText = LabelingUtils.getNodeText(node, mLabelManager) ?: return false
        val queryText = mQueryText.toString().lowercase()

        return nodeText.toString().lowercase().contains(queryText)
    }

    /**
     * Updates the display to show the current search result, with cursor
     * at the end of the matching text. The text will correspond to
     * the text of matched node, which may not be the text of the focused node.
     */
    private fun brailleMatchedOrFocusedNode() {
        val current = getCurrentNode() ?: return
        val content = formatNodeToBraille(current)
        if (content != null) {
            mDisplayManager.setContent(content)
        }
    }

    /**
     * Returns the last matching node if available. Otherwise falls back to
     * the currently focused node.
     */
    private fun getCurrentNode(): AccessibilityNodeInfoCompat? {
        return if (AccessibilityNodeInfoRef.isNull(mMatchedNode)) {
            FocusFinder.getFocusedNode(mAccessibilityService, true)
        } else {
            mMatchedNode.get()
        }
    }

    /**
     * Formats some braille content from an [AccessibilityEvent].
     */
    private fun formatEventToBraille(
            event: AccessibilityEvent): DisplayManager.Content? {
        val eventNode = getNodeFromEvent(event)
        if (eventNode != null) {
            val content = formatNodeToBraille(eventNode)
            if (content != null) {
                return content.setPanStrategy(DisplayManager.Content.PAN_CURSOR)
            }
        }

        // This should never happen, print out an error if it does.
        LogUtils.log(this, Log.WARN, "No node on event!")
        return null
    }

    /**
     * Formats some braille content from an [AccessibilityNodeInfoCompat].
     * Marks the description with a cursor right after the last character
     * matching the search query. Returns content with keep pan strategy
     * by default, or reset pan strategy if there is no matched node stored yet.
     */
    private fun formatNodeToBraille(
            node: AccessibilityNodeInfoCompat?): DisplayManager.Content? {
        if (node == null) {
            return null
        }
        val content = mNodeBrailler.brailleNode(node) ?: return null

        val spanned = content.getSpanned()
        if (spanned == null) {
            LogUtils.log(this, Log.ERROR, "No text for node")
            return null
        }

        // Find index of match and the index corresponding to the
        // end of the matched text so we know where to place the cursor.
        val lowerCase = spanned.toString().lowercase()
        val matchText = mQueryText.toString().lowercase()
        val spanNode = DisplaySpans.getEqualSpan(
                spanned, node) as AccessibilityNodeInfoCompat?
        var cursorIndex = -1
        if (spanNode != null) {
            val nodeIndex = spanned.getSpanStart(spanNode)
            if (nodeIndex >= 0 && nodeIndex < lowerCase.length) {
                val matchIndex = lowerCase.indexOf(matchText, nodeIndex)
                if (matchIndex >= 0 && matchIndex <= lowerCase.length) {
                    cursorIndex = matchIndex + matchText.length
                }
            }
        }

        // Add prefix to what's displayed to mark this as a search result.
        val prefix = mAccessibilityService.getString(
                R.string.search_result_prefix)
        val lengthDiff = prefix.length
        val sb = spanned as SpannableStringBuilder
        sb.insert(0, prefix)

        // If match in this node, add cursor at end of match.
        if (cursorIndex != -1) {
            DisplaySpans.addSelection(sb, cursorIndex + lengthDiff,
                    cursorIndex + lengthDiff)
        }

        // No matched node stored, means we have just activated search mode,
        // so we want to reset the panning frame so we can see the search
        // prefix.
        val panStrategy = if (AccessibilityNodeInfoRef.isNull(mMatchedNode)) {
            DisplayManager.Content.PAN_RESET
        } else {
            DisplayManager.Content.PAN_KEEP
        }

        return content.setPanStrategy(panStrategy)
    }

    private fun getNodeFromEvent(
            event: AccessibilityEvent): AccessibilityNodeInfoCompat? {
        val node = event.source
        return if (node != null) {
            AccessibilityNodeInfoCompat(node)
        } else {
            null
        }
    }

    /* Package private functions for testing. */

    internal fun setQueryTextForTest(text: String) {
        mQueryText.setLength(0)
        mQueryText.append(text)
    }

    internal fun getQueryTextForTest(): String {
        return mQueryText.toString()
    }
}
