package com.kazamatech.kzdesk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transfer file HP <-> PC menulis ke drive yang di-redirect ke Windows, jadi
 * nama file harus lolos aturan NTFS dan tidak boleh menimpa file yang sudah
 * ada di PC. Keduanya aturan murni, jadi diuji di JVM.
 */
class FileTransferPlanTest {

    @Test
    fun cleanNamesPassThrough() {
        assertEquals("photo.jpg", FileTransferPlan.sanitizeFileName("photo.jpg", 1L))
        assertEquals("noext", FileTransferPlan.sanitizeFileName("noext", 1L))
        assertEquals(".hidden", FileTransferPlan.sanitizeFileName(".hidden", 1L))
        assertEquals(
            "laporan akhir.pdf",
            FileTransferPlan.sanitizeFileName("laporan  akhir.pdf", 1L),
        )
    }

    @Test
    fun windowsForbiddenCharactersAreReplaced() {
        assertEquals("a_b_c_.jpg", FileTransferPlan.sanitizeFileName("a/b:c*.jpg", 1L))
        assertEquals(
            "x_y_z_w_v_u_t_p_t.png",
            FileTransferPlan.sanitizeFileName("""x\y:z*w?v"u<t>p|t.png""", 1L),
        )
    }

    @Test
    fun emptyOrBlankNamesGetAFallback() {
        assertEquals("kzdesk-12345", FileTransferPlan.sanitizeFileName(null, 12_345L))
        assertEquals("kzdesk-7", FileTransferPlan.sanitizeFileName("   ", 7L))
        assertEquals("kzdesk-5", FileTransferPlan.sanitizeFileName("...", 5L))
        assertEquals("kzdesk-9", FileTransferPlan.sanitizeFileName("", 9L))
    }

    @Test
    fun veryLongNamesKeepTheirExtension() {
        val long = "a".repeat(200) + ".jpg"
        val result = FileTransferPlan.sanitizeFileName(long, 1L)
        assertTrue("ekstensi harus bertahan, dapat: $result", result.endsWith(".jpg"))
        assertEquals(124, result.length)
    }

    @Test
    fun existingFilesAreNeverOverwritten() {
        assertEquals(
            "foto.jpg",
            FileTransferPlan.uniqueName(emptyList(), "foto.jpg"),
        )
        assertEquals(
            "foto (2).jpg",
            FileTransferPlan.uniqueName(listOf("foto.jpg"), "foto.jpg"),
        )
        assertEquals(
            "foto (3).jpg",
            FileTransferPlan.uniqueName(listOf("foto.jpg", "foto (2).jpg"), "foto.jpg"),
        )
        assertEquals(
            "data (2)",
            FileTransferPlan.uniqueName(listOf("data"), "data"),
        )
    }

    @Test
    fun nameCollisionCheckIgnoresCaseLikeWindowsDoes() {
        // NTFS tidak membedakan besar-kecil huruf; kalau dianggap berbeda,
        // salinan "foto.jpg" akan menimpa "FOTO.JPG" milik pengguna.
        assertEquals(
            "foto (2).jpg",
            FileTransferPlan.uniqueName(listOf("FOTO.JPG"), "foto.jpg"),
        )
    }

    @Test
    fun byteLabelsStayReadable() {
        assertEquals("0 B", FileTransferPlan.formatBytes(0L))
        assertEquals("0 B", FileTransferPlan.formatBytes(-5L))
        assertEquals("512 B", FileTransferPlan.formatBytes(512L))
        assertEquals("1023 B", FileTransferPlan.formatBytes(1023L))
        assertEquals("1 KB", FileTransferPlan.formatBytes(1024L))
        assertEquals("1.5 KB", FileTransferPlan.formatBytes(1536L))
        assertEquals("1 MB", FileTransferPlan.formatBytes(1024L * 1024L))
        assertEquals("1.5 MB", FileTransferPlan.formatBytes(1536L * 1024L))
        assertEquals("1 GB", FileTransferPlan.formatBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun progressPercentIsClamped() {
        assertEquals(25, FileTransferPlan.percentOf(50L, 200L))
        assertEquals(100, FileTransferPlan.percentOf(200L, 200L))
        assertEquals(100, FileTransferPlan.percentOf(300L, 200L))
        assertEquals(0, FileTransferPlan.percentOf(0L, 0L))
        assertEquals(0, FileTransferPlan.percentOf(-1L, 200L))
    }
}
