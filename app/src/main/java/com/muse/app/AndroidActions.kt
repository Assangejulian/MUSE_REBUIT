package com.muse.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.muse.agent.ScreenImage
import com.muse.agent.ActionPort
import com.muse.agent.UrlBlocked
import com.muse.agent.UrlGuard
import com.muse.agent.compactUiDump
import com.muse.agent.formatOcrHits
import com.muse.agent.formatScheduleInstant
import com.muse.agent.formatSnapshot
import com.muse.agent.parseDumpBounds
import com.muse.agent.parseScheduleWhen
import com.muse.memory.ScheduleEntity
import com.muse.memory.ScheduleRepository
import com.muse.memory.SettingsStore
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import kotlin.math.min

class AndroidActions(
    context: Context,
    private val shizuku: ShizukuGateway,
    private val overlay: CotOverlay? = null,
    private val settings: SettingsStore? = null,
    private val schedules: ScheduleRepository? = null,
    private val alarms: ScheduleAlarms? = null,
) : ActionPort {
    private val app = context.applicationContext

    override suspend fun openUrl(url: String): String {
        val uri = try {
            UrlGuard.validate(url)
        } catch (e: UrlBlocked) {
            return "错误：${e.message}"
        }
        return launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString())),
            "已在浏览器打开 $uri",
        )
    }

    override suspend fun shareText(text: String): String {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return launch(Intent.createChooser(send, "分享"), "已打开系统分享。")
    }

    override suspend fun openApp(name: String): String {
        val needle = name.trim()
        if (needle.isEmpty()) return "错误：name 不能为空。"
        val pm = app.packageManager
        if (needle.contains('.')) {
            pm.getLaunchIntentForPackage(needle)?.let {
                return withFreshTree(launch(it, "已打开 $needle。"), 700)
            }
        }
        val matches = launcherMatches(pm, needle)
        if (matches.isEmpty()) {
            return "没有找到叫「$needle」的已安装 App。"
        }
        if (matches.size > 1 && matches.none { it.first.equals(needle, true) || it.second.equals(needle, true) }) {
            val list = matches.take(8).joinToString("\n") { "- ${it.first} (${it.second})" }
            return "匹配到多个 App，请用更精确的名字：\n$list"
        }
        val chosen = matches.firstOrNull {
            it.first.equals(needle, true) || it.second.equals(needle, true)
        } ?: matches.first()
        val intent = pm.getLaunchIntentForPackage(chosen.second)
            ?: return "错误：无法启动 ${chosen.first}。"
        return withFreshTree(launch(intent, "已打开 ${chosen.first}。"), 700)
    }

    @Suppress("DEPRECATION")
    private fun launcherMatches(pm: PackageManager, needle: String): List<Pair<String, String>> {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val fromLauncher = pm.queryIntentActivities(launch, PackageManager.MATCH_ALL).map { info ->
            info.loadLabel(pm).toString() to info.activityInfo.packageName
        }
        val fromInstalled = runCatching {
            pm.getInstalledApplications(0).mapNotNull { info ->
                val pkg = info.packageName
                val label = pm.getApplicationLabel(info).toString()
                if (pm.getLaunchIntentForPackage(pkg) == null) null else label to pkg
            }
        }.getOrDefault(emptyList())
        return (fromLauncher + fromInstalled)
            .distinctBy { it.second }
            .filter { (label, pkg) ->
                label.contains(needle, ignoreCase = true) || pkg.contains(needle, ignoreCase = true)
            }
    }

    override suspend fun shizukuStatus(): String = shizuku.statusLine()

    override suspend fun shell(command: String): String = shizuku.exec(command)

    override suspend fun uiDump(): String {
        val raw = shizuku.exec(MuseShellService.dumpCommand())
        if (raw.startsWith("错误：")) return raw
        val xmlStart = raw.indexOf("<hierarchy")
        val xml = if (xmlStart >= 0) raw.substring(xmlStart) else raw
        return compactUiDump(xml)
    }

    override suspend fun tap(x: Int, y: Int): String {
        a11y()?.let { return it.tap(x, y) }
        val raw = shizuku.exec("input tap $x $y")
        return withFreshTree(if (raw.startsWith("错误：")) raw else "已 tap ($x,$y)\n$raw")
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String {
        a11y()?.let { return it.swipe(x1, y1, x2, y2) }
        val raw = shizuku.exec("input swipe $x1 $y1 $x2 $y2 300")
        return withFreshTree(if (raw.startsWith("错误：")) raw else "已 swipe ($x1,$y1)->($x2,$y2)\n$raw")
    }

    override suspend fun type(text: String): String {
        if (text.isBlank()) return "错误：text 为空。"
        a11y()?.let {
            val viaTree = it.typeIntoFocused(text)
            if (!viaTree.startsWith("错误")) return viaTree
        }
        val ascii = text.all { it.code < 128 }
        val status = if (ascii) {
            val escaped = text.replace(" ", "%s").replace("'", "'\\''")
            shizuku.exec("input text '$escaped'")
        } else {
            val clip = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("muse", text))
            delay(200)
            shizuku.exec("input keyevent 279").let {
                if (it.startsWith("错误：")) it else "已粘贴中文/非 ASCII 文本。"
            }
        }
        return withFreshTree(status)
    }

    override suspend fun key(name: String): String {
        val key = name.trim().uppercase()
        a11y()?.let {
            when (key) {
                "BACK" -> return it.goBack()
                "HOME" -> return it.goHome()
            }
        }
        val code = when (key) {
            "BACK" -> 4
            "HOME" -> 3
            "ENTER", "RETURN" -> 66
            "RECENTS", "APP_SWITCH" -> 187
            "DELETE", "DEL" -> 67
            "PASTE" -> 279
            else -> name.toIntOrNull()
        } ?: return "错误：未知按键 $name。可用 BACK / HOME / ENTER / RECENTS / DELETE / PASTE。"
        return withFreshTree(shizuku.exec("input keyevent $code"))
    }

    override suspend fun uiStatus(): String = buildString {
        val live = MuseAccessibilityService.instance != null
        val enabled = MuseAccessibilityService.enabled(app)
        append("accessibility_enabled=").append(enabled)
        append('\n')
        append("accessibility_live=").append(live)
        append('\n')
        if (enabled && !live) append("hint=无障碍已开但服务未连上，请在系统设置里开关一次 Muse。\n")
        append(shizuku.statusLine())
    }

    override suspend fun uiSnapshot(): String {
        a11y()?.let { return it.snapshotText() }
        val dump = uiDump()
        return if (dump.startsWith("错误")) {
            "错误：无障碍未开启，Shizuku dump 也失败。$dump"
        } else {
            "source=shizuku\n$dump"
        }
    }

    override suspend fun findNodes(query: String): String {
        a11y()?.let { return it.find(query) }
        val dump = uiDump()
        val hits = dump.lines().filter { it.contains(query, ignoreCase = true) }
        return if (hits.isEmpty()) "没有匹配「$query」。" else hits.joinToString("\n")
    }

    override suspend fun clickNode(id: String): String {
        a11y()?.let { return it.clickId(id) }
        return "错误：click_node 需要无障碍。设置里打开 Accessibility，或改用 click_text / tap。"
    }

    override suspend fun clickText(text: String): String {
        a11y()?.let { return it.clickText(text) }
        val dump = uiDump()
        if (dump.startsWith("错误")) return dump
        val line = dump.lines().firstOrNull { it.contains(text, ignoreCase = true) }
            ?: return "错误：dump 里没有「$text」。"
        val point = parseDumpBounds(line) ?: return "错误：无法解析 bounds：$line"
        return tap(point.first, point.second)
    }

    override suspend fun scroll(direction: String): String {
        a11y()?.let { return it.scroll(direction) }
        return "错误：scroll 需要无障碍或改用 swipe。"
    }

    override suspend fun waitMs(ms: Int): String {
        delay(ms.toLong())
        return withFreshTree("已等待 ${ms}ms", 0)
    }

    override fun deviceHealth(): String = buildString {
        val a11yOn = MuseAccessibilityService.enabled(app)
        val a11yLive = MuseAccessibilityService.instance != null
        append("accessibility_enabled=").append(a11yOn).append('\n')
        append("accessibility_live=").append(a11yLive).append('\n')
        if (a11yOn && !a11yLive) append("hint=无障碍已开但服务未连上，去设置里开关一次 Muse。\n")
        if (!a11yOn) append("hint=要点屏幕请先开无障碍。\n")
        append("overlay=").append(overlay?.canDraw() == true).append('\n')
        append("exact_alarm=").append(alarms?.canExact() != false).append('\n')
        append(shizuku.statusLine())
    }

    override suspend fun waitFor(text: String, pkg: String, minNodes: Int, ms: Int): String {
        val deadline = System.currentTimeMillis() + ms.toLong()
        var last = ""
        val needText = text.isNotBlank()
        val needPkg = pkg.isNotBlank()
        val needNodes = minNodes > 0
        if (!needText && !needPkg && !needNodes) {
            delay(ms.toLong().coerceAtMost(4000))
            return withFreshTree("已等待 ${ms}ms", 0)
        }
        while (true) {
            val snap = a11y()?.snapshot()
            if (snap != null) {
                last = formatSnapshot(snap)
                val hay = buildString {
                    append(snap.pkg).append(' ').append(snap.title).append(' ')
                    snap.nodes.forEach { append(it.text).append(' ').append(it.desc).append(' ') }
                }
                val ok = (!needText || hay.contains(text, ignoreCase = true)) &&
                    (!needPkg || snap.pkg.contains(pkg, ignoreCase = true)) &&
                    (!needNodes || snap.nodes.size >= minNodes)
                if (ok) return "已等到。\n---\n$last"
            } else {
                last = uiSnapshot()
                val ok = (!needText || last.contains(text, ignoreCase = true)) &&
                    (!needPkg || last.contains(pkg, ignoreCase = true))
                if (ok && !needNodes) return "已等到。\n---\n$last"
            }
            if (System.currentTimeMillis() >= deadline) {
                return "超时未等到 text=$text pkg=$pkg min_nodes=$minNodes。\n---\n$last"
            }
            delay(300)
        }
    }

    override suspend fun seeScreen(): ScreenImage {
        val expanded = overlay?.isShowing() == true
        if (expanded) overlay?.collapse(settings?.current()?.theme ?: "cream")
        if (expanded) delay(80)
        return try {
            val shot = captureScreen() ?: return ScreenImage(
                "",
                "错误：截屏失败。打开无障碍（Android 11+）或连接 Shizuku。",
            )
            val jpeg = compressJpeg(shot.first)
            shot.first.recycle()
            if (jpeg.isEmpty()) return ScreenImage("", "错误：截屏编码失败。")
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            ScreenImage(
                b64,
                "已附上当前屏幕截图（source=${shot.second}）。看画面用这张图；要点控件再 ui_snapshot。",
            )
        } catch (t: Throwable) {
            ScreenImage("", "错误：截屏失败：${t.message ?: t::class.java.simpleName}")
        } finally {
            if (expanded) overlay?.show(settings?.current()?.theme ?: "cream")
        }
    }

    override suspend fun ocrScreen(): String {
        val expanded = overlay?.isShowing() == true
        if (expanded) overlay?.collapse(settings?.current()?.theme ?: "cream")
        if (expanded) delay(80)
        return try {
            val shot = captureScreen() ?: return "错误：截屏失败。打开无障碍（Android 11+）或连接 Shizuku。"
            val hits = ScreenOcr.read(shot.first)
            shot.first.recycle()
            formatOcrHits(hits, shot.second)
        } catch (t: Throwable) {
            "错误：OCR 失败：${t.message ?: t::class.java.simpleName}"
        } finally {
            if (expanded) overlay?.show(settings?.current()?.theme ?: "cream")
        }
    }

    override suspend fun floatWindow(state: String): String {
        val ov = overlay ?: return "错误：悬浮窗不可用。"
        if (!ov.canDraw()) return "错误：没有悬浮窗权限。去设置开启悬浮窗后再开。"
        val theme = settings?.current()?.theme ?: "cream"
        return when (state.trim().lowercase()) {
            "on", "true", "1", "show", "panel" -> {
                settings?.update { it.copy(floatOnTask = true) }
                ov.show(theme)
                "悬浮窗已展开。用户点收起变小球，长按小球可关闭。"
            }
            "ball", "bubble", "min" -> {
                settings?.update { it.copy(floatOnTask = false) }
                ov.collapse(theme)
                "已收成小球。点一下打开，长按关闭。"
            }
            "off", "false", "0", "hide" -> {
                ov.hide()
                "小球已关闭。用户可在 Muse 里再打开，或你再 float_window on/ball。"
            }
            else -> "错误：state 用 on、ball 或 off。"
        }
    }

    override suspend fun scheduleCreate(
        title: String,
        prompt: String,
        mode: String,
        `when`: String,
        repeat: String,
    ): String {
        val store = schedules ?: return "错误：定时存储不可用。"
        val clock = alarms ?: return "错误：闹钟不可用。"
        val name = title.trim().ifBlank { "未命名" }
        val body = prompt.trim()
        if (body.isEmpty()) return "错误：prompt 不能为空。"
        val spec = try {
            parseScheduleWhen(`when`, repeat)
        } catch (e: IllegalArgumentException) {
            return "错误：${e.message}"
        }
        val id = newScheduleId()
        val job = ScheduleEntity(
            id = id,
            title = name,
            prompt = body,
            mode = if (mode.equals("chat", true)) "chat" else "task",
            repeat = spec.repeat,
            hour = spec.hour,
            minute = spec.minute,
            nextAt = spec.nextAt,
            enabled = true,
            lastRunAt = 0L,
            lastStatus = "",
            createdAt = System.currentTimeMillis(),
        )
        store.upsert(job)
        clock.set(job)
        val exact = if (clock.canExact()) "" else " 精确闹钟未授权，可能会晚点响。去系统设置允许 Muse 精确闹钟。"
        return "已写入定时 $id「$name」${if (spec.repeat == "daily") "每天" else "一次"} ${formatScheduleInstant(spec.nextAt)}。$exact"
    }

    override suspend fun scheduleList(): String {
        val store = schedules ?: return "错误：定时存储不可用。"
        val jobs = store.list()
        if (jobs.isEmpty()) return "定时清单是空的。"
        return jobs.joinToString("\n") { job ->
            val flag = if (job.enabled) "开" else "关"
            val kind = if (job.repeat == "daily") "每天" else "一次"
            val next = if (job.nextAt > 0) formatScheduleInstant(job.nextAt) else "-"
            val last = job.lastStatus.ifBlank { "-" }
            "$flag $kind ${job.id} 「${job.title}」下次 $next 上次：$last"
        }
    }

    override suspend fun scheduleCancel(id: String): String {
        val store = schedules ?: return "错误：定时存储不可用。"
        val clock = alarms
        val job = store.get(id.trim()) ?: return "错误：没有这条定时（$id）。"
        store.delete(job.id)
        clock?.cancel(job.id)
        return "已取消 ${job.id}「${job.title}」。"
    }

    private fun compressJpeg(src: Bitmap, maxSide: Int = 1280, quality: Int = 72): ByteArray {
        val scaled = if (src.width > maxSide || src.height > maxSide) {
            val ratio = min(maxSide.toFloat() / src.width, maxSide.toFloat() / src.height)
            Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).toInt().coerceAtLeast(1),
                (src.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            src
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== src) scaled.recycle()
        return out.toByteArray()
    }

    private data class Shot(val first: Bitmap, val second: String)

    private suspend fun captureScreen(): Shot? {
        a11y()?.captureBitmap()?.let { return Shot(it, "a11y") }
        if (shizuku.isReady()) {
            val bytes = shizuku.screenshot()
            if (bytes != null && bytes.size > 64) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return Shot(bmp, "shizuku")
            }
        }
        return null
    }

    private suspend fun withFreshTree(status: String, settleMs: Long = 180): String {
        if (status.startsWith("错误：")) return status
        if (settleMs > 0) delay(settleMs)
        val tree = runCatching { uiSnapshot() }.getOrDefault("(snapshot failed)")
        return "$status\n---\n$tree"
    }

    private fun a11y(): MuseAccessibilityService? = MuseAccessibilityService.instance

    private fun launch(intent: Intent, ok: String): String {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ok
        } catch (t: Throwable) {
            "错误：${t.message ?: "无法启动"}"
        }
    }
}
