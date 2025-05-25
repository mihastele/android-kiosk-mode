package com.example.kioskmodeapplication

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.os.Handler
import android.os.Looper

class KioskService : Service() {

    companion object {
        private const val TAG = "KioskService"
        private const val INTERVAL = 1000L // check every second
        const val EXTRA_TARGET_PACKAGE = "TARGET_PACKAGE"
    }

    private var isRunning = false
    private var targetPackage: String? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                checkForegroundApp()
                handler.postDelayed(this, INTERVAL)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetPackage = intent?.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (!isRunning) {
            isRunning = true
            handler.post(checkRunnable)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        isRunning = false
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun checkForegroundApp() {
        try {
            val foregroundApp = getForegroundAppPackage()
            val target = targetPackage ?: return
            
            if (foregroundApp != null && foregroundApp != target) {
                // If foreground app is not our target app, bring target app to front
                val launchIntent = packageManager.getLaunchIntentForPackage(target)
                if (launchIntent != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    startActivity(launchIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking foreground app: ${e.message}")
        }
    }
    
    @Suppress("DEPRECATION")
    private fun getForegroundAppPackage(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val time = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                time - 1000 * 1000,
                time
            )
            
            var lastUsedApp: String? = null
            var lastUsedTime: Long = 0
            
            for (usageStats in stats) {
                if (usageStats.lastTimeUsed > lastUsedTime) {
                    lastUsedApp = usageStats.packageName
                    lastUsedTime = usageStats.lastTimeUsed
                }
            }
            
            lastUsedApp
        } catch (e: Exception) {
            Log.e(TAG, "Error getting foreground app: ${e.message}")
            null
        }
    }
}