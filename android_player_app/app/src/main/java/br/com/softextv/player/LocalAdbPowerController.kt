package br.com.softextv.player

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

class LocalAdbPowerController(private val context: Context) {
    fun prepareAuthorization(): Boolean {
        val dadb = connect() ?: return false
        return runCatching {
            dadb.shell("echo aponti_adb_ready")
            true
        }.onFailure {
            Log.w(TAG, "ADB local ainda nao autorizado.", it)
        }.getOrDefault(false)
    }

    fun sleep(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val dadb = connect() ?: return false

        val commands = listOf(
            "input keyevent KEYCODE_SLEEP",
            "input keyevent KEYCODE_POWER",
            "input keyevent 26"
        )

        commands.forEach { command ->
            val sent = runCatching {
                dadb.shell(command)
                true
            }.onFailure {
                Log.w(TAG, "Falha ao executar ADB local: $command", it)
            }.getOrDefault(false)

            if (sent && waitUntilNotInteractive(powerManager)) {
                val asleep = isAsleep(dadb, powerManager)
                Log.i(TAG, "ADB local enviou $command. asleep=$asleep")
                return asleep
            }
        }

        return false
    }

    fun isAsleep(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val dadb = connect()
        return isAsleep(dadb, powerManager)
    }

    fun wake(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val dadb = connect() ?: return false

        val sent = runCatching {
            dadb.shell("input keyevent KEYCODE_WAKEUP")
            true
        }.onFailure {
            Log.w(TAG, "Falha ao executar ADB local para ligar.", it)
        }.getOrDefault(false)

        return sent && waitUntilInteractive(powerManager)
    }

    private fun connect(): Dadb? {
        return runCatching {
            Dadb.create(LOCAL_ADB_HOST, LOCAL_ADB_PORT, adbKeyPair())
        }.onFailure {
            Log.w(TAG, "ADB local indisponivel em $LOCAL_ADB_HOST:$LOCAL_ADB_PORT. Autorize o ADB local se a TV pedir.", it)
        }.getOrNull()
    }

    private fun adbKeyPair(): AdbKeyPair {
        val keyDir = File(context.filesDir, "adb").apply { mkdirs() }
        val privateKey = File(keyDir, "adbkey")
        val publicKey = File(keyDir, "adbkey.pub")

        if (!privateKey.exists() || !publicKey.exists()) {
            AdbKeyPair.generate(privateKey, publicKey)
        }

        return AdbKeyPair.read(privateKey, publicKey)
    }

    private fun waitUntilNotInteractive(powerManager: PowerManager, attempts: Int = 8): Boolean {
        repeat(attempts) {
            Thread.sleep(350L)
            if (!powerManager.isInteractive) return true
        }
        return !powerManager.isInteractive
    }

    private fun isAsleep(dadb: Dadb?, powerManager: PowerManager): Boolean {
        val powerDump = dadb?.let { connected ->
            runCatching { connected.shell("dumpsys power") }
                .onFailure { Log.w(TAG, "Nao foi possivel confirmar energia via dumpsys power.", it) }
                .getOrNull()
                ?.toString()
        }.orEmpty()

        if (powerDump.contains("mWakefulness=Asleep", ignoreCase = true)) return true
        if (powerDump.contains("mHoldingDisplaySuspendBlocker=false", ignoreCase = true)) return true
        if (powerDump.contains("mWakefulness=Awake", ignoreCase = true)) return false

        return !powerManager.isInteractive
    }

    private fun waitUntilInteractive(powerManager: PowerManager, attempts: Int = 8): Boolean {
        repeat(attempts) {
            Thread.sleep(350L)
            if (powerManager.isInteractive) return true
        }
        return powerManager.isInteractive
    }

    companion object {
        private const val TAG = "LocalAdbPower"
        private const val LOCAL_ADB_HOST = "127.0.0.1"
        private const val LOCAL_ADB_PORT = 5555
    }
}
