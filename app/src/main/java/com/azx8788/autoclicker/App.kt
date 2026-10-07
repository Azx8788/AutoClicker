package com.azx8788.autoclicker

import android.app.Application
import com.azx8788.autoclicker.shizuku.ShizukuAutoGrant

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuAutoGrant.boot(this)
    }
}
