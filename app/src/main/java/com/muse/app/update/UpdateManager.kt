package com.muse.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.muse.app.BuildConfig
import com.muse.llm.MuseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class RemoteRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val htmlUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpToDate(val current: String, val detail: String? = null) : UpdateState()
    data class Available(val release: RemoteRelease) : UpdateState()
    data class Downloading(val release: RemoteRelease, val received: Long, val total: Long) : UpdateState()
    data class Ready(val release: RemoteRelease, val file: File) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

class UpdateManager(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String = BuildConfig.VERSION_NAME
    val repoLabel: String = "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}"

    suspend fun check() = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("User-Agent", "Muse/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(request).execute().use { resp ->
                if (resp.code == 404) {
                    _state.value = UpdateState.UpToDate(
                        currentVersion,
                        "GitHub 上还没有 Release。",
                    )
                    return@withContext
                }
                if (!resp.isSuccessful) {
                    _state.value = UpdateState.Failed("检查更新失败（HTTP ${resp.code}）。")
                    return@withContext
                }
                val body = resp.body?.string().orEmpty()
                val release = parseLatestRelease(body)
                    ?: run {
                        _state.value = UpdateState.Failed("这个 Release 没有 APK。")
                        return@withContext
                    }
                _state.value = if (isNewerVersion(release.version, currentVersion)) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.UpToDate(currentVersion, "已是最新（${release.tag}）。")
                }
            }
        } catch (t: Throwable) {
            _state.value = UpdateState.Failed(t.message ?: "检查更新失败。")
        }
    }

    suspend fun download() {
        val available = when (val current = _state.value) {
            is UpdateState.Available -> current.release
            is UpdateState.Ready -> current.release
            is UpdateState.Failed -> return
            else -> return
        }
        _state.value = UpdateState.Downloading(available, 0, available.apkSize)
        withContext(Dispatchers.IO) {
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, available.apkName.ifBlank { "Muse-${available.version}.apk" })
                val request = Request.Builder()
                    .url(available.apkUrl)
                    .header("User-Agent", "Muse/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/octet-stream")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        _state.value = UpdateState.Failed("下载失败（HTTP ${resp.code}）。")
                        return@withContext
                    }
                    val body = resp.body
                    if (body == null) {
                        _state.value = UpdateState.Failed("下载为空。")
                        return@withContext
                    }
                    val total = if (body.contentLength() > 0) body.contentLength() else available.apkSize
                    file.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var received = 0L
                        val src = body.byteStream()
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            _state.value = UpdateState.Downloading(available, received, total)
                        }
                    }
                }
                val expected = available.sha256
                if (!expected.isNullOrBlank()) {
                    val actual = sha256Hex(file)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        file.delete()
                        _state.value = UpdateState.Failed("APK SHA-256 校验失败。")
                        return@withContext
                    }
                }
                _state.value = UpdateState.Ready(available, file)
            } catch (t: Throwable) {
                _state.value = UpdateState.Failed(t.message ?: "下载失败。")
            }
        }
    }

    fun install(context: Context): String? {
        val ready = _state.value as? UpdateState.Ready ?: return "还没有下载完成的 APK。"
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}"),
            )
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "请允许 Muse 安装未知应用，然后再点安装。"
        }
        val uri = FileProvider.getUriForFile(
            app,
            "${app.packageName}.fileprovider",
            ready.file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return null
    }
}

fun parseLatestRelease(raw: String): RemoteRelease? {
    val root = runCatching { MuseJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return null
    val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (tag.isBlank()) return null
    val assets = root["assets"] as? JsonArray ?: JsonArray(emptyList())
    val apk = pickApkAsset(assets) ?: return null
    val digest = apk["digest"]?.jsonPrimitive?.contentOrNull
        ?.removePrefix("sha256:")
        ?.removePrefix("SHA256:")
        ?.trim()
        ?.ifBlank { null }
    return RemoteRelease(
        tag = tag,
        version = normalizeVersion(tag),
        notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        apkName = apk["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        apkUrl = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        apkSize = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
        sha256 = digest,
    )
}

internal fun pickApkAsset(assets: JsonArray): JsonObject? {
    val apks = assets.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
        .filter { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", true) == true }
    return apks.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.contains("release", true) == true }
        ?: apks.firstOrNull()
}

private fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
