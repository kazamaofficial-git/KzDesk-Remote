package com.kazamatech.kzdesk.ui

/**
 * Peta kode error koneksi KzDesk dan pesan native FreeRDP menjadi penjelasan
 * yang bisa ditindaklanjuti. Pesan mentah tetap tersedia sebagai detail
 * teknis; penyebab spesifik hanya ditampilkan bila pesannya memang menyatakan itu.
 */
internal object RdpErrors {

    /** Balikan pas (Indonesia, English), atau null bila tidak ada detail native. */
    fun hint(code: String, message: String): Pair<String, String>? {
        val m = message.lowercase()
        return when {
            // Probe TCP gagal: host/port tidak menjawab, bukan diagnosis penyebabnya.
            code == "unreachable" -> Pair(
                "Host tidak menjawab di port RDP. Periksa alamat dan port, status host, " +
                    "RDP diaktifkan, serta firewall/jaringan.",
                "The host did not answer on the RDP port. Check the address and port, " +
                    "host availability, enabled RDP, and the firewall/network.",
            )

            // Watchdog: hanya menyatakan proses koneksi tidak selesai tepat waktu.
            code == "connect_timeout" -> Pair(
                "Proses koneksi melewati batas waktu. Periksa jaringan, host, dan " +
                    "pengaturan TLS/NLA; Detail menampilkan pesan native bila tersedia.",
                "The connection attempt timed out. Check the network, host, and " +
                    "TLS/NLA settings; Details shows the native message when available.",
            )

            code == "connect_failed" && (m.contains("wrong password") || m.contains("password supplied")) -> Pair(
                "FreeRDP melaporkan kata sandi salah. Periksa kata sandi akun remote.",
                "FreeRDP reported a wrong password. Check the remote account password.",
            )

            code == "connect_failed" && m.contains("account locked") -> Pair(
                "Server melaporkan akun terkunci. Buka kunci akun di host atau hubungi administrator.",
                "The server reported that the account is locked. Unlock it on the host or contact its administrator.",
            )

            code == "connect_failed" && m.contains("password") &&
                (m.contains("expired") || m.contains("must be changed")) -> Pair(
                "Server melaporkan kata sandi kedaluwarsa. Perbarui kata sandi melalui cara yang diizinkan host.",
                "The server reported an expired password. Change it using a method supported by the host.",
            )

            code == "connect_failed" && (m.contains("certificate") || m.contains("tls") || m.contains("ssl")) -> Pair(
                "Negosiasi TLS/sertifikat gagal. Periksa jam perangkat, sertifikat host, dan dukungan TLS.",
                "TLS/certificate negotiation failed. Check the device clock, host certificate, and TLS support.",
            )

            code == "connect_failed" && (m.contains("dns") || m.contains("could not be resolved") ||
                m.contains("host name was not found")) -> Pair(
                "FreeRDP tidak dapat menyelesaikan nama host. Periksa DNS atau gunakan alamat host yang benar.",
                "FreeRDP could not resolve the host name. Check DNS or use the correct host address.",
            )

            code == "connect_failed" && (m.contains("logon failed") || m.contains("logon failure") ||
                m.contains("authentication")) -> Pair(
                "Server menolak proses logon, tetapi tidak menyebut field yang salah. Periksa username, domain, " +
                    "status akun, dan kebijakan logon host.",
                "The server rejected logon but did not identify which field was wrong. Check the username, domain, " +
                    "account status, and host logon policy.",
            )

            code == "connect_failed" && m.contains("server denied") -> Pair(
                "Server secara eksplisit menolak koneksi. Periksa kebijakan RDP dan batas sesi pada host.",
                "The server explicitly denied the connection. Check the host's RDP policy and session limits.",
            )

            code == "connect_failed" -> Pair(
                "FreeRDP gagal menyelesaikan handshake. Penyebab pastinya tidak tersedia; periksa Detail, " +
                    "jaringan, host, dan konfigurasi RDP.",
                "FreeRDP did not complete the handshake. The exact cause was not provided; check Details, " +
                    "the network, host, and RDP configuration.",
            )

            code == "connect_exception" -> Pair(
                "Mesin RDP gagal memulai koneksi (kesalahan internal). Buka Detail untuk log teknis.",
                "The RDP engine failed to start the connection (internal error). Open Details for the technical log.",
            )

            else -> null
        }
    }

    /** Hint untuk disconnect hanya bila native detail menyebut alasan tertentu. */
    fun disconnectedHint(message: String?): Pair<String, String>? {
        val m = message?.lowercase().orEmpty()
        return when {
            m.contains("another user connected") || m.contains("forcing the disconnection") -> Pair(
                "Server melaporkan koneksi lain mengambil alih sesi ini.",
                "The server reported that another connection took over this session.",
            )

            m.contains("active session limit timer") || m.contains("logon timeout") -> Pair(
                "Server melaporkan batas waktu sesi aktif telah tercapai.",
                "The server reported that the active-session time limit was reached.",
            )

            m.contains("idle session limit timer") -> Pair(
                "Server melaporkan batas waktu sesi idle telah tercapai.",
                "The server reported that the idle-session time limit was reached.",
            )

            m.contains("server denied connection") -> Pair(
                "Server melaporkan bahwa koneksi ditolak.",
                "The server reported that the connection was denied.",
            )

            else -> null
        }
    }
}
