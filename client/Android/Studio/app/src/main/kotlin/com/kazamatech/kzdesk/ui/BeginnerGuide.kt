package com.kazamatech.kzdesk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.ui.components.KzCard
import com.kazamatech.kzdesk.ui.components.KzOverlay
import com.kazamatech.kzdesk.ui.components.KzPillButton

/** Panduan singkat untuk pemula: bedanya dua cara terhubung dan fitur yang sering terlewat. */
@Composable
fun BeginnerGuideDialog(onClose: () -> Unit) {
    KzOverlay(title = kz("Panduan singkat", "Quick guide"), onDismiss = onClose) {
        Column(
            Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GuideCard(
                kz("1. RDP via IP", "1. RDP via IP"),
                kz(
                    "Untuk PC atau server yang alamat IP-nya kamu tahu (satu jaringan, VPN, atau IP publik). Isi IP, username, dan password Windows.",
                    "For a PC or server whose IP you know (same network, VPN, or public IP). Enter the IP and your Windows username and password.",
                ),
            )
            GuideCard(
                kz("2. ID Device + Password", "2. Device ID + Password"),
                kz(
                    "Tanpa menghafal IP. Pasang KzDesk Host di PC sekali saja; ID dan password tampil di akhir instalasi (dan bisa dilihat lagi dari Start Menu). Masukkan di app, atau scan QR yang tampil di PC.",
                    "No IP to memorize. Install KzDesk Host on the PC once; the ID and password appear at the end (and again from the Start Menu). Enter them in the app, or scan the QR shown on the PC.",
                ),
            )
            GuideCard(
                kz("Kontrol", "Controls"),
                kz(
                    "Buka Panel di sesi → Template Controller untuk tombol dan joystick siap pakai (bisa impor/ekspor JSON). Keyboard dan mouse fisik langsung dipakai dan kontrol layar menyingkir otomatis.",
                    "Open the Panel in a session → Controller Templates for ready-made buttons and joysticks (JSON import/export). Physical keyboard and mouse work right away and on-screen controls step aside.",
                ),
            )
            GuideCard(
                kz("Lebih praktis", "More convenient"),
                kz(
                    "Mikrofon HP jadi input suara di PC. Tekan Home saat tersambung untuk jendela mengambang (Picture-in-Picture). Tambahkan widget KzDesk di layar utama untuk sambung sekali ketuk, dan nyalakan notifikasi agar tahu bila koneksi terputus.",
                    "Your phone mic becomes the PC's voice input. Press Home while connected for a floating window (Picture-in-Picture). Add the KzDesk widget to your home screen for one-tap connect, and enable notifications to know when a connection drops.",
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        KzPillButton(kz("Mengerti", "Got it"), onClose, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GuideCard(title: String, body: String) {
    KzCard {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
