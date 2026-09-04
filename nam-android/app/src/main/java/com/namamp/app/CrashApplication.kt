package com.namamp.app

import android.app.Application
import android.content.Intent
import android.os.Process
import kotlin.system.exitProcess

class CrashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val trace = throwable.stackTraceToString()
                getSharedPreferences("crash", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", trace)
                    .commit()
                val intent = Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                // If even the crash handler fails, fall through below.
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
