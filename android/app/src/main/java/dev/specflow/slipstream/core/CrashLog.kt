package dev.specflow.slipstream.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * The one thing that makes a crash on a phone with no adb attached
 * debuggable: catch it, write the full trace to a file this app owns, and
 * show it on the very next launch. Nothing here can fix a native crash — the
 * process is already gone before a JVM handler ever runs — but for anything
 * that throws in Kotlin, this turns "it just crashes" into a stack trace
 * someone can read, or copy out and send.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            // The system's own crash handling (a debugger, Play Vitals, the
            // "app has stopped" dialog) still needs to run after this, so the
            // previous handler is always called rather than swallowing it.
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("Slipstream crash report")
            appendLine("Time: ${java.util.Date()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        File(context.filesDir, FILE_NAME).writeText(report)
    }

    /** The last crash report, if one is waiting to be shown; null once cleared. */
    fun pending(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.exists()) file.readText() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
