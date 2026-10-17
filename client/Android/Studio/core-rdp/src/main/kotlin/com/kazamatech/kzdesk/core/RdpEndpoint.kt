package com.kazamatech.kzdesk.core

/** Parsed host and optional RDP port from the single address field. */
data class RdpEndpoint(val host: String, val port: Int = 3389) {
    init {
        require(host.isNotBlank()) { "host wajib diisi" }
        require(port in 1..65535) { "port harus 1-65535" }
    }
}

/**
 * Accepts a hostname/IP with an optional port. IPv6 addresses may be entered
 * bare when using the default port, or bracketed when an explicit port follows:
 * `pc.example`, `pc.example:3390`, `192.0.2.4:3390`, `[2001:db8::1]:3390`.
 */
fun parseRdpEndpoint(input: String, defaultPort: Int = 3389): RdpEndpoint? {
    if (defaultPort !in 1..65535) return null
    val value = input.trim()
    if (value.isEmpty() || value.any { it.isWhitespace() }) return null

    val host: String
    val port: Int
    if (value.startsWith("[")) {
        val end = value.indexOf(']')
        if (end <= 1) return null
        host = value.substring(1, end)
        val suffix = value.substring(end + 1)
        if (':' !in host || (suffix.isNotEmpty() && !suffix.startsWith(':'))) return null
        port = if (suffix.isEmpty()) defaultPort else {
            if (suffix.length == 1 || suffix.substring(1).any { !it.isDigit() }) return null
            suffix.substring(1).toIntOrNull() ?: return null
        }
    } else {
        when (value.count { it == ':' }) {
            0 -> {
                host = value
                port = defaultPort
            }
            1 -> {
                val colon = value.indexOf(':')
                host = value.substring(0, colon)
                val portText = value.substring(colon + 1)
                if (portText.isEmpty() || portText.any { !it.isDigit() }) return null
                port = portText.toIntOrNull() ?: return null
            }
            else -> {
                // Unbracketed IPv6 is only unambiguous with the default port.
                host = value
                port = defaultPort
            }
        }
    }
    if (host.isBlank() || port !in 1..65535) return null
    return RdpEndpoint(host, port)
}

/** Format an existing saved profile into the one-field address syntax. */
fun formatRdpEndpoint(host: String, port: Int): String {
    val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return if (port == 3389) formattedHost else "$formattedHost:$port"
}

/**
 * Konversi alamat IPv4 (mis. `192.168.1.50` atau `100.84.12.9`) menjadi
 * ID PC numerik 10 digit berformat `XXX-XXX-XXXX` (mis. `323-223-5826`).
 */
fun encodeIpv4ToPcId(ip: String): String? {
    val parts = ip.trim().split('.')
    if (parts.size != 4) return null
    val octets = parts.map { it.toIntOrNull() ?: return null }
    if (octets.any { it !in 0..255 }) return null
    val num = ((octets[0].toLong() and 0xFF) shl 24) or
        ((octets[1].toLong() and 0xFF) shl 16) or
        ((octets[2].toLong() and 0xFF) shl 8) or
        (octets[3].toLong() and 0xFF)
    val digits = num.toString().padStart(10, '0')
    return "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6, 10)}"
}

/**
 * Parse legacy ID PC (IPv4 encoded as decimal/hex) or a host/IP into [RdpEndpoint].
 * This is a direct address conversion, not a global rendezvous ID or relay lookup.
 */
fun parsePcIdOrEndpoint(input: String, defaultPort: Int = 3389): RdpEndpoint? {
    val raw = input.trim()
    if (raw.isEmpty()) return null

    val colonIdx = raw.lastIndexOf(':')
    val hasPortSuffix = colonIdx > 0 && raw.indexOf(':') == colonIdx
    val basePart = if (hasPortSuffix) raw.substring(0, colonIdx).trim() else raw
    val portPart = if (hasPortSuffix) {
        raw.substring(colonIdx + 1).trim().toIntOrNull() ?: return null
    } else {
        defaultPort
    }
    if (portPart !in 1..65535) return null

    val compact = basePart.replace("-", "").replace(" ", "")
    if (compact.uppercase().startsWith("XY") && compact.length == 10) {
        val hex = compact.substring(2)
        val num = hex.toLongOrNull(16)
        if (num != null && num in 16777216L..4294967295L) {
            val ip = "${(num shr 24) and 0xFF}.${(num shr 16) and 0xFF}.${(num shr 8) and 0xFF}.${num and 0xFF}"
            return RdpEndpoint(ip, portPart)
        }
    }
    if (compact.length in 8..10 && compact.all { it.isDigit() }) {
        val num = compact.toLongOrNull()
        if (num != null && num in 16777216L..4294967295L) {
            val ip = "${(num shr 24) and 0xFF}.${(num shr 16) and 0xFF}.${(num shr 8) and 0xFF}.${num and 0xFF}"
            return RdpEndpoint(ip, portPart)
        }
    }
    return parseRdpEndpoint(raw, defaultPort)
}

