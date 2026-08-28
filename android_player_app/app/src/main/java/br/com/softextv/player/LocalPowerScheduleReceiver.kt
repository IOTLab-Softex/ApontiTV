package br.com.softextv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class LocalPowerScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocalPowerScheduleManager.ACTION_WAKE_FOR_POWER_SCHEDULE) return

        runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "LocalPowerSchedule:wakeReceiver"
            )
            wakeLock.acquire(12_000L)
            wakeLock.release()
        }.onFailure {
            Log.w("LocalPowerSchedule", "Nao foi possivel acordar pelo receiver.", it)
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (launchIntent != null) {
            context.startActivity(launchIntent)
        }
    }
}
