package com.kazamatech.kzdesk.core

import java.util.Locale

/**
 * Taksonomi kode kegagalan koneksi KzDesk.
 *
 * **Ini kode KzDesk, bukan kode error FreeRDP.** FreeRDP hanya mengekspos
 * `getLastErrorString(inst)` yang mengembalikan teks; tidak ada accessor kode
 * numerik di JNI yang tersedia. Jadi alih-alih menampilkan angka yang tampak
 * resmi tetapi tidak berarti, kegagalan dikelompokkan ke kategori yang benar-
 * benar bisa dideteksi dari `SessionState.Error.code` + teks native, lalu
 * kategori itu yang diberi nomor stabil.
 *
 * Nomornya **tidak boleh diubah atau dipakai ulang** setelah dirilis: angka ini
 * masuk ke log dan dialog, jadi laporan bug pengguna merujuk ke sini.
 * Tambahkan kategori baru di akhir daftar.
 */
object RdpFailure {
    /** Penyebab tidak dikenali. */
    const val UNKNOWN = 0x00

    /** Probe TCP tidak dijawab host. */
    const val UNREACHABLE = 0x01

    /** Handshake melewati batas waktu watchdog. */
    const val TIMEOUT = 0x02

    /** Nama host tidak bisa diselesaikan (DNS). */
    const val DNS = 0x03

    /** Negosiasi TLS/sertifikat gagal. */
    const val TLS = 0x04

    /** Kata sandi ditolak server. */
    const val AUTH_PASSWORD = 0x05

    /** Logon ditolak tanpa menyebut field yang salah. */
    const val AUTH_LOGON = 0x06

    /** Akun terkunci. */
    const val ACCOUNT_LOCKED = 0x07

    /** Kata sandi kedaluwarsa / wajib diganti. */
    const val PASSWORD_EXPIRED = 0x08

    /** Server secara eksplisit menolak koneksi. */
    const val SERVER_DENIED = 0x09

    /** Mesin RDP gagal memulai koneksi (kesalahan internal). */
    const val ENGINE = 0x0A

    /** Handshake gagal tanpa alasan spesifik dari native. */
    const val HANDSHAKE = 0x0B

    /**
     * Menentukan kategori kegagalan.
     *
     * Urutan pengecekan teks native disamakan dengan [com.kazamatech.kzdesk.ui.RdpErrors.hint]
     * di modul app supaya satu kegagalan tidak mendapat kode dan penjelasan yang
     * berbeda. Pemeriksaan yang lebih spesifik didahulukan.
     */
    fun codeFor(code: String, message: String): Int {
        val m = message.lowercase(Locale.ROOT)
        return when {
            code == "unreachable" -> UNREACHABLE
            code == "connect_timeout" -> TIMEOUT
            code == "connect_exception" -> ENGINE
            code == "connect_failed" && (m.contains("wrong password") || m.contains("password supplied")) ->
                AUTH_PASSWORD
            code == "connect_failed" && m.contains("account locked") -> ACCOUNT_LOCKED
            code == "connect_failed" && m.contains("password") &&
                (m.contains("expired") || m.contains("must be changed")) -> PASSWORD_EXPIRED
            code == "connect_failed" &&
                (m.contains("certificate") || m.contains("tls") || m.contains("ssl")) -> TLS
            code == "connect_failed" && (m.contains("dns") || m.contains("could not be resolved") ||
                m.contains("host name was not found")) -> DNS
            code == "connect_failed" && (m.contains("logon failed") || m.contains("logon failure") ||
                m.contains("authentication")) -> AUTH_LOGON
            code == "connect_failed" && m.contains("server denied") -> SERVER_DENIED
            code == "connect_failed" -> HANDSHAKE
            else -> UNKNOWN
        }
    }

    /** Kode kategori sebagai `0x000B` — format yang dipakai log dan dialog. */
    fun formatCode(numeric: Int): String =
        String.format(Locale.ROOT, "0x%04X", numeric)

    /**
     * ID koneksi: handle instance native FreeRDP (`inst`) saat kegagalan terjadi.
     *
     * Nilainya nyata dan berguna untuk mencocokkan dialog dengan baris log, tapi
     * sengaja diformat hex penuh 16 digit supaya jelas ini handle, bukan nomor urut.
     */
    fun formatConnectionId(instance: Long): String =
        if (instance == 0L) "-" else String.format(Locale.ROOT, "0x%016X", instance)

    /**
     * Baris detail singkat untuk log dan dialog.
     *
     * Contoh: `Kode 0x000B · ID koneksi 0x00007F3C2A10B400 · 192.168.1.5:3389`
     */
    fun detailLine(
        code: String,
        message: String,
        instance: Long,
        host: String?,
        port: Int,
    ): String {
        val target = if (host.isNullOrBlank()) "-" else "$host:$port"
        return "Kode ${formatCode(codeFor(code, message))}" +
            " · ID koneksi ${formatConnectionId(instance)}" +
            " · $target"
    }
}
