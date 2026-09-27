package com.tvplayer.app

import android.app.Application
import com.tvplayer.app.data.Http
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        // If the app crashes, keep the error so it can be shown (and sent) next time it opens.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = android.util.Log.getStackTraceString(error)
                File(filesDir, CRASH_FILE).writeText("${BuildConfig.VERSION_NAME}\n$trace".take(6000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
