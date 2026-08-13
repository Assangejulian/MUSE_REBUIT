package com.muse.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.muse.agent.ActionPort
import com.muse.agent.UrlBlocked
import com.muse.agent.UrlGuard

class AndroidActions(context: Context) : ActionPort {
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
