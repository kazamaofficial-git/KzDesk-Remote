package com.kazamatech.kzdesk.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.ui.components.KzOverlay
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.KzSegmented

internal fun feedbackShareDraft(category: String, message: String): String = buildString {
    appendLine("KzDesk Remote — KazamaDev")
    appendLine("Category: $category")
    appendLine()
    append(message)
}

/** User-controlled feedback draft. No persistence, logging, or network request. */
/**
 * Tujuan masukan.
 *
 * Sengaja tidak ada tujuan "email ke pengembang": repo ini tidak menyimpan
 * alamat dukungan, dan mengarang alamat akan mengirim masukan pengguna ke
 * tempat yang salah. Chooser Android sudah bisa mengirim ke aplikasi apa pun,
 * dan [COPY] membiarkan pengguna menempelkan draf ke mana saja.
 */
internal enum class FeedbackDestination {
    /** Buka pemilih aplikasi Android — tujuan bebas. */
    SHARE,

    /** Salin draf ke clipboard supaya bisa ditempel ke mana saja. */
    COPY,
}

@Composable
internal fun FeedbackDialog(
    onDismiss: () -> Unit,
    onSend: (destination: FeedbackDestination, category: String, message: String) -> Unit,
) {
    val categories = listOf(
        kz("Masalah", "Bug"),
        kz("Saran fitur", "Feature idea"),
        kz("Lainnya", "Other"),
    )
    var selected by remember { mutableIntStateOf(1) }
    var message by remember { mutableStateOf("") }

    KzOverlay(
        title = kz("Masukan & saran", "Feedback & suggestions"),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 430.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                kz("Pilih jenis masukan", "Choose a feedback type"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KzSegmented(categories, selected, onSelect = { selected = it })
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp, max = 190.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                if (message.isEmpty()) {
                    Text(
                        kz("Tulis masukan Anda…", "Write your feedback…"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 108.dp, max = 166.dp),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    maxLines = 6,
                )
            }
            Text(
                kz(
                    "Tidak ada yang dikirim otomatis. \"Bagikan\" membuka pemilih aplikasi Android (bebas ke aplikasi mana pun), \"Salin draf\" menaruh teks di clipboard supaya bisa ditempel sendiri. Draf hanya memuat nama aplikasi, kategori, dan teks di atas. Host, kredensial, clipboard lama, dan log tidak ikut.",
                    "Nothing is sent automatically. \"Share\" opens Android's app chooser (any app you like); \"Copy draft\" puts the text on the clipboard so you can paste it yourself. The draft contains only the app name, category, and the text above. Host, credentials, previous clipboard content, and logs are not attached.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KzPillButton(
                    text = kz("Batal", "Cancel"),
                    onClick = onDismiss,
                    primary = false,
                    compact = true,
                )
                KzPillButton(
                    text = kz("Salin draf", "Copy draft"),
                    onClick = { onSend(FeedbackDestination.COPY, categories[selected], message.trim()) },
                    enabled = message.isNotBlank(),
                    primary = false,
                    compact = true,
                )
                KzPillButton(
                    text = kz("Bagikan…", "Share…"),
                    onClick = { onSend(FeedbackDestination.SHARE, categories[selected], message.trim()) },
                    enabled = message.isNotBlank(),
                    compact = true,
                )
            }
        }
    }
}
