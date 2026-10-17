package com.kazamatech.kzdesk

/**
 * Konfigurasi tunggal untuk semua alamat dan identitas brand.
 * Ubah di sini bila domain server atau repo rilis berpindah.
 */
object KzConfig {
    const val APP_NAME = "KzDesk Remote"
    const val DEVELOPER = "KazamaDev"

    /** Server web + API (kzdesk-web). Harus HTTPS. */
    const val API_BASE = "https://kzdesk.kazamasite.biz.id"

    const val DONATE_URL = "https://www.kazamasite.biz.id/donate"
    const val REPO_URL = "https://github.com/kazamadev/KzDesk-Remote"
    const val HOST_PAGE_URL = "$API_BASE/host"
    const val HOST_INSTALL_ONE_LINER = "irm $API_BASE/install.ps1 | iex"

    /** Akun Windows khusus yang dibuat KzDesk Host untuk mode ID Device. */
    const val DEVICE_USERNAME = "KzDesk"
}
