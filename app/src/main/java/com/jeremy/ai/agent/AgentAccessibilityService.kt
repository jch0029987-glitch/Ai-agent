package com.jeremy.ai.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibility"
        var instance: AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AgentAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted.")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // --- Screen Reading ---

    fun getScreenContentSummary(): String {
        val rootNode = rootInActiveWindow ?: return "Error: No active window content accessible."
        val textBuilder = StringBuilder()
        dumpNodeHierarchy(rootNode, textBuilder, 0)
        return if (textBuilder.isNotBlank()) textBuilder.toString() else "Active window text is empty."
    }

    private fun dumpNodeHierarchy(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int) {
        if (depth > 15) return

        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val indent = "  ".repeat(depth)

        if (!text.isNullOrBlank()) {
            val resourceId = node.viewIdResourceName ?: "no_id"
            builder.append(indent).append("- Text: $text [ID: $resourceId]\n")
        }
        if (!contentDesc.isNullOrBlank() && contentDesc != text) {
            builder.append(indent).append("- Desc: $contentDesc\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeHierarchy(child, builder, depth + 1)
        }
    }

    // --- UI Control Actions ---

    fun clickByText(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val node = findNodeByTextRecursive(rootNode, targetText) ?: return false
        
        // Execute click action on the node or walk up to its clickable parent
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }

        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if ((text.contains(targetText, ignoreCase = true) || desc.contains(targetText, ignoreCase = true))) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextRecursive(child, targetText)
            if (found != null) {
                return found
            }
        }
        return null
    }

    fun typeText(textToType: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val targetNode = findEditableNodeRecursive(rootNode) ?: return false

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNodeRecursive(child)
            if (found != null) {
                return found
            }
        }
        return null
    }

    // --- Safe App Launching ---

    fun openApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                startActivity(launchIntent)
                true
            } else {
                Log.e(TAG, "Failed to get launch intent for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "SecurityException/Error opening app: ${e.localizedMessage}", e)
            false
        }
    }
}
