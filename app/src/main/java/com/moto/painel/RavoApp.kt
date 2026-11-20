package com.moto.painel

import android.app.Application

// Guarda o erro quando o app fecha, para mostrar na próxima abertura
class RavoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Diagnostics.crash(this, error)
            getSharedPreferences("crash", MODE_PRIVATE).edit()
                .putString("log", error.stackTraceToString())
                .commit()
            previous?.uncaughtException(thread, error)
        }
    }
}
