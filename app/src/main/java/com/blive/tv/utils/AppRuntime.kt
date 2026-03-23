package com.blive.tv.utils

import android.content.Context

object AppRuntime {
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
