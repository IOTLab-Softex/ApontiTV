package br.com.softextv.player

import android.app.Activity
import android.content.pm.ActivityInfo

object AppLayoutRotationApplier {
    fun apply(activity: Activity, settings: ServerEndpointSettings) {
        activity.requestedOrientation = when (settings.appLayoutRotation()) {
            ServerEndpointSettings.AppLayoutRotation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ServerEndpointSettings.AppLayoutRotation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ServerEndpointSettings.AppLayoutRotation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ServerEndpointSettings.AppLayoutRotation.PORTRAIT_INVERTED -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
    }
}
