package com.kazamatech.kzdesk.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazamatech.kzdesk.ui.components.KzDialog
import com.kazamatech.kzdesk.ui.components.KzField
import com.kazamatech.kzdesk.ui.components.KzOverlay
import com.kazamatech.kzdesk.ui.components.KzPillButton
import com.kazamatech.kzdesk.ui.components.KzSectionLabel
import java.io.ByteArrayOutputStream

/**
 * Template Controller: simpan tata letak sebagai template, terapkan template bawaan/milikmu,
 * impor (berkas / clipboard / tempel) dan ekspor (berkas / clipboard) dalam format JSON.
 */
@Composable
fun TemplateControllerDialog(
    currentKeys: List<HudKey>,
    buttonSize: Float,
    onApply: (List<HudKey>) -> Unit,
    onNotice: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ControllerTemplateStore(context) }
    var mine by remember { mutableStateOf(store.list()) }
    var revision by remember { mutableIntStateOf(0) }
    var pasteText by remember { mutableStateOf("") }
    var saveName by remember { mutableStateOf("") }
    var confirmApply by remember { mutableStateOf<ControllerTemplate?>(null) }
    var confirmDelete by remember { mutableStateOf<ControllerTemplate?>(null) }
    var pendingImport by remember { mutableStateOf<TemplateParse.Ok?>(null) }
    var pendingExport by remember { mutableStateOf<ControllerTemplate?>(null) }
    val builtIns = remember { ControllerTemplates.builtIns() }

    fun refresh() { mine = store.list(); revision++ }

    fun commitImport(ok: TemplateParse.Ok) {
        var saved = 0
        ok.templates.forEach { if (store.save(it)) saved++ }
        refresh()
        onNotice(
            if (saved == ok.templates.size) {
                kzNow("{0} template diimpor", "{0} template(s) imported", saved)
            } else {
                kzNow("Batas {0} template tercapai; sebagian tidak disimpan", "Limit of {0} templates reached; some were not saved", ControllerTemplates.MAX_TEMPLATES)
            },
        )
    }

    fun handleImportText(raw: String) {
        when (val r = ControllerTemplates.parse(raw, buttonSize)) {
            is TemplateParse.Error -> onNotice(kzNow(r.id, r.en))
            is TemplateParse.Ok -> if (r.macroCount > 0) pendingImport = r else commitImport(r)
        }
    }

    fun clipboardText(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    }

    fun copyJson(t: ControllerTemplate) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("KzDesk Template", ControllerTemplates.toJson(t)))
        onNotice(kzNow("JSON template disalin", "Template JSON copied"))
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > ControllerTemplates.MAX_BYTES) return@use null
                        out.write(buf, 0, n)
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            }.getOrNull()
            if (text == null) onNotice(kzNow("Berkas tidak bisa dibaca atau terlalu besar", "File unreadable or too large"))
            else handleImportText(text)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val t = pendingExport
        pendingExport = null
        if (uri != null && t != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(ControllerTemplates.toJson(t).toByteArray(Charsets.UTF_8)) } != null
            }.getOrDefault(false)
            onNotice(if (ok) kzNow("Template disimpan ke berkas", "Template saved to file") else kzNow("Gagal menyimpan berkas", "Could not save file"))
        }
    }

    KzOverlay(title = kz("Template Controller", "Controller Templates"), onDismiss = onClose) {
        Column(
            Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                kz(
                    "Template menyimpan tombol dan joystick beserta posisinya. Bagikan sebagai JSON atau impor milik orang lain.",
                    "Templates store your buttons and joysticks with their positions. Share them as JSON or import someone else's.",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )

            KzSectionLabel(kz("Impor", "Import"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KzPillButton(
                    kz("Dari berkas", "From file"),
                    { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                KzPillButton(
                    kz("Dari clipboard", "From clipboard"),
                    {
                        val clip = clipboardText()
                        if (clip.isBlank()) onNotice(kzNow("Clipboard kosong", "Clipboard is empty")) else handleImportText(clip)
                    },
                    primary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            KzField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = kz("Tempel JSON template", "Paste template JSON"),
                hint = "{\"format\":\"kzdesk.controller-template\",...}",
            )
            KzPillButton(
                kz("Impor dari teks", "Import from text"),
                { handleImportText(pasteText); pasteText = "" },
                primary = false,
                compact = true,
                enabled = pasteText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            KzSectionLabel(kz("Simpan tata letak saat ini", "Save current layout"))
            KzField(
                value = saveName,
                onValueChange = { saveName = it.take(ControllerTemplates.MAX_NAME) },
                label = kz("Nama template", "Template name"),
                hint = "FPS ${currentKeys.size}",
            )
            KzPillButton(
                kz("Simpan sebagai template", "Save as template"),
                {
                    val t = ControllerTemplates.fromCurrent(saveName.ifBlank { "Template ${mine.size + 1}" }, currentKeys)
                    if (store.save(t)) {
                        saveName = ""
                        refresh()
                        onNotice(kzNow("Template \"{0}\" disimpan", "Template \"{0}\" saved", t.name))
                    } else {
                        onNotice(kzNow("Batas template tercapai", "Template limit reached"))
                    }
                },
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )

            KzSectionLabel(kz("Template saya ({0})", "My templates ({0})", mine.size))
            if (mine.isEmpty()) {
                Text(
                    kz("Belum ada. Simpan tata letak atau impor JSON.", "None yet. Save a layout or import JSON."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            mine.forEach { t ->
                TemplateRow(
                    t = t,
                    onApply = { confirmApply = t },
                    onCopy = { copyJson(t) },
                    onSave = { pendingExport = t; exportLauncher.launch(ControllerTemplates.fileName(t)) },
                    onDelete = { confirmDelete = t },
                )
            }

            KzSectionLabel(kz("Bawaan", "Built-in"))
            builtIns.forEach { t ->
                TemplateRow(
                    t = t,
                    onApply = { confirmApply = t },
                    onCopy = { copyJson(t) },
                    onSave = { pendingExport = t; exportLauncher.launch(ControllerTemplates.fileName(t)) },
                    onDelete = null,
                )
            }
        }
        KzPillButton(
            kz("Tutup", "Close"),
            onClose,
            primary = false,
            compact = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    confirmApply?.let { t ->
        KzDialog(
            title = kz("Terapkan template?", "Apply template?"),
            body = kz(
                "Tata letak tombol saat ini akan diganti dengan \"{0}\" ({1} tombol).",
                "Your current button layout will be replaced by \"{0}\" ({1} buttons).",
                t.name, t.keys.size,
            ),
            confirmLabel = kz("Terapkan", "Apply"),
            onConfirm = {
                confirmApply = null
                onApply(t.keys)
                onNotice(kzNow("Template \"{0}\" diterapkan", "Template \"{0}\" applied", t.name))
                onClose()
            },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmApply = null },
        )
    }
    confirmDelete?.let { t ->
        KzDialog(
            title = kz("Hapus template?", "Delete template?"),
            body = kz("Hapus \"{0}\" dari daftar template saya?", "Delete \"{0}\" from my templates?", t.name),
            confirmLabel = kz("Hapus", "Delete"),
            onConfirm = { store.delete(t.id); confirmDelete = null; refresh() },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { confirmDelete = null },
        )
    }
    pendingImport?.let { ok ->
        KzDialog(
            title = kz("Template berisi makro", "Template contains macros"),
            body = kz(
                "Template ini memuat {0} tombol makro yang mengetik teks otomatis ke PC. Periksa isinya di editor tombol sebelum dipakai. Penekanan Enter otomatis dimatikan untuk template impor.",
                "This template has {0} macro button(s) that auto-type text on your PC. Review them in the button editor before use. Auto-Enter is turned off for imported templates.",
                ok.macroCount,
            ),
            confirmLabel = kz("Tetap impor", "Import anyway"),
            onConfirm = { pendingImport = null; commitImport(ok) },
            dismissLabel = kz("Batal", "Cancel"),
            onDismiss = { pendingImport = null },
        )
    }
}

@Composable
private fun TemplateRow(
    t: ControllerTemplate,
    onApply: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(t.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
        Text(
            buildString {
                append(kzNow("{0} tombol", "{0} buttons", t.keys.size))
                if (t.author.isNotEmpty()) append(" · ").append(t.author)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
        if (t.description.isNotEmpty()) {
            Text(t.description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KzPillButton(kz("Terapkan", "Apply"), onApply, compact = true, modifier = Modifier.weight(1f))
            KzPillButton("JSON", onCopy, primary = false, compact = true, modifier = Modifier.weight(1f))
            KzPillButton(".json", onSave, primary = false, compact = true, modifier = Modifier.weight(1f))
            if (onDelete != null) {
                KzPillButton(kz("Hapus", "Del"), onDelete, primary = false, compact = true, modifier = Modifier.weight(1f))
            }
        }
    }
}
