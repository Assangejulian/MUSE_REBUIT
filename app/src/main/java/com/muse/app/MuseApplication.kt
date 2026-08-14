package com.muse.app

import android.app.Application
import kotlinx.coroutines.launch

class MuseApplication : Application() {
    lateinit var graph: AppGraph
        private set
    var taskHost: TaskHost? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        ).launch {
            graph.scheduleHost.resync()
        }
    }
}

interface TaskHost {
    fun enterTaskMode()
    fun exitTaskMode()
}
