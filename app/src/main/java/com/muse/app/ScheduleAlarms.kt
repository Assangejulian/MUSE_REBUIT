package com.muse.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.muse.memory.ScheduleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScheduleAlarms(context: Context) {
    private val app = context.applicationContext
    private val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canExact(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true

    fun set(job: ScheduleEntity) {
        if (!job.enabled || job.nextAt <= 0L) {
            cancel(job.id)
            return
        }
        val pi = pending(job.id)
        val at = job.nextAt.coerceAtLeast(System.currentTimeMillis() + 3_000)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(id: String) {
        am.cancel(pending(id))
    }

    fun reschedule(jobs: List<ScheduleEntity>) {
        jobs.forEach { set(it) }
    }

    private fun pending(id: String): PendingIntent {
        val intent = Intent(app, ScheduleReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            app,
            code(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE = "com.muse.app.SCHEDULE_FIRE"
        const val EXTRA_ID = "schedule_id"

        fun code(id: String): Int {
            var h = 0
            for (c in id) h = 31 * h + c.code
            return h and 0x7fffffff
        }
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduleAlarms.ACTION_FIRE) return
        val id = intent.getStringExtra(ScheduleAlarms.EXTRA_ID) ?: return
        val app = context.applicationContext as? MuseApplication ?: return
        val pending = goAsync()
        app.graph.scheduleHost.fire(id) {
            pending.finish()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val app = context.applicationContext as? MuseApplication ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.graph.scheduleHost.resync()
            } finally {
                pending.finish()
            }
        }
    }
}
