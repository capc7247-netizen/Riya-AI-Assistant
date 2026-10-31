package com.user.riaassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class RiaAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

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

    // ---------------- screen ka text ----------------

    // Jo app abhi screen par hai uska text. Ria ka apna overlay/window chhod deta hai.
    fun getScreenText(): String {
        var root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root == null || root.packageName?.toString() == packageName) {
            try {
                for (w in windows) {
                    val r = w.root ?: continue
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        r.packageName?.toString() != packageName
                    ) {
                        root = r
                        break
                    }
                }
            } catch (e: Exception) {
            }
        }
        val node = root ?: return ""
        val sb = StringBuilder()
        collectText(node, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null || sb.length > 6000) return
        if (!node.isVisibleToUser) return
        val text = node.text
        if (text != null && text.isNotEmpty()) {
            sb.append(text.toString())
            sb.append(". ")
        }
        val contentDesc = node.contentDescription
        if (contentDesc != null && contentDesc.isNotEmpty() && contentDesc.toString() != text?.toString()) {
            sb.append(contentDesc.toString())
            sb.append(". ")
        }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb)
        }
    }

    // ---------------- click ----------------

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

    // In mein se koi bhi shabd text ya description mein ho to pehle wale par click
    fun clickByDescAny(words: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        for (w in words) {
            val match = findNodeByText(root, w.lowercase())
            if (match != null) {
                val clickable = findClickableAncestorOrSelf(match)
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }
        return false
    }

    // YouTube / Facebook ki search list ka pehla video dabao (ads chhod kar)
    fun clickFirstVideoResult(): Boolean {
        val root = rootInActiveWindow ?: return false
        val hints = listOf(
            "play video", "वीडियो चलाएं", "वीडियो चलाएँ", "views", "व्यू",
            "minutes", "मिनट", "seconds", "सेकंड"
        )
        val skip = listOf("sponsored", "प्रायोजित", "advert", "विज्ञापन")
        val found = findFirstMatching(root) { n ->
            val d = n.contentDescription?.toString()?.lowercase() ?: ""
            d.length > 15 && hints.any { d.contains(it) } && skip.none { d.contains(it) }
        } ?: return false
        val clickable = findClickableAncestorOrSelf(found) ?: return false
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findFirstMatching(
        node: AccessibilityNodeInfo?,
        test: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && !node.isEditable && test(node)) return node
        for (i in 0 until node.childCount) {
            val r = findFirstMatching(node.getChild(i), test)
            if (r != null) return r
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

    // ---------------- search box ----------------

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

    // Search icon dabao -> text likho -> Enter dabao (Facebook jaise ऐप ke liye)
    fun submitSearch(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (findEditableNode(root) == null) {
            val opened = clickByDescAny(listOf("search", "खोजें", "सर्च", "खोज"))
            if (!opened) return false
        }
        handler.postDelayed({
            val r = rootInActiveWindow
            val editable = if (r != null) findEditableNode(r) else null
            if (editable != null) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                handler.postDelayed({
                    var done = false
                    if (Build.VERSION.SDK_INT >= 30) {
                        done = editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }
                    if (!done) {
                        clickByDescAny(listOf("search", "खोजें", "सर्च"))
                    }
                }, 700)
            }
        }, 1400)
        return true
    }

    // ---------------- switch (Wi-Fi / Bluetooth) ----------------

    // Screen ka pehla switch chalu (on=true) ya band (on=false) karo
    fun setFirstSwitch(on: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val sw = findFirstMatching(root) { n ->
            val cn = n.className?.toString() ?: ""
            cn.contains("Switch") || (n.isCheckable && cn.contains("Compound"))
        } ?: return false
        if (sw.isChecked == on) return true
        val clickable = findClickableAncestorOrSelf(sw) ?: sw
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---------------- gesture tap ----------------

    // accessibility config mein canPerformGestures="true" chahiye
    fun tapAt(x: Float, y: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- global actions ----------------

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