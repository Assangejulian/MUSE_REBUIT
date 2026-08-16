package com.muse.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.muse.agent.UiNode
import com.muse.agent.UiSafety
import com.muse.agent.UiSnapshot
import com.muse.agent.findInSnapshot
import com.muse.agent.formatSnapshot
import com.muse.agent.rematchNode
import com.muse.agent.sameScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class MuseAccessibilityService : AccessibilityService() {
    @Volatile
    private var last: UiSnapshot? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    suspend fun snapshot(): UiSnapshot = onMain {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString().orEmpty()
        val title = windows?.firstOrNull { it.title != null }?.title?.toString().orEmpty()
        val nodes = if (root == null) emptyList() else walk(root)
        UiSnapshot(pkg = pkg, title = title, source = "a11y", nodes = nodes).also { last = it }
    }

    suspend fun snapshotText(): String = formatSnapshot(snapshot())

    suspend fun find(query: String): String {
        val snap = snapshot()
        val hits = findInSnapshot(snap, query)
        if (hits.isEmpty()) return "没有匹配「$query」的节点。"
        return formatSnapshot(snap.copy(nodes = hits), limit = 20)
    }

    suspend fun clickId(id: String): String {
        val snap = last ?: snapshot()
        UiSafety.blockReason(snap)?.let { return "错误：$it" }
        val old = snap.nodes.firstOrNull { it.id.equals(id.trim(), true) }
            ?: return "错误：没有 $id。先 ui_snapshot。"
        val fresh = snapshot()
        UiSafety.blockReason(fresh)?.let { return "错误：$it" }
        val target = rematchNode(old, fresh.nodes) ?: old
        return clickNode(target)
    }

    suspend fun clickText(text: String): String {
        val snap = snapshot()
        UiSafety.blockReason(snap)?.let { return "错误：$it" }
        val hits = findInSnapshot(snap, text)
        val target = hits.firstOrNull { it.clickable || it.editable || it.checkable }
            ?: hits.firstOrNull()
            ?: return "错误：没有找到「$text」。"
        return clickNode(target)
    }

    suspend fun typeIntoFocused(text: String): String {
        val status = onMain {
            val root = rootInActiveWindow ?: return@onMain "错误：没有窗口。"
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return@onMain if (ok) "已输入 ${text.take(20)}" else "错误：该输入框不支持 SET_TEXT。"
            }
            "错误：没有焦点输入框。先 click_node 点中输入框。"
        }
        return withTree(status)
    }

    suspend fun goBack(): String = withTree(
        onMain { if (performGlobalAction(GLOBAL_ACTION_BACK)) "已 BACK" else "错误：BACK 失败" },
    )

    suspend fun goHome(): String = withTree(
        onMain { if (performGlobalAction(GLOBAL_ACTION_HOME)) "已 HOME" else "错误：HOME 失败" },
    )

    suspend fun scroll(direction: String): String {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "up" -> listOf(w / 2, h * 0.35f, w / 2, h * 0.75f)
            "left" -> listOf(w * 0.25f, h / 2, w * 0.8f, h / 2)
            "right" -> listOf(w * 0.8f, h / 2, w * 0.25f, h / 2)
            else -> listOf(w / 2, h * 0.75f, w / 2, h * 0.35f)
        }
        return withTree(gesture(x1, y1, x2, y2, 280))
    }

    suspend fun tap(x: Int, y: Int): String =
        withTree(gesture(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 60))

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String =
        withTree(gesture(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), 280))

    private suspend fun clickNode(node: UiNode): String {
        val before = last ?: snapshot()
        val resolved = onMain { resolveClick(node) }
        val a11yOk = onMain {
            val live = resolved.actionNode ?: return@onMain false
            if (live.isCheckable || live.isClickable || live.isEditable) {
                live.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                false
            }
        }
        if (a11yOk) {
            delay(180)
            val after = snapshot()
            if (!sameScreen(before, after)) {
                return "已点击 ${node.id}「${node.label()}」via a11y\n---\n${formatSnapshot(after)}"
            }
        }
        val tapped = gesture(resolved.x.toFloat(), resolved.y.toFloat(), resolved.x.toFloat(), resolved.y.toFloat(), 60)
        if (tapped.startsWith("错误") && !a11yOk) return tapped
        delay(180)
        val after = snapshot()
        val how = if (tapped.startsWith("错误")) "a11y" else "gesture (${resolved.x},${resolved.y})"
        return "已点击 ${node.id}「${node.label()}」via $how\n---\n${formatSnapshot(after)}"
    }

    private data class ResolvedClick(val actionNode: AccessibilityNodeInfo?, val x: Int, val y: Int)

    private fun resolveClick(node: UiNode): ResolvedClick {
        val live = findLive(node) ?: return ResolvedClick(null, node.cx, node.cy)
        var action = live
        if (!action.isClickable && !action.isCheckable && !action.isEditable) {
            var parent = action.parent
            while (parent != null) {
                if (parent.isClickable || parent.isCheckable || parent.isEditable) {
                    action = parent
                    break
                }
                parent = parent.parent
            }
        }
        val rect = Rect()
        action.getBoundsInScreen(rect)
        val x = if (rect.width() > 0) rect.centerX() else node.cx
        val y = if (rect.height() > 0) rect.centerY() else node.cy
        return ResolvedClick(action, x, y)
    }

    private suspend fun withTree(status: String): String {
        if (status.startsWith("错误：")) return status
        delay(180)
        return status + "\n---\n" + snapshotText()
    }

    private fun findLive(target: UiNode): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val rect = Rect()
            n.getBoundsInScreen(rect)
            val text = n.text?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            val vid = n.viewIdResourceName.orEmpty()
            val score = when {
                text == target.text && desc == target.desc && vid == target.viewId && target.label().isNotBlank() -> 0
                vid.isNotBlank() && vid == target.viewId -> 1
                text == target.text && target.text.isNotBlank() -> 2
                desc == target.desc && target.desc.isNotBlank() -> 3
                else -> 99
            }
            if (score < bestScore) {
                best = n
                bestScore = score
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return if (bestScore <= 3) best else null
    }

    private fun walk(root: AccessibilityNodeInfo): List<UiNode> {
        val out = ArrayList<UiNode>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var i = 0
        while (q.isNotEmpty() && out.size < 80) {
            val n = q.removeFirst()
            if (n.isVisibleToUser) {
                val text = n.text?.toString().orEmpty()
                val desc = n.contentDescription?.toString().orEmpty()
                val useful = n.isClickable || n.isEditable || n.isCheckable ||
                    text.isNotBlank() || desc.isNotBlank()
                if (useful) {
                    val rect = Rect()
                    n.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        i += 1
                        out += UiNode(
                            id = "n$i",
                            text = text.take(40),
                            desc = desc.take(40),
                            viewId = n.viewIdResourceName.orEmpty(),
                            cls = n.className?.toString()?.substringAfterLast('.').orEmpty(),
                            clickable = n.isClickable,
                            editable = n.isEditable,
                            checkable = n.isCheckable,
                            checked = n.isChecked,
                            enabled = n.isEnabled,
                            cx = rect.centerX(),
                            cy = rect.centerY(),
                            left = rect.left,
                            top = rect.top,
                            right = rect.right,
                            bottom = rect.bottom,
                        )
                    }
                }
            }
            for (c in 0 until n.childCount) n.getChild(c)?.let { q.add(it) }
        }
        return out
    }

    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): String {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val done = CompletableDeferred<Boolean>()
        onMain {
            val ok = dispatchGesture(
                desc,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        done.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        done.complete(false)
                    }
                },
                null,
            )
            if (!ok) done.complete(false)
        }
        return if (withTimeout(3_000) { done.await() }) "已手势 ($x1,$y1)->($x2,$y2)" else "错误：手势失败"
    }

    suspend fun captureBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val done = CompletableDeferred<Bitmap?>()
        onMain {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                { runnable -> runnable.run() },
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                        buffer.close()
                        done.complete(copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        done.complete(null)
                    }
                },
            )
        }
        return runCatching { withTimeout(4_000) { done.await() } }.getOrNull()
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    companion object {
        @Volatile
        var instance: MuseAccessibilityService? = null
            private set

        fun enabled(context: Context): Boolean {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return raw.contains(context.packageName, ignoreCase = true)
        }
    }
}
