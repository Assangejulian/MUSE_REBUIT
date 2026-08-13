package com.muse.app

import kotlin.concurrent.thread

class MuseShellService : IMuseShell.Stub {
    constructor()
    @Suppress("unused")
    constructor(context: android.content.Context)

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = thread {
            process.inputStream.bufferedReader().use { it.forEachLine { line -> stdout.append(line).append('\n') } }
        }
        val errThread = thread {
            process.errorStream.bufferedReader().use { it.forEachLine { line -> stderr.append(line).append('\n') } }
        }
        val finished = process.waitFor()
        outThread.join(3_000)
        errThread.join(1_000)
        val body = (stdout.toString() + stderr.toString()).trim()
        return "exit=$finished\n${body.take(8_000)}"
    }

    companion object {
        const val DUMP_PATH = "/data/local/tmp/muse_ui.xml"

        fun dumpCommand(): String =
            "uiautomator dump $DUMP_PATH; cat $DUMP_PATH"
    }
}
