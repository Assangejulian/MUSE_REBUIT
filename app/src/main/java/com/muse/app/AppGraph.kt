package com.muse.app

import android.content.Context
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.muse.agent.AgentRuntime
import com.muse.agent.DevicePort
import com.muse.agent.HttpPort
import com.muse.agent.MemoryPort
import com.muse.agent.NotePort
import com.muse.agent.OkHttpFetcher
import com.muse.agent.WebSearcher
import com.muse.llm.DeepSeekClient
import com.muse.memory.MemoryFileStore
import com.muse.memory.MuseDatabase
import com.muse.memory.NoteStore
import com.muse.memory.SessionRepository
import com.muse.app.update.UpdateManager
import com.muse.memory.SettingsStore
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class AppGraph(context: Context) {
    private val app = context.applicationContext
    val settings = SettingsStore(app)
    val db = MuseDatabase.create(app)
    val sessions = SessionRepository(db)
    val memoryFiles = MemoryFileStore(app)
    val notes = NoteStore(db)
    val okHttp = AndroidHttp.client(app)
    val llm = DeepSeekClient(http = okHttp, allowLoopback = false)
    val http: HttpPort = OkHttpFetcher(okHttp)
    val search = WebSearcher(okHttp)
    val shizuku = ShizukuGateway(app)
    val overlay = CotOverlay(app)
    val actions = AndroidActions(app, shizuku)
    val memoryPort = object : MemoryPort {
        override suspend fun read(): String = memoryFiles.read()
        override suspend fun write(op: String, text: String): String = memoryFiles.write(op, text)
    }
    val notePort = object : NotePort {
        override suspend fun save(title: String, body: String): String = notes.save(title, body)
    }
    val devicePort = AndroidDeviceStatus(app, shizuku)
    val agent = AgentRuntime(
        llm = llm,
        memory = memoryPort,
        notes = notePort,
        device = devicePort,
        http = http,
        search = search,
        actions = actions,
    )
    val updates = UpdateManager(app, okHttp)
}

class AndroidDeviceStatus(
    private val context: Context,
    private val shizuku: ShizukuGateway,
) : DevicePort {
    override suspend fun status(): String {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = battery.isCharging
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val now = ZonedDateTime.now()
        return buildString {
            append("time=").append(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            append('\n')
            append("timezone=").append(ZoneId.systemDefault().id)
            append('\n')
            append("battery=").append(percent).append('%')
            append('\n')
            append("charging=").append(charging)
            append('\n')
            append("network=").append(network)
            append('\n')
            append(shizuku.statusLine())
        }
    }
}
