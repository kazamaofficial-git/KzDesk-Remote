package com.kazamatech.kzdesk.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazamatech.kzdesk.ui.components.KzIcons
import com.kazamatech.kzdesk.ui.components.kzGlass

/**
 * Tujuan navigasi bawah.
 *
 * Sengaja hanya **tiga** sesuai permintaan pengguna. Sebelumnya bar ini memuat
 * enam seksi (`KzSection`) sehingga penuh dan tiap item jadi sempit; seksi
 * lain sekarang hidup di drawer kiri dan di layar Profil.
 */
internal enum class KzTab(val title: String, val titleEn: String) {
    HOME("Beranda", "Home"),
    FEED("Feed", "Feed"),
    PROFILE("Profil", "Profile"),
}

/**
 * Navigasi bawah app: tiga tujuan, gaya kaca monokrom.
 *
 * Warna tetap hitam-putih (`onSurface`/`surface`) alih-alih aksen, mengikuti
 * permintaan pengguna. Item terpilih digambar sebagai pil berisi; sisanya
 * redup. Perubahan warna dan ukuran pil dianimasikan supaya perpindahan tab
 * terasa halus, bukan melompat.
 */
@Composable
internal fun KzBottomNav(
    selected: KzTab,
    onSelect: (KzTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Lapisan kaca tipis: bar menyatu dengan latar tanpa jadi bidang
            // datar, senada dengan kartu di atasnya.
            .kzGlass(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), strength = 0.8f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KzTab.entries.forEach { tab ->
            val active = tab == selected
            // kz() bersifat @Composable, jadi harus dipanggil di sini, bukan di
            // dalam lambda semantics {} yang bukan composable.
            val title = kz(tab.title, tab.titleEn)
            val pillWidth by animateDpAsState(
                targetValue = if (active) 44.dp else 34.dp,
                animationSpec = tween(durationMillis = 180),
                label = "navPill",
            )
            val pillColor by animateColorAsState(
                targetValue = if (active) ink else Color.Transparent,
                animationSpec = tween(durationMillis = 180),
                label = "navPillColor",
            )
            val iconTint by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.surface else ink.copy(alpha = 0.42f),
                animationSpec = tween(durationMillis = 180),
                label = "navIconTint",
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = { onSelect(tab) })
                    .semantics { contentDescription = title }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(pillWidth)
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(pillColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        tabIcon(tab),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Text(
                    text = title,
                    color = if (active) ink else ink.copy(alpha = 0.42f),
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Ikon per tujuan. Tiga saja, jadi tidak ada risiko dua seksi berbagi ikon. */
private fun tabIcon(tab: KzTab): ImageVector = when (tab) {
    KzTab.HOME -> KzIcons.Monitor
    KzTab.FEED -> KzIcons.Users
    KzTab.PROFILE -> KzIcons.Gear
}

/**
 * Garis pemisah tipis di atas bar.
 *
 * Tetap dipertahankan sebagai penegas batas walaupun bar sudah berkaca: di
 * tema terang lapisan kaca saja tidak selalu cukup memisahkan isi dari nav.
 */
@Composable
internal fun KzBottomNavHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    )
}
