package br.com.softextv.player

import android.os.Build

object DevicePlatform {
    fun isFireTv(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        val device = Build.DEVICE.orEmpty().lowercase()
        val product = Build.PRODUCT.orEmpty().lowercase()

        return manufacturer.contains("amazon") ||
            brand.contains("amazon") ||
            model.startsWith("aft") ||
            device.startsWith("aft") ||
            product.startsWith("aft")
    }
}
