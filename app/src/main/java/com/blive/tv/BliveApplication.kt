package com.blive.tv

import android.app.Application
import com.blive.tv.utils.AppRuntime

class BliveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppRuntime.init(this)
    }
}
