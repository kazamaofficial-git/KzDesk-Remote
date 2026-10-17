package com.kazamatech.kzdesk.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * Pesan singkat milik app sendiri.
 *
 * Pengganti `android.widget.Toast`: toast bawaan Android tampil dengan gaya
 * sistem (bulat penuh abu-abu, font sistem) dan tidak ikut tema app. Semua
 * pesan di KzDesk lewat sini supaya satu bahasa visual.
 */
class KzNoticeState {
    internal var message by mutableStateOf<String?>(null)
    internal var seq by mutableIntStateOf(0)

    fun show(text: String) {
        message = text
        seq++
    }
}

@Composable
fun rememberXyNotice(): KzNoticeState = remember { KzNoticeState() }

/**
 * Jalur pesan untuk kejadian yang tidak punya layar lagi saat pesannya jadi
 * (mis. activity sesi gagal dibuat lalu langsung ditutup). Pesan disimpan
 * sebagai event terakhir, jadi tetap tampil di layar berikutnya.
 */
object KzNoticeBus {
    private val events = MutableStateFlow<String?>(null)
    val messages: kotlinx.coroutines.flow.Flow<String> = events.filterNotNull()

    fun post(text: String) {
        events.value = text
    }
}

/**
 * Host pesan. Taruh sekali di paling atas layar; pesan hilang sendiri setelah
 * ~2 detik. Sengaja selalu gelap-putih: sering dipakai di atas gambar remote,
 * jadi kontrasnya tidak boleh bergantung tema.
 */
@Composable
fun KzNoticeHost(
    state: KzNoticeState,
    modifier: Modifier = Modifier,
) {
    val text = state.message
    val token = state.seq
    var visible by remember { mutableStateOf(false) }

    // Pesan dari luar layar ini (KzNoticeBus) ikut ditampilkan.
    LaunchedEffect(Unit) {
        KzNoticeBus.messages.collect { state.show(it) }
    }

    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        visible = true
        delay(2200)
        visible = false
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .zIndex(60f),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible && text != null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
        ) {
            Box(
                Modifier
                    .widthIn(max = 420.dp)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(
                    text = text.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
            }
        }
    }
}
