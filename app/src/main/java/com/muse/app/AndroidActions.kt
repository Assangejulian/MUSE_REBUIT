package com.muse.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.muse.agent.ActionPort
import com.muse.agent.UrlBlocked
import com.muse.agent.UrlGuard
import com.muse.agent.compactUiDump
import kotlinx.coroutines.delay

class AndroidActions(
    context: Context,
    private val shizuku: ShizukuGateway,
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
        val pm = app.packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launch, PackageManager.MATCH_DEFAULT_ONLY)
        val matches = apps.map { info ->
            val label = info.loadLabel(pm).toString()
            val pkg = info.activityInfo.packageName
            Triple(label, pkg, info)
        }.filter { (label, pkg, _) ->
            label.contains(needle, ignoreCase = true) || pkg.contains(needle, ignoreCase = true)
        }
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
        return launch(intent, "已打开 ${chosen.first}。")
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

    override suspend fun tap(x: Int, y: Int): String =
        shizuku.exec("input tap $x $y").let { if (it.startsWith("错误：")) it else "已 tap ($x,$y)\n$it" }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): String =
        shizuku.exec("input swipe $x1 $y1 $x2 $y2 300").let {
            if (it.startsWith("错误：")) it else "已 swipe ($x1,$y1)->($x2,$y2)\n$it"
        }

    override suspend fun type(text: String): String {
        if (text.isBlank()) return "错误：text 为空。"
        val ascii = text.all { it.code < 128 }
        return if (ascii) {
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
    }

    override suspend fun key(name: String): String {
        val code = when (name.trim().uppercase()) {
            "BACK" -> 4
            "HOME" -> 3
            "ENTER", "RETURN" -> 66
            "RECENTS", "APP_SWITCH" -> 187
            "DELETE", "DEL" -> 67
            "PASTE" -> 279
            else -> name.toIntOrNull()
        } ?: return "错误：未知按键 $name。可用 BACK / HOME / ENTER / RECENTS / DELETE / PASTE。"
        return shizuku.exec("input keyevent $code")
    }

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
