package com.user.riaassistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RiaAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text
        if (text != null && text.isNotEmpty()) {
            sb.append(text.toString())
            sb.append(". ")
        }
        val contentDesc = node.contentDescription
        if (contentDesc != null && contentDesc.isNotEmpty()) {
            sb.append(contentDesc.toString())
            sb.append(". ")
        }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb)
        }
    }

    // Skips editable fields (like search/text boxes) so it doesn't match
    // the box itself when looking for a clickable result with matching text.
    fun clickByText(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = findNodeByText(root, target.lowercase())
        if (match != null) {
            val clickable = findClickableAncestorOrSelf(match)
            if (clickable != null) {
                return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    private fun findNodeByText(node: AccessibilityNodeInfo?, targetLower: String): AccessibilityNodeInfo? {
        if (node == null) return null

        if (!node.isEditable) {
            val text = node.text?.toString()?.lowercase()
            if (text != null && text.contains(targetLower)) {
                return node
            }
            val desc = node.contentDescription?.toString()?.lowercase()
            if (desc != null && desc.contains(targetLower)) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val result = findNodeByText(node.getChild(i), targetLower)
            if (result != null) return result
        }
        return null
    }

    fun clickByContentDescription(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = findNodeByContentDescription(root, target.lowercase())
        if (match != null) {
            val clickable = findClickableAncestorOrSelf(match)
            if (clickable != null) {
                return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        return false
    }

    private fun findNodeByContentDescription(node: AccessibilityNodeInfo?, targetLower: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (!node.isEditable) {
            val desc = node.contentDescription?.toString()?.lowercase()
            if (desc != null && desc.contains(targetLower)) return node
        }
        for (i in 0 until node.childCount) {
            val result = findNodeByContentDescription(node.getChild(i), targetLower)
            if (result != null) return result
        }
        return null
    }

    private fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    fun setTextInFirstEditable(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findEditableNode(root) ?: return false
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val result = findEditableNode(node.getChild(i))
            if (result != null) return result
        }
        return null
    }

    fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun openRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    companion object {
        @Volatile
        var instance: RiaAccessibilityService? = null

        fun isRunning(): Boolean {
            return instance != null
        }
    }
}