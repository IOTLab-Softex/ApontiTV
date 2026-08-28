package br.com.softextv.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LocalPowerScheduleManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class EvaluationResult(
        val action: String? = null,
        val due: Boolean = false,
        val success: Boolean = false
    ) {
        val shouldEnterStandby: Boolean
            get() = action == "off" && due
    }

    fun remember(channel: TvChannel) {
        remember(channel.id, channel.name, channel.powerSchedule)
    }

    fun remember(channelId: Long, channelName: String?, schedule: TvPowerSchedule?) {
        if (channelId <= 0L || schedule == null) return

        prefs.edit()
            .putLong(KEY_CHANNEL_ID, channelId)
            .putString(KEY_CHANNEL_NAME, channelName.orEmpty())
            .putBoolean(KEY_ENABLED, schedule.enabled)
            .putString(KEY_ON_TIME, schedule.onTime.orEmpty())
            .putString(KEY_OFF_TIME, schedule.offTime.orEmpty())
            .putString(KEY_DISABLED_WEEKDAYS, schedule.disabledWeekdays.joinToString(","))
            .putLong(KEY_SERVER_TIME_MS, schedule.serverTimeMs)
            .putLong(KEY_SAVED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun evaluate(serverAvailable: Boolean): EvaluationResult {
        if (!prefs.getBoolean(KEY_ENABLED, false)) return EvaluationResult()
        if (serverAvailable) return EvaluationResult()

        val now = Calendar.getInstance()
        val todayKey = DAY_KEY_FORMAT.format(Date(now.timeInMillis))
        val weekday = now.get(Calendar.DAY_OF_WEEK) - 1
        val disabledWeekdays = prefs.getString(KEY_DISABLED_WEEKDAYS, null)
            .orEmpty()
            .split(",")
            .mapNotNull { it.toIntOrNull() }
            .toSet()

        if (weekday in disabledWeekdays) {
            return executeOnce("off", todayKey, prefs.getString(KEY_OFF_TIME, null), now)
        }

        val onResult = executeOnce("on", todayKey, prefs.getString(KEY_ON_TIME, null), now)
        val offResult = executeOnce("off", todayKey, prefs.getString(KEY_OFF_TIME, null), now)
        return if (offResult.due) offResult else onResult
    }

    private fun executeOnce(action: String, todayKey: String, time: String?, now: Calendar): EvaluationResult {
        val minuteOfDay = parseMinuteOfDay(time) ?: return EvaluationResult()
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (nowMinute < minuteOfDay || nowMinute > minuteOfDay + EXECUTION_GRACE_MINUTES) return EvaluationResult()

        val key = "${KEY_LAST_ACTION_PREFIX}_${action}_$todayKey"
        if (prefs.getBoolean(key, false)) {
            if (action != "off" || LocalAdbPowerController(context).isAsleep()) {
                return EvaluationResult(action = action, due = true, success = true)
            }

            Log.w(TAG, "Desligamento local estava marcado como executado, mas a TV continua acordada. Tentando novamente.")
            prefs.edit().remove(key).apply()
        }

        val success = when (action) {
            "on" -> wakeUp()
            "off" -> {
                scheduleNextWakeAlarm(now)
                sleep() && LocalAdbPowerController(context).isAsleep()
            }
            else -> false
        }

        if (success) {
            prefs.edit().putBoolean(key, true).apply()
        }
        Log.i(TAG, "Fallback local de energia executado: action=$action success=$success")
        return EvaluationResult(action = action, due = true, success = success)
    }

    private fun wakeUp(): Boolean {
        val localAdbResult = LocalAdbPowerController(context).wake()
        if (localAdbResult) return true

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val reflected = runCatching {
            val method = powerManager.javaClass.getMethod("wakeUp", Long::class.javaPrimitiveType)
            method.invoke(powerManager, SystemClock.uptimeMillis())
            true
        }.getOrDefault(false)
        if (reflected) return true

        val wakeLockResult = runCatching {
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "$TAG:wake"
            )
            wakeLock.acquire(10_000L)
            wakeLock.release()
            true
        }.getOrDefault(false)
        if (wakeLockResult) return true

        return runShellKeyevent("KEYCODE_WAKEUP")
    }

    private fun sleep(): Boolean {
        return LocalAdbPowerController(context).sleep()
    }

    private fun scheduleNextWakeAlarm(now: Calendar) {
        val onMinute = parseMinuteOfDay(prefs.getString(KEY_ON_TIME, null)) ?: return
        val alarmAt = now.clone() as Calendar
        alarmAt.set(Calendar.HOUR_OF_DAY, onMinute / 60)
        alarmAt.set(Calendar.MINUTE, onMinute % 60)
        alarmAt.set(Calendar.SECOND, 0)
        alarmAt.set(Calendar.MILLISECOND, 0)
        if (alarmAt.timeInMillis <= now.timeInMillis) {
            alarmAt.add(Calendar.DAY_OF_YEAR, 1)
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Sem permissao SCHEDULE_EXACT_ALARM. Pulando alarme local de ligar.")
            return
        }

        val pendingIntent = wakeAlarmPendingIntent(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmAt.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, alarmAt.timeInMillis, pendingIntent)
            }
        }.onFailure {
            Log.w(TAG, "Nao foi possivel agendar alarme local de ligar.", it)
            return
        }
        Log.i(TAG, "Alarme local de ligar agendado para ${Date(alarmAt.timeInMillis)}")
    }

    private fun runShellKeyevent(keycode: String): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keycode))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            if (exitCode != 0) {
                Log.w(TAG, "Keyevent local $keycode falhou: exit=$exitCode output=$output error=$error")
            }
            exitCode == 0
        }.getOrElse {
            Log.w(TAG, "Falha no keyevent local $keycode.", it)
            false
        }
    }

    private fun parseMinuteOfDay(time: String?): Int? {
        val match = Regex("""\A(\d{1,2}):(\d{2})""").find(time.orEmpty()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    companion object {
        private const val TAG = "LocalPowerSchedule"
        private const val PREFS_NAME = "local_power_schedule"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ON_TIME = "on_time"
        private const val KEY_OFF_TIME = "off_time"
        private const val KEY_DISABLED_WEEKDAYS = "disabled_weekdays"
        private const val KEY_SERVER_TIME_MS = "server_time_ms"
        private const val KEY_SAVED_AT_MS = "saved_at_ms"
        private const val KEY_LAST_ACTION_PREFIX = "last_action"
        private const val EXECUTION_GRACE_MINUTES = 3
        const val ACTION_WAKE_FOR_POWER_SCHEDULE = "br.com.softextv.player.ACTION_WAKE_FOR_POWER_SCHEDULE"
        private val DAY_KEY_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US)

        fun wakeAlarmPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, LocalPowerScheduleReceiver::class.java)
                .setAction(ACTION_WAKE_FOR_POWER_SCHEDULE)
            return PendingIntent.getBroadcast(
                context,
                3207,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
