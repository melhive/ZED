package com.melhive.zed.assist

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Screen awareness and control, used only when the user asks: reads the text of the app on
 * screen (behind ZED's floating orb) and taps, scrolls, types or presses Back/Home for them.
 * Nothing is recorded or kept; each request reads the screen at that moment.
 */
class ZedAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }

    data class Screen(val app: String, val text: String)

    /** The app the user is looking at: the top application window that isn't ZED. */
    private fun appRoot(): Pair<String, AccessibilityNodeInfo>? {
        val wins = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
        for (w in wins) {
            val root = w.root ?: continue
            if (root.packageName?.toString() == packageName) continue
            return label(root.packageName?.toString()) to root
        }
        val active = rootInActiveWindow ?: return null
        return if (active.packageName?.toString() == packageName) null else label(active.packageName?.toString()) to active
    }

    private fun label(pkg: String?): String = pkg?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault(it) } ?: "the app"

    private fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 40) return
        visit(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1, visit)
    }

    fun readScreen(maxChars: Int = 4000): Screen? {
        val (app, root) = appRoot() ?: return null
        val seen = LinkedHashSet<String>()
        walk(root) { n ->
            if (!n.isVisibleToUser) return@walk
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.replace(Regex("\\s+"), " ")
            if (!t.isNullOrEmpty() && t.length > 1) seen += t
        }
        val text = buildString { for (line in seen) { if (length + line.length > maxChars) break; appendLine(line) } }.trim()
        return Screen(app, text)
    }

    private fun matches(n: AccessibilityNodeInfo): String = listOfNotNull(n.text, n.contentDescription, n.hintText(), n.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' '))
        .joinToString(" ").lowercase()

    private fun AccessibilityNodeInfo.hintText(): CharSequence? = if (Build.VERSION.SDK_INT >= 26) hintText else null

    private fun find(target: String): AccessibilityNodeInfo? {
        val (_, root) = appRoot() ?: return null
        val q = target.trim().lowercase().removePrefix("the ").removeSuffix(" button")
        var exact: AccessibilityNodeInfo? = null; var partial: AccessibilityNodeInfo? = null
        walk(root) { n ->
            if (!n.isVisibleToUser) return@walk
            val own = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase().orEmpty()
            if (exact == null && own == q) exact = n
            if (partial == null && (own.contains(q) || matches(n).contains(q))) partial = n
        }
        return exact ?: partial
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) { if (cur.isClickable) return cur; cur = cur.parent }
        return null
    }

    private fun tapAt(r: Rect): Boolean {
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    /** Taps the control labelled [target]; returns what was tapped, or null. */
    fun tap(target: String): String? {
        val n = find(target) ?: return null
        val name = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty().ifEmpty { target }
        clickable(n)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return name }
        val r = Rect(); n.getBoundsInScreen(r)
        return if (!r.isEmpty && tapAt(r)) name else null
    }

    fun scroll(direction: String): Boolean {
        val (_, root) = appRoot() ?: return false
        var best: AccessibilityNodeInfo? = null; var area = 0
        walk(root) { n -> if (n.isScrollable && n.isVisibleToUser) { val r = Rect(); n.getBoundsInScreen(r); val a = r.width() * r.height(); if (a > area) { area = a; best = n } } }
        val forward = direction in setOf("down", "right", "forward", "next")
        best?.let { if (it.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true }
        // No scrollable list found: swipe.
        val dm = resources.displayMetrics
        val (x, y0, y1) = Triple(dm.widthPixels / 2f, dm.heightPixels * 0.7f, dm.heightPixels * 0.3f)
        val path = Path().apply { if (forward) { moveTo(x, y0); lineTo(x, y1) } else { moveTo(x, y1); lineTo(x, y0) } }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build(), null, null)
    }

    /** Types into the focused text field, or the one labelled [field], or the first one on screen. */
    fun type(text: String, field: String?): Boolean {
        val (_, root) = appRoot() ?: return false
        var target: AccessibilityNodeInfo? = field?.let { f -> find(f)?.takeIf { it.isEditable } }
        if (target == null) walk(root) { n -> if (target == null && n.isEditable && n.isFocused) target = n }
        if (target == null) walk(root) { n -> if (target == null && n.isEditable && n.isVisibleToUser) target = n }
        val t = target ?: return false
        t.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return t.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun global(action: String): Boolean = performGlobalAction(when (action) {
        "back" -> GLOBAL_ACTION_BACK
        "home" -> GLOBAL_ACTION_HOME
        "recents" -> GLOBAL_ACTION_RECENTS
        "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
        "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
        "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
        "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
        else -> return false
    })

    companion object {
        @Volatile var instance: ZedAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, ZedAccessibilityService::class.java).flattenToString()
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return on.split(':').any { it.equals(me, ignoreCase = true) }
        }

        fun openSettings(context: Context) {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
