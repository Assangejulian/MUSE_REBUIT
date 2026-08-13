package com.muse.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

class ShizukuGateway(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var remote: IMuseShell? = null
    private var bindWait: CompletableDeferred<IMuseShell>? = null

    private val args = Shizuku.UserServiceArgs(ComponentName(app, MuseShellService::class.java))
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val stub = IMuseShell.Stub.asInterface(service)
            remote = stub
            bindWait?.complete(stub)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener {
        // no-op; bind happens on demand
    }

    init {
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderListener) }
    }

    fun statusLine(): String {
        val binder = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val perm = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        val uid = runCatching { Shizuku.getUid() }.getOrNull()
        return buildString {
            append("shizuku_binder=").append(binder)
            append('\n')
            append("shizuku_permission=").append(perm)
            append('\n')
            append("shizuku_uid=").append(uid ?: "n/a")
            append('\n')
            append("user_service=").append(if (remote != null) "bound" else "idle")
        }
    }

    fun isReady(): Boolean =
        runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    fun requestPermission(): String {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return "Shizuku 未运行。请先打开 Shizuku App，用无线调试或电脑 ADB 启动。"
        }
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "Shizuku 已授权。"
        } else {
            Shizuku.requestPermission(REQ)
            "已弹出 Shizuku 授权，请点允许。"
        }
    }

    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        val deny = com.muse.agent.ShellPolicy.denyReason(command)
        if (deny != null) return@withContext "错误：$deny"
        if (!isReady()) {
            return@withContext "错误：Shizuku 未连接。打开 Shizuku App 启动服务，再在 Muse 设置里授权。"
        }
        val shell = mutex.withLock { ensureBound() }
            ?: return@withContext "错误：无法绑定 Shizuku UserService。"
        try {
            withTimeout(18_000) { shell.exec(command) }
        } catch (t: Throwable) {
            "错误：${t.message ?: t::class.java.simpleName}"
        }
    }

    private suspend fun ensureBound(): IMuseShell? {
        remote?.let { return it }
        if (!isReady()) return null
        val wait = CompletableDeferred<IMuseShell>()
        bindWait = wait
        return try {
            Shizuku.bindUserService(args, connection)
            withTimeout(8_000) { wait.await() }
        } catch (_: Throwable) {
            remote
        }
    }

    companion object {
        const val REQ = 0x5131
    }
}
