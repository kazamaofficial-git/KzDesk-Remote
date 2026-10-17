package com.kazamatech.kzdesk.core

import com.freerdp.freerdpcore.services.LibFreeRDP
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DiscoveredPcHost(
    val ip: String,
    val port: Int,
    val pcId: String,
    val rttMs: Int,
    val subnetPrefix: String,
)

object LanScanner {

    fun encodeIpv4ToPcId(ip: String): String? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        val octets = parts.map { it.toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        val num = ((octets[0].toLong() and 0xFFL) shl 24) or
            ((octets[1].toLong() and 0xFFL) shl 16) or
            ((octets[2].toLong() and 0xFFL) shl 8) or
            (octets[3].toLong() and 0xFFL)
        val digits = num.toString().padStart(10, '0')
        return "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6, 10)}"
    }

    fun detectLocalIpv4Address(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                        return@runCatching ip
                    }
                }
            }
        }
        null
    }.getOrNull()

    fun scanLocalSubnet(
        port: Int = 3389,
        timeoutMs: Int = 280,
    ): List<DiscoveredPcHost> {
        val selfIp = detectLocalIpv4Address() ?: return emptyList()
        val parts = selfIp.split('.')
        if (parts.size != 4) return emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        val pool = Executors.newFixedThreadPool(48)
        return try {
            val tasks = (1..254).map { hostOctet ->
                Callable<DiscoveredPcHost?> {
                    val targetIp = "$prefix.$hostOctet"
                    if (targetIp == selfIp) return@Callable null
                    val startNs = System.nanoTime()
                    val quicJson = runCatching {
                        LibFreeRDP.quicProbeHost(targetIp, 4433, 150)
                    }.getOrDefault("")
                    if (quicJson.contains("\"ok\":true")) {
                        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L)
                            .toInt()
                            .coerceAtLeast(1)
                        val pcId = encodeIpv4ToPcId(targetIp) ?: targetIp
                        return@Callable DiscoveredPcHost(
                            ip = targetIp,
                            port = port,
                            pcId = pcId,
                            rttMs = elapsedMs,
                            subnetPrefix = "$prefix.0/24 (QUIC)",
                        )
                    }
                    try {
                        Socket().use { socket ->
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(targetIp, port), timeoutMs)
                        }
                        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L)
                            .toInt()
                            .coerceAtLeast(1)
                        val pcId = encodeIpv4ToPcId(targetIp) ?: targetIp
                        DiscoveredPcHost(
                            ip = targetIp,
                            port = port,
                            pcId = pcId,
                            rttMs = elapsedMs,
                            subnetPrefix = "$prefix.0/24",
                        )
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
            val futures = pool.invokeAll(tasks, 12L, TimeUnit.SECONDS)
            futures.mapNotNull { fut ->
                runCatching { if (fut.isDone && !fut.isCancelled) fut.get() else null }.getOrNull()
            }.sortedBy { it.rttMs }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }
}
