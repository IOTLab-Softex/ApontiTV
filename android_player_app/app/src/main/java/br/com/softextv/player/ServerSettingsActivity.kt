package br.com.softextv.player

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ServerSettingsActivity : AppCompatActivity() {
    private val endpointSettings by lazy { ServerEndpointSettings(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLayoutRotationApplier.apply(this, endpointSettings)
        setContentView(R.layout.activity_server_settings)

        val modeGroup = findViewById<RadioGroup>(R.id.accessModeGroup)
        val appLayoutRotationGroup = findViewById<RadioGroup>(R.id.appLayoutRotationGroup)
        val localInput = findViewById<EditText>(R.id.localServerInput)
        val externalInput = findViewById<EditText>(R.id.externalServerInput)
        val cancelButton = findViewById<MaterialButton>(R.id.serverSettingsCancelButton)
        val saveButton = findViewById<MaterialButton>(R.id.serverSettingsSaveButton)

        localInput.setText(endpointSettings.localUrl())
        externalInput.setText(endpointSettings.externalUrl())
        modeGroup.check(
            when (endpointSettings.mode()) {
                ServerEndpointSettings.AccessMode.LOCAL -> R.id.accessModeLocal
                ServerEndpointSettings.AccessMode.EXTERNAL -> R.id.accessModeExternal
                ServerEndpointSettings.AccessMode.AUTO -> R.id.accessModeAuto
            }
        )
        appLayoutRotationGroup.check(
            when (endpointSettings.appLayoutRotation()) {
                ServerEndpointSettings.AppLayoutRotation.LANDSCAPE -> R.id.appLayoutRotationLandscape
                ServerEndpointSettings.AppLayoutRotation.PORTRAIT -> R.id.appLayoutRotationPortrait
                ServerEndpointSettings.AppLayoutRotation.PORTRAIT_INVERTED -> R.id.appLayoutRotationPortraitInverted
                ServerEndpointSettings.AppLayoutRotation.SYSTEM -> R.id.appLayoutRotationSystem
            }
        )

        cancelButton.setOnClickListener { finish() }
        saveButton.setOnClickListener {
            val mode = when (modeGroup.checkedRadioButtonId) {
                R.id.accessModeLocal -> ServerEndpointSettings.AccessMode.LOCAL
                R.id.accessModeExternal -> ServerEndpointSettings.AccessMode.EXTERNAL
                else -> ServerEndpointSettings.AccessMode.AUTO
            }
            val appLayoutRotation = when (appLayoutRotationGroup.checkedRadioButtonId) {
                R.id.appLayoutRotationLandscape -> ServerEndpointSettings.AppLayoutRotation.LANDSCAPE
                R.id.appLayoutRotationPortrait -> ServerEndpointSettings.AppLayoutRotation.PORTRAIT
                R.id.appLayoutRotationPortraitInverted -> ServerEndpointSettings.AppLayoutRotation.PORTRAIT_INVERTED
                else -> ServerEndpointSettings.AppLayoutRotation.SYSTEM
            }
            endpointSettings.save(
                mode = mode,
                localUrl = localInput.text?.toString().orEmpty(),
                externalUrl = externalInput.text?.toString().orEmpty(),
                appLayoutRotation = appLayoutRotation
            )
            AppLayoutRotationApplier.apply(this, endpointSettings)
            Toast.makeText(this, R.string.server_config_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<View>(R.id.accessModeAuto).requestFocus()
    }

    override fun onStart() {
        super.onStart()
        ApontiForegroundState.markForeground(applicationContext)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        ApontiForegroundState.markBackground(applicationContext)
        ApontiForegroundWatchdogReceiver.scheduleSoon(applicationContext)
    }

    override fun onStop() {
        ApontiForegroundState.markBackground(applicationContext)
        ApontiForegroundWatchdogReceiver.scheduleSoon(applicationContext)
        super.onStop()
    }
}
