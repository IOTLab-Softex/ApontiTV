package br.com.softextv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ApontiBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        Log.i(TAG, "Boot/start receiver action=$action")

        val pendingResult = goAsync()
        Thread {
            try {
                Thread.sleep(BOOT_LAUNCH_DELAY_MS)
                val launchIntent = Intent(context.applicationContext, MainActivity::class.java).apply {
                    setAction(Intent.ACTION_MAIN)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.applicationContext.startActivity(launchIntent)
                ApontiForegroundWatchdogReceiver.schedule(context.applicationContext)
                Log.i(TAG, "Aponti TV opened after boot/start event")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to open Aponti TV after boot/start event", error)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "ApontiBootReceiver"
        private const val BOOT_LAUNCH_DELAY_MS = 8_000L
    }
}
