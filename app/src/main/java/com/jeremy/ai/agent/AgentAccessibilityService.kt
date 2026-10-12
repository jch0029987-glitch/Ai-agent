package com.jeremy.ai.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        instance = null
    }

    fun getScreenContentSummary(): String {
        val rootNode = rootInActiveWindow ?: return "Error: No active window content accessible."
        val textBuilder = StringBuilder()
        try {
            dumpNodeHierarchy(rootNode, textBuilder, 0)
        } finally {
            rootNode.recycle()
        }
        return if (textBuilder.isNotBlank()) textBuilder.toString() else "Active window text is empty."
    }

    private fun dumpNodeHierarchy(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int) {
        if (depth > 15) return
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        
        val indent = "  ".repeat(depth)
        if (!text.isNullOrBlank()) {
            builder.append(indent).append("- Text: $text [ID: ${node.viewIdResourceName}]\n")
        }
        if (!contentDesc.isNullOrBlank() && contentDesc != text) {
            builder.append(indent).append("- Desc: $contentDesc\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                try {
                    dumpNodeHierarchy(child, builder, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    // --- UI Control Actions ---

    fun clickByText(targetText: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return try {
            val node = findNodeByTextRecursive(rootNode, targetText)
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } finally {
            rootNode.recycle()
        }
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.contains(targetText, true) || desc.contains(targetText, true)) {
            if (node.isClickable) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findNodeByTextRecursive(child, targetText)
                if (found != null) {
                    // Do not recycle the matched node, but we can return it
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    fun typeText(textToType: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return try {
            val targetNode = findEditableNodeRecursive(rootNode) ?: return false
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            }
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } finally {
            rootNode.recycle()
        }
    }

    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findEditableNodeRecursive(child)
                if (found != null) {
                    return found
                }
                child.recycle()
            }
        }
        return null
    }

    fun openApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }
}
