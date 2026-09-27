package com.tvplayer.app

import android.app.Application
import com.tvplayer.app.data.Http

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Http.init(this)
    }
}
