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

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.RemoteException
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.selfbraille.ISelfBrailleService
import com.googlecode.eyesfree.braille.selfbraille.WriteData
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.NodeFilter
import java.lang.ref.WeakReference
import java.util.HashSet

/**
 * Allows clients to control what should be output on the braille display for
 * parts of the accessibility node tree.
 */
class SelfBrailleService : Service() {
    private val serviceImpl = ServiceImpl()
    private val clients = HashMap<IBinder, ClientInfo>()
    private val nodeStates = HashMap<AccessibilityNodeInfo, NodeState>()
    private val handler = SelfBrailleHandler(this)
    private var packageManager: PackageManager? = null

    override fun onCreate() {
        sInstance = WeakReference(this)
        packageManager = getPackageManager()
    }

    override fun onDestroy() {
        sInstance = null
        handler.removeCallbacksAndMessages(null)
        val nodes = HashSet<AccessibilityNodeInfo>()
        for (info in clients.values) {
            info.unlinkDeathRecipient()
            nodes.addAll(info.mNodes)
        }
        nodes.addAll(nodeStates.keys)
        clients.clear()
        nodeStates.clear()
        for (node in nodes) {
            node.recycle()
        }
        packageManager = null
    }

    override fun onBind(intent: Intent): IBinder {
        return serviceImpl
    }

    private inner class ServiceImpl : ISelfBrailleService.Stub() {
        override fun write(clientToken: IBinder?, writeData: WriteData?) {
            if (clientToken == null) {
                LogUtils.log(this@SelfBrailleService, Log.ERROR, "null client token to write")
                return
            }
            val manager = packageManager
            if (manager == null) {
                LogUtils.log(
                    this@SelfBrailleService, Log.ERROR,
                    "write received after service destruction")
                return
            }
            val serviceUtil = ServiceUtil(manager)
            if (!serviceUtil.verifyCaller(Binder.getCallingUid())) {
                LogUtils.log(
                    this@SelfBrailleService, Log.ERROR,
                    "non-google signed package try to invoke service, rejected.")
                return
            }
            if (writeData == null) {
                LogUtils.log(this@SelfBrailleService, Log.ERROR, "null writeData to write")
                return
            }
            LogUtils.log(
                this@SelfBrailleService, Log.VERBOSE,
                "write %s, %s", writeData.text, writeData.accessibilityNodeInfo)
            try {
                writeData.validate()
            } catch (ex: IllegalStateException) {
                LogUtils.log(this@SelfBrailleService, Log.ERROR, "Invalid write data: %s", ex)
                return
            }
            val state = NodeState()
            state.mClientToken = clientToken
            state.mWriteData = writeData
            handler.setNodeState(state)
        }

        override fun disconnect(clientToken: IBinder?) {
            handler.clientDisconnected(clientToken)
        }
    }

    fun contentForNode(node: AccessibilityNodeInfoCompat): DisplayManager.Content? {
        if (nodeStates.isEmpty()) {
            return null
        }
        val match = AccessibilityNodeInfoUtils.getSelfOrMatchingAncestor(
            this, node, filterHaveNodeState) ?: return null
        val unwrappedMatch = match.info as AccessibilityNodeInfo
        val writeData = nodeStates[unwrappedMatch]?.mWriteData ?: return null

        val sb = SpannableStringBuilder(writeData.text)
        // NOTE: it is important to use a node returned by the accessibility
        // framework and not a node from a client of this service. The rest of
        // BrailleBack will assume that the node we are adding here is sealed,
        // supports actions etc.
        DisplaySpans.setAccessibilityNode(sb, match)
        val selectionStart = writeData.selectionStart
        if (selectionStart >= 0) {
            var selectionEnd = writeData.selectionEnd
            if (selectionEnd < selectionStart) {
                selectionEnd = selectionStart
            }
            DisplaySpans.addSelection(sb, selectionStart, selectionEnd)
        }
        return DisplayManager.Content(sb)
            .setFirstNode(match)
            .setLastNode(match)
            .setPanStrategy(DisplayManager.Content.PAN_CURSOR)
    }

    private fun infoForClient(clientToken: IBinder): ClientInfo? {
        var info = clients[clientToken]
        if (info == null) {
            info = ClientInfo(clientToken)
            try {
                clientToken.linkToDeath(info, 0)
            } catch (ex: RemoteException) {
                return null
            }
            clients[clientToken] = info
        }
        return info
    }

    private inner class ClientInfo(private val mClientToken: IBinder) :
        IBinder.DeathRecipient {
        val mNodes = HashSet<AccessibilityNodeInfo>()

        fun unlinkDeathRecipient() {
            try {
                mClientToken.unlinkToDeath(this, 0)
            } catch (e: RuntimeException) {
                // The binder may already be dead or unlinked.
            }
        }

        override fun binderDied() {
            handler.clientDisconnected(mClientToken)
        }
    }

    private class NodeState {
        var mClientToken: IBinder? = null
        var mWriteData: WriteData? = null
    }

    /**
     * Static so messages queued on the main Looper do not retain the service.
     */
    private class SelfBrailleHandler(service: SelfBrailleService) :
        Handler(Looper.getMainLooper()) {
        private val serviceRef = WeakReference(service)

        fun setNodeState(newState: NodeState) {
            obtainMessage(MSG_SET_NODE_STATE, newState).sendToTarget()
        }

        fun clientDisconnected(clientToken: IBinder?) {
            if (clientToken != null) {
                obtainMessage(MSG_CLIENT_DISCONNECTED, clientToken).sendToTarget()
            }
        }

        override fun handleMessage(msg: Message) {
            val service = serviceRef.get() ?: return
            when (msg.what) {
                MSG_SET_NODE_STATE ->
                    handleSetNodeState(service, msg.obj as NodeState)
                MSG_CLIENT_DISCONNECTED ->
                    (msg.obj as? IBinder)?.let {
                        handleClientDisconnected(service, it)
                    }
            }
        }

        private fun handleSetNodeState(service: SelfBrailleService, newState: NodeState) {
            val newNode = newState.mWriteData!!.accessibilityNodeInfo!!
            // We must remove and insert to get the node replaced.
            val oldState = service.nodeStates.remove(newNode)
            if (oldState != null) {
                val oldNode = oldState.mWriteData!!.accessibilityNodeInfo!!
                val oldClientInfo = service.infoForClient(oldState.mClientToken!!)
                if (oldClientInfo != null) {
                    oldClientInfo.mNodes.remove(oldNode)
                }
                oldNode.recycle()
            }
            var recycleNewNode = true
            if (newState.mWriteData!!.text != null) {
                val newClientInfo = service.infoForClient(newState.mClientToken!!)
                if (newClientInfo != null) {
                    newClientInfo.mNodes.add(newNode)
                    service.nodeStates[newNode] = newState
                    recycleNewNode = false
                } else {
                    // The client is already dead.
                }
            } else {
                // No new text, the client relinquishes control over this node.
            }
            val brailleBack = BrailleBackService.getActiveInstance()
            if (brailleBack != null) {
                brailleBack.invalidateNode(newNode)
            }
            if (recycleNewNode) {
                newNode.recycle()
            }
        }

        private fun invalidateAndRecycleNode(node: AccessibilityNodeInfo) {
            val brailleBack = BrailleBackService.getActiveInstance()
            if (brailleBack != null) {
                brailleBack.invalidateNode(node)
            }
            node.recycle()
        }

        private fun handleClientDisconnected(
            service: SelfBrailleService, clientToken: IBinder) {
            val clientInfo = service.clients.remove(clientToken)
            if (clientInfo != null) {
                LogUtils.log(
                    service, Log.VERBOSE,
                    "Disconnected %s, removing %d nodes", clientToken, clientInfo.mNodes.size)
                for (node in clientInfo.mNodes) {
                    val state = service.nodeStates[node]
                    if (state != null && state.mClientToken == clientToken) {
                        service.nodeStates.remove(node)
                        invalidateAndRecycleNode(node)
                    }
                }
            }
        }
    }

    private val filterHaveNodeState = object : NodeFilter() {
        override fun accept(context: Context?, node: AccessibilityNodeInfoCompat?): Boolean {
            val unwrappedNode = node!!.info as AccessibilityNodeInfo
            return nodeStates.containsKey(unwrappedNode)
        }
    }

    companion object {
        private var sInstance: WeakReference<SelfBrailleService>? = null

        private const val MSG_SET_NODE_STATE = 1
        private const val MSG_CLIENT_DISCONNECTED = 2

        @JvmStatic
        fun getActiveInstance(): SelfBrailleService? {
            return sInstance?.get()
        }
    }
}
