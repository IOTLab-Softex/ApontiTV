package br.com.softextv.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class ApontiForegroundWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CHECK_FOREGROUND) return

        try {
            if (ApontiForegroundState.shouldRelaunch(context)) {
                openAponti(context)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Foreground watchdog failed", error)
        } finally {
            schedule(context)
        }
    }

    companion object {
        const val ACTION_CHECK_FOREGROUND = "br.com.softextv.player.action.CHECK_FOREGROUND"
        private const val TAG = "ApontiWatchdog"
        private const val REQUEST_CODE = 4107
        private const val DEFAULT_INTERVAL_MS = 5_000L
        private const val FIRE_TV_INTERVAL_MS = 1_500L
        private const val SOON_INTERVAL_MS = 750L

        fun schedule(context: Context) {
            schedule(context, intervalMs(context))
        }

        fun scheduleSoon(context: Context) {
            schedule(context, SOON_INTERVAL_MS)
        }

        private fun schedule(context: Context, delayMs: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = pendingIntent(context)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                }
            }.onFailure {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ApontiForegroundWatchdogReceiver::class.java).apply {
                action = ACTION_CHECK_FOREGROUND
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openAponti(context: Context) {
            val launchIntent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context.applicationContext, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            context.applicationContext.startActivity(launchIntent)
            Log.i(TAG, "Aponti TV relaunched by foreground watchdog")
        }

        private fun intervalMs(context: Context): Long {
            val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
            val model = Build.MODEL.orEmpty().lowercase()
            val brand = Build.BRAND.orEmpty().lowercase()
            val isFireTv = manufacturer.contains("amazon") ||
                brand.contains("amazon") ||
                model.contains("aft")

            return if (isFireTv) FIRE_TV_INTERVAL_MS else DEFAULT_INTERVAL_MS
        }
    }
}
