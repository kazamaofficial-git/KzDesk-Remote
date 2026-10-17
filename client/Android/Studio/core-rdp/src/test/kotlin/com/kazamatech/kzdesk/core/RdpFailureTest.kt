package com.kazamatech.kzdesk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kode kegagalan harus stabil: angka ini masuk ke log dan dialog, jadi laporan
 * bug pengguna merujuk ke sini. Tes ini mengunci nilai dan urutan klasifikasi.
 */
class RdpFailureTest {

    @Test
    fun kodeKategoriTidakBerubah() {
        assertEquals(0x00, RdpFailure.UNKNOWN)
        assertEquals(0x01, RdpFailure.UNREACHABLE)
        assertEquals(0x02, RdpFailure.TIMEOUT)
        assertEquals(0x03, RdpFailure.DNS)
        assertEquals(0x04, RdpFailure.TLS)
        assertEquals(0x05, RdpFailure.AUTH_PASSWORD)
        assertEquals(0x06, RdpFailure.AUTH_LOGON)
        assertEquals(0x07, RdpFailure.ACCOUNT_LOCKED)
        assertEquals(0x08, RdpFailure.PASSWORD_EXPIRED)
        assertEquals(0x09, RdpFailure.SERVER_DENIED)
        assertEquals(0x0A, RdpFailure.ENGINE)
        assertEquals(0x0B, RdpFailure.HANDSHAKE)
    }

    @Test
    fun klasifikasiBerdasarkanKodeStabil() {
        assertEquals(RdpFailure.UNREACHABLE, RdpFailure.codeFor("unreachable", ""))
        assertEquals(RdpFailure.TIMEOUT, RdpFailure.codeFor("connect_timeout", ""))
        assertEquals(RdpFailure.ENGINE, RdpFailure.codeFor("connect_exception", "boom"))
        assertEquals(RdpFailure.UNKNOWN, RdpFailure.codeFor("cancelled", "dibatalkan"))
    }

    @Test
    fun klasifikasiBerdasarkanTeksNative() {
        assertEquals(
            RdpFailure.AUTH_PASSWORD,
            RdpFailure.codeFor("connect_failed", "LOGON_FAILED: wrong password supplied"),
        )
        assertEquals(
            RdpFailure.ACCOUNT_LOCKED,
            RdpFailure.codeFor("connect_failed", "The account locked out"),
        )
        assertEquals(
            RdpFailure.PASSWORD_EXPIRED,
            RdpFailure.codeFor("connect_failed", "Password expired, must be changed"),
        )
        assertEquals(
            RdpFailure.TLS,
            RdpFailure.codeFor("connect_failed", "TLS negotiation failure"),
        )
        assertEquals(
            RdpFailure.DNS,
            RdpFailure.codeFor("connect_failed", "host name could not be resolved"),
        )
        assertEquals(
            RdpFailure.AUTH_LOGON,
            RdpFailure.codeFor("connect_failed", "Authentication failure, unknown user"),
        )
        assertEquals(
            RdpFailure.SERVER_DENIED,
            RdpFailure.codeFor("connect_failed", "server denied connection"),
        )
        assertEquals(
            RdpFailure.HANDSHAKE,
            RdpFailure.codeFor("connect_failed", "something we have not mapped"),
        )
    }

    @Test
    fun kataSandiSalahMenangAtasLogonUmum() {
        // "wrong password" juga memuat "logon"; urutan harus memilih yang lebih
        // spesifik, sama seperti RdpErrors.hint di modul app.
        assertEquals(
            RdpFailure.AUTH_PASSWORD,
            RdpFailure.codeFor("connect_failed", "Logon failed: wrong password"),
        )
    }

    @Test
    fun klasifikasiTidakPekaHurufBesarKecil() {
        assertEquals(
            RdpFailure.TLS,
            RdpFailure.codeFor("connect_failed", "CERTIFICATE VERIFY FAILED"),
        )
    }

    @Test
    fun formatKodeSelaluEmpatDigitHex() {
        assertEquals("0x0000", RdpFailure.formatCode(0))
        assertEquals("0x000B", RdpFailure.formatCode(0x0B))
        assertEquals("0x0004", RdpFailure.formatCode(RdpFailure.TLS))
    }

    @Test
    fun idKosongDitandaiStripBukanNol() {
        // Handle 0 berarti instance belum ada; menampilkan 0x0000… akan dibaca
        // sebagai ID sungguhan dan menyesatkan saat mencocokkan log.
        assertEquals("-", RdpFailure.formatConnectionId(0L))
        assertEquals("0x0000000000007F3C", RdpFailure.formatConnectionId(0x7F3CL))
    }

    @Test
    fun barisDetailMemuatKodeIdDanTarget() {
        val line = RdpFailure.detailLine(
            code = "connect_failed",
            message = "TLS negotiation failure",
            instance = 0x7F3CL,
            host = "192.0.2.4",
            port = 3389,
        )
        assertEquals("Kode 0x0004 · ID koneksi 0x0000000000007F3C · 192.0.2.4:3389", line)
    }

    @Test
    fun barisDetailTanpaHostPakaiStrip() {
        val line = RdpFailure.detailLine("unreachable", "", 0L, null, 0)
        assertEquals("Kode 0x0001 · ID koneksi - · -", line)
    }

    @Test
    fun setiapKodePunyaFormatUnik() {
        val codes = listOf(
            RdpFailure.UNKNOWN, RdpFailure.UNREACHABLE, RdpFailure.TIMEOUT, RdpFailure.DNS,
            RdpFailure.TLS, RdpFailure.AUTH_PASSWORD, RdpFailure.AUTH_LOGON,
            RdpFailure.ACCOUNT_LOCKED, RdpFailure.PASSWORD_EXPIRED, RdpFailure.SERVER_DENIED,
            RdpFailure.ENGINE, RdpFailure.HANDSHAKE,
        )
        assertEquals("kode duplikat", codes.size, codes.distinct().size)
        assertTrue(codes.all { it in 0x00..0xFF })
    }
}
