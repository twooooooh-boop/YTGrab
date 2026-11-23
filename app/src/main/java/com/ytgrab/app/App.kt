package com.ytgrab.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.load(this)
        Engine.init(this)
    }
}
