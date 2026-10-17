package com.kazamatech.kzdesk.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Utilitas Wake-on-LAN (WoL) + poller kesiapan port TCP RDP + generator
 * perintah SSH Tunnel / Jump-Host.
 *
 * Bagian [normalizeMac], [parseMacBytes], [buildMagicPacket], dan
 * [buildSshTunnelCommand] murni bebas Android Context sehingga bisa diuji
 * langsung di JVM unit test.
 */
object WakeOnLan {

    const val MAGIC_PACKET_SIZE = 102

    /**
     * Normalisasi alamat MAC (`AA:BB:CC:DD:EE:FF`, `AA-BB-CC-DD-EE-FF`, atau
     * `AABBCCDDEEFF`) ke bentuk kanonik `AA:BB:CC:DD:EE:FF`.
     * Mengembalikan `null` bila jumlah digit heksadesimal bukan 12.
     */
    fun normalizeMac(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace(":", "").replace("-", "").replace(".", "").uppercase()
        if (cleaned.length != 12) return null
        if (!cleaned.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return cleaned.chunked(2).joinToString(":")
    }

    /** Ubah alamat MAC ke 6 byte, atau `null` bila format tidak sah. */
    fun parseMacBytes(raw: String?): ByteArray? {
        val norm = normalizeMac(raw) ?: return null
        val parts = norm.split(':')
        if (parts.size != 6) return null
        return ByteArray(6) { i -> parts[i].toInt(16).toByte() }
    }

    /**
     * Bangun paket sakti (Magic Packet) Wake-on-LAN 102 byte:
     * 6 byte `0xFF` diikuti 16 kali pengulangan 6 byte MAC target.
     */
    fun buildMagicPacket(mac: String): ByteArray {
        val macBytes = parseMacBytes(mac)
            ?: throw IllegalArgumentException("Alamat MAC tidak valid: $mac")
        val packet = ByteArray(MAGIC_PACKET_SIZE)
        for (i in 0 until 6) {
            packet[i] = 0xFF.toByte()
        }
        for (rep in 0 until 16) {
            System.arraycopy(macBytes, 0, packet, 6 + rep * 6, 6)
        }
        return packet
    }

    /**
     * Kirim UDP broadcast Magic Packet ke [broadcastHost]:[port] (dan port 7
     * sebagai cadangan standar WoL). Wajib dipanggil dari background thread.
     */
    fun sendMagicPacket(
        mac: String,
        broadcastHost: String = "255.255.255.255",
        port: Int = 9,
    ) {
        val payload = buildMagicPacket(mac)
        val targetAddress = InetAddress.getByName(broadcastHost.ifBlank { "255.255.255.255" })
        val safePort = port.coerceIn(1, 65535)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(payload, payload.size, targetAddress, safePort))
            if (safePort != 7) {
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, targetAddress, 7))
                }
            }
        }
    }

    /**
     * Poll kesiapan port TCP (default 3389) sesudah Magic Packet dikirim.
     * Mengembalikan `true` begitu port menerima koneksi TCP, atau `false`
     * jika [timeoutMs] habis.
     */
    fun pollHostReady(
        host: String,
        port: Int = 3389,
        timeoutMs: Long = 30_000L,
        intervalMs: Long = 1_500L,
        onTick: ((elapsedMs: Long) -> Unit)? = null,
    ): Boolean {
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            onTick?.invoke(elapsed)
            val reachable = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 1_200)
                }
                true
            } catch (_: Throwable) {
                false
            }
            if (reachable) return true
            if (elapsed >= timeoutMs) return false
            try {
                Thread.sleep(intervalMs.coerceAtLeast(250L))
            } catch (_: InterruptedException) {
                return false
            }
        }
    }

    /**
     * Bangun perintah CLI `ssh -N -L` untuk port-forwarding RDP lewat
     * bastion/jump-host SSH ke [targetHost]:[targetPort].
     */
    fun buildSshTunnelCommand(
        targetHost: String,
        targetPort: Int,
        options: RdpOptions,
    ): String? {
        val sshHost = options.sshHost?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val localPort = if (options.sshLocalPort in 1..65535) options.sshLocalPort else 13389
        val sshPort = if (options.sshPort in 1..65535) options.sshPort else 22
        val sshUserPrefix = options.sshUser?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it@" }.orEmpty()
        val formattedTarget = if (targetHost.contains(':') && !targetHost.startsWith('[')) {
            "[$targetHost]"
        } else {
            targetHost
        }
        return buildString {
            append("ssh -N -L ")
            append(localPort).append(':').append(formattedTarget).append(':').append(targetPort)
            append(' ').append(sshUserPrefix).append(sshHost)
            if (sshPort != 22) {
                append(" -p ").append(sshPort)
            }
        }
    }
}
