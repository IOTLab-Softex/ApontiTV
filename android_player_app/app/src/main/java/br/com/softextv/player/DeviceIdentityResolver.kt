package br.com.softextv.player

import java.net.Inet4Address
import java.net.NetworkInterface

object DeviceIdentityResolver {

    private const val CACHE_TTL_MS = 30_000L

    @Volatile private var cachedAddresses: List<String> = emptyList()
    @Volatile private var cacheExpiresAt: Long = 0L

    fun currentIpv4Address(): String? = currentIpv4Addresses().firstOrNull()

    @Synchronized
    fun currentIpv4Addresses(): List<String> {
        val now = System.currentTimeMillis()
        if (now < cacheExpiresAt && cachedAddresses.isNotEmpty()) return cachedAddresses

        val fresh = resolveAddresses()
        cachedAddresses = fresh
        cacheExpiresAt = now + CACHE_TTL_MS
        return fresh
    }

    fun invalidateCache() {
        cacheExpiresAt = 0L
    }

    private fun resolveAddresses(): List<String> = runCatching {
        (NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.hostAddress.isNullOrBlank() }
            .mapNotNull { it.hostAddress?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
    }.getOrDefault(emptyList())
}
