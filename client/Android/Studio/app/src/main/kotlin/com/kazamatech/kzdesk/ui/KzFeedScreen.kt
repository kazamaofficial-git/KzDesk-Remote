package com.kazamatech.kzdesk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.ui.components.KzGlassCard
import com.kazamatech.kzdesk.ui.components.KzGlassSectionLabel
import com.kazamatech.kzdesk.ui.components.KzIconPill
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.KzTopBar

/**
 * Layar Feed — layar sendiri, bukan kartu yang menumpang di Beranda.
 *
 * Sebelumnya feed jokes komunitas (`FunHubCard`) hanya muncul sebagai satu
 * kartu di tengah daftar perangkat, jadi isinya sempit dan terpotong oleh
 * `INITIAL_JOKES_VISIBLE`. Sebagai tujuan nav sendiri, feed mendapat lebar
 * penuh dan bisa digulir tanpa berebut ruang dengan daftar perangkat.
 *
 * `FunHubCard` dipanggil dengan `onHide = null`: tombol "Sembunyikan" tidak
 * relevan di sini karena feed memang tujuan yang dipilih pengguna, bukan
 * kartu opsional. Tombol itu tetap ada di kartu Beranda.
 */
@Composable
internal fun KzFeedScreen(
    onMenu: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        KzTopBar(
            title = kz("Feed", "Feed"),
            actions = { KzIconPill(KzIcons.Menu, onMenu, flat = true, contentDescription = "Menu") },
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KzGlassSectionLabel(kz("Komunitas", "Community"))
            KzGlassCard(padding = 14.dp) {
                FunHubCard(onHide = null)
            }
        }
    }
}
