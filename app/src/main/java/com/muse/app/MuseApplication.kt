package com.muse.app

import android.app.Application

class MuseApplication : Application() {
    lateinit var graph: AppGraph
        private set
    var taskHost: TaskHost? = null

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

interface TaskHost {
    fun enterTaskMode()
    fun exitTaskMode()
}
