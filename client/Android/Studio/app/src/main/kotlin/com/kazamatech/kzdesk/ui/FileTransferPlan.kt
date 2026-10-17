package com.kazamatech.kzdesk.ui

/**
 * Aturan penamaan dan format untuk transfer file HP <-> PC.
 *
 * Dipisah dari Android supaya bisa diuji di JVM. Nama file hasil kiriman
 * aplikasi lain sering berisi karakter yang ditolak Windows (`:` `?` `*` `"`)
 * atau kosong sama sekali; kalau dibiarkan apa adanya, salinan ke drive yang
 * di-redirect gagal diam-diam dan pengguna hanya melihat transfer yang macet.
 */
internal object FileTransferPlan {
    /** Karakter yang ditolak NTFS/Windows Explorer plus karakter kontrol. */
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /** Nama cadangan kalau URI tidak membawa nama sama sekali. */
    private const val FALLBACK_BASE = "kzdesk"

    /** Batas praktis nama file NTFS (255 byte); disisakan ruang untuk " (2)". */
    private const val MAX_NAME_LENGTH = 120

    /**
     * Nama file yang aman dipakai di drive yang di-redirect ke Windows.
     *
     * Karakter terlarang diganti `_`, spasi di ujung dibuang, nama kosong
     * diganti `kzdesk-<millis>`. Ekstensi dipertahankan apa adanya karena
     * Windows mengenali tipe file dari sana.
     */
    fun sanitizeFileName(raw: String?, fallbackMillis: Long): String {
        // Titik di DEPAN dipertahankan (".gitignore" adalah nama sah), titik di
        // BELAKANG dibuang karena Windows menolaknya.
        val cleaned = raw
            ?.replace(FORBIDDEN, "_")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.trimEnd('.')
            .orEmpty()
        if (cleaned.isEmpty()) return "$FALLBACK_BASE-$fallbackMillis"
        val dot = cleaned.lastIndexOf('.')
        return if (dot > 0 && dot < cleaned.length - 1) {
            val base = cleaned.substring(0, dot).takeLast(MAX_NAME_LENGTH)
            val ext = cleaned.substring(dot).take(20)
            "$base$ext"
        } else {
            cleaned.takeLast(MAX_NAME_LENGTH)
        }
    }

    /**
     * Nama unik di dalam folder tujuan: `foto.jpg` -> `foto (2).jpg` ->
     * `foto (3).jpg`. Menimpa file PC yang kebetulan bernama sama akan
     * menghapus data pengguna tanpa peringatan, jadi selalu dicari slot kosong.
     */
    fun uniqueName(existingNames: Collection<String>, desired: String): String {
        // Kunci disimpan dalam huruf kecil: NTFS tidak membedakan besar-kecil
        // huruf, jadi "foto.jpg" dan "FOTO.JPG" adalah file yang sama.
        val taken = HashSet<String>(existingNames.size + 1)
        existingNames.forEach { taken.add(it.lowercase()) }
        if (!taken.contains(desired.lowercase())) return desired
        val dot = desired.lastIndexOf('.')
        val base: String
        val ext: String
        if (dot > 0 && dot < desired.length - 1) {
            base = desired.substring(0, dot)
            ext = desired.substring(dot)
        } else {
            base = desired
            ext = ""
        }
        var index = 2
        while (index < 10_000) {
            val candidate = "$base ($index)$ext"
            if (!taken.contains(candidate.lowercase())) return candidate
            index++
        }
        return "$base (${System.nanoTime()})$ext"
    }

    /** `512 B` / `1,4 MB` / `2,10 GB` — cukup untuk baris status transfer. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024.0) return trimNumber(kib) + " KB"
        val mib = kib / 1024.0
        if (mib < 1024.0) return trimNumber(mib) + " MB"
        return trimNumber(mib / 1024.0) + " GB"
    }

    /** Persentase untuk bar progres; total 0 berarti belum diketahui. */
    fun percentOf(doneBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0L || doneBytes < 0L) return 0
        val pct = (doneBytes.toDouble() / totalBytes.toDouble() * 100.0)
        return pct.toInt().coerceIn(0, 100)
    }

    /** Satu angka di belakang koma, tanpa `.0` yang tidak perlu. */
    private fun trimNumber(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }
}
