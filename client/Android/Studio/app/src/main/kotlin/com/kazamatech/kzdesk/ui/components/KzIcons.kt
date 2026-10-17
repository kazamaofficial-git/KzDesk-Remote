package com.kazamatech.kzdesk.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ikon KzDesk — set garis 24dp, stroke 1.7, ujung bulat.
 *
 * Digambar sendiri (bukan set bawaan OS/Material) supaya bobot garis dan
 * bentuknya konsisten dengan tipografi app. Ikon stroke dan solid tetap
 * memakai satu tint melalui `Icon(tint = ...)`.
 */
private fun kzIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }.build()

private fun kzSolid(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.White), pathBuilder = block)
    }.build()

private fun kzTwoPath(
    name: String,
    outline: PathBuilder.() -> Unit,
    solid: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = outline,
        )
        path(fill = SolidColor(Color.White), pathBuilder = solid)
    }.build()

object KzIcons {

    /** Monitor: perangkat remote. */
    val Monitor: ImageVector = kzIcon("KzMonitor") {
        moveTo(2.5f, 4.5f); lineTo(21.5f, 4.5f); lineTo(21.5f, 16f); lineTo(2.5f, 16f); close()
        moveTo(9f, 20.5f); lineTo(15f, 20.5f)
        moveTo(12f, 16f); lineTo(12f, 20.5f)
    }

    /** Cursor: penunjuk mouse. */
    val Cursor: ImageVector = kzSolid("KzCursor") {
        moveTo(6f, 3f); lineTo(6f, 18.5f); lineTo(10.2f, 14.6f); lineTo(13.1f, 20.8f)
        lineTo(15.6f, 19.6f); lineTo(12.8f, 13.6f); lineTo(18.4f, 13.1f); close()
    }

    val Keyboard: ImageVector = kzIcon("KzKeyboard") {
        moveTo(2.5f, 6.5f); lineTo(21.5f, 6.5f); lineTo(21.5f, 17.5f); lineTo(2.5f, 17.5f); close()
        moveTo(6f, 10f); lineTo(6.01f, 10f)
        moveTo(9.5f, 10f); lineTo(9.51f, 10f)
        moveTo(13f, 10f); lineTo(13.01f, 10f)
        moveTo(16.5f, 10f); lineTo(16.51f, 10f)
        moveTo(8f, 14f); lineTo(16f, 14f)
    }

    val Mouse: ImageVector = kzIcon("KzMouse") {
        moveTo(12f, 3f)
        curveTo(8.7f, 3f, 6.5f, 5.5f, 6.5f, 9f)
        lineTo(6.5f, 15f)
        curveTo(6.5f, 18.5f, 8.7f, 21f, 12f, 21f)
        curveTo(15.3f, 21f, 17.5f, 18.5f, 17.5f, 15f)
        lineTo(17.5f, 9f)
        curveTo(17.5f, 5.5f, 15.3f, 3f, 12f, 3f)
        close()
        moveTo(12f, 3f); lineTo(12f, 9f)
    }

    val ScrollUp: ImageVector = kzIcon("KzScrollUp") {
        moveTo(7f, 14f); lineTo(12f, 8.5f); lineTo(17f, 14f)
        moveTo(7f, 19f); lineTo(17f, 19f)
    }

    val ScrollDown: ImageVector = kzIcon("KzScrollDown") {
        moveTo(7f, 10f); lineTo(12f, 15.5f); lineTo(17f, 10f)
        moveTo(7f, 5f); lineTo(17f, 5f)
    }

    val ScrollSlide: ImageVector = kzIcon("KzScrollSlide") {
        moveTo(12f, 3f); lineTo(12f, 21f)
        moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f)
        moveTo(8f, 17f); lineTo(12f, 21f); lineTo(16f, 17f)
    }

    val Music: ImageVector = kzIcon("KzMusic") {
        moveTo(9f, 17f); lineTo(9f, 5f); lineTo(20f, 3f); lineTo(20f, 15f)
        moveTo(9f, 8f); lineTo(20f, 6f)
        moveTo(9f, 16f)
        curveTo(9f, 18.2f, 7.2f, 20f, 5f, 20f)
        curveTo(2.8f, 20f, 2f, 18.6f, 3.2f, 17.2f)
        curveTo(4.4f, 15.8f, 6.8f, 15.2f, 9f, 16f)
        moveTo(20f, 14f)
        curveTo(20f, 16.2f, 18.2f, 18f, 16f, 18f)
        curveTo(13.8f, 18f, 13f, 16.6f, 14.2f, 15.2f)
        curveTo(15.4f, 13.8f, 17.8f, 13.2f, 20f, 14f)
    }

    val Play: ImageVector = kzSolid("KzPlay") {
        moveTo(7f, 4.8f); lineTo(19f, 12f); lineTo(7f, 19.2f); close()
    }

    val Pause: ImageVector = kzSolid("KzPause") {
        moveTo(6f, 5f); lineTo(10f, 5f); lineTo(10f, 19f); lineTo(6f, 19f); close()
        moveTo(14f, 5f); lineTo(18f, 5f); lineTo(18f, 19f); lineTo(14f, 19f); close()
    }

    val SkipPrevious: ImageVector = kzSolid("KzSkipPrevious") {
        moveTo(5f, 5f); lineTo(8f, 5f); lineTo(8f, 19f); lineTo(5f, 19f); close()
        moveTo(19f, 5f); lineTo(9f, 12f); lineTo(19f, 19f); close()
    }

    val SkipNext: ImageVector = kzSolid("KzSkipNext") {
        moveTo(16f, 5f); lineTo(19f, 5f); lineTo(19f, 19f); lineTo(16f, 19f); close()
        moveTo(5f, 5f); lineTo(15f, 12f); lineTo(5f, 19f); close()
    }

    val ChevronUp: ImageVector = kzIcon("KzChevronUp") {
        moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f)
    }

    val Swap: ImageVector = kzIcon("KzSwap") {
        moveTo(4f, 9f); lineTo(18f, 9f); moveTo(15f, 6f); lineTo(18f, 9f); lineTo(15f, 12f)
        moveTo(20f, 15f); lineTo(6f, 15f); moveTo(9f, 12f); lineTo(6f, 15f); lineTo(9f, 18f)
    }

    /** Aksi klik kanan: siluet mouse dengan tombol kanan terisi penuh. */
    val ClickRight: ImageVector = kzTwoPath(
        name = "KzClickRight",
        outline = {
            moveTo(6.5f, 9f)
            curveTo(6.5f, 5.5f, 8.8f, 3.5f, 12f, 3.5f)
            curveTo(15.2f, 3.5f, 17.5f, 5.5f, 17.5f, 9f)
            lineTo(17.5f, 15f)
            curveTo(17.5f, 18.5f, 15.2f, 20.5f, 12f, 20.5f)
            curveTo(8.8f, 20.5f, 6.5f, 18.5f, 6.5f, 15f)
            close()
            moveTo(6.5f, 10.5f); lineTo(17.5f, 10.5f)
            moveTo(12f, 3.5f); lineTo(12f, 10.5f)
        },
        solid = {
            moveTo(12.4f, 4.2f)
            curveTo(14.9f, 4.4f, 16.8f, 6.1f, 16.8f, 9f)
            lineTo(16.8f, 10.1f)
            lineTo(12.4f, 10.1f)
            close()
        },
    )

    /** Aksi klik tengah: siluet mouse dengan roda tengah terisi. */
    val ClickMiddle: ImageVector = kzTwoPath(
        name = "KzClickMiddle",
        outline = {
            moveTo(6.5f, 9f)
            curveTo(6.5f, 5.5f, 8.8f, 3.5f, 12f, 3.5f)
            curveTo(15.2f, 3.5f, 17.5f, 5.5f, 17.5f, 9f)
            lineTo(17.5f, 15f)
            curveTo(17.5f, 18.5f, 15.2f, 20.5f, 12f, 20.5f)
            curveTo(8.8f, 20.5f, 6.5f, 18.5f, 6.5f, 15f)
            close()
            moveTo(6.5f, 10.5f); lineTo(17.5f, 10.5f)
        },
        solid = {
            moveTo(10.7f, 5.2f); lineTo(13.3f, 5.2f); lineTo(13.3f, 9.4f); lineTo(10.7f, 9.4f); close()
        },
    )

    val Shot: ImageVector = kzIcon("KzShot") {
        moveTo(3f, 7.5f); lineTo(8f, 7.5f); lineTo(9.5f, 5f); lineTo(14.5f, 5f)
        lineTo(16f, 7.5f); lineTo(21f, 7.5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(12f, 10.5f); lineTo(12f, 16f)
        moveTo(9.5f, 13.2f); lineTo(12f, 10.5f); lineTo(14.5f, 13.2f)
    }

    val Fit: ImageVector = kzIcon("KzFit") {
        moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
        moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
        moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
    }

    val Rotate: ImageVector = kzIcon("KzRotate") {
        moveTo(20f, 12f)
        curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f)
        curveTo(7.6f, 20f, 4f, 16.4f, 4f, 12f)
        curveTo(4f, 7.6f, 7.6f, 4f, 12f, 4f)
        lineTo(16f, 4f)
        moveTo(13.5f, 1.5f); lineTo(16.2f, 4f); lineTo(13.5f, 6.5f)
    }

    val Power: ImageVector = kzIcon("KzPower") {
        moveTo(12f, 3.5f); lineTo(12f, 11f)
        moveTo(17.5f, 6.2f)
        curveTo(19.6f, 8f, 20.8f, 10.5f, 20.5f, 13.4f)
        curveTo(20.1f, 17.6f, 16.5f, 20.6f, 12.3f, 20.5f)
        curveTo(8.1f, 20.4f, 4.7f, 17.1f, 4.5f, 13f)
        curveTo(4.4f, 10.2f, 5.6f, 7.9f, 7.6f, 6.2f)
    }

    val Plus: ImageVector = kzIcon("KzPlus") {
        moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f)
    }

    val Close: ImageVector = kzIcon("KzClose") {
        moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val Check: ImageVector = kzIcon("KzCheck") {
        moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 6.5f)
    }

    val ChevronLeft: ImageVector = kzIcon("KzChevronLeft") {
        moveTo(14.5f, 5f); lineTo(8f, 12f); lineTo(14.5f, 19f)
    }

    val ChevronRight: ImageVector = kzIcon("KzChevronRight") {
        moveTo(9.5f, 5f); lineTo(16f, 12f); lineTo(9.5f, 19f)
    }

    val ChevronDown: ImageVector = kzIcon("KzChevronDown") {
        moveTo(5f, 9f); lineTo(12f, 15.5f); lineTo(19f, 9f)
    }

    val Sliders: ImageVector = kzIcon("KzSliders") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 7f); lineTo(9f, 7.01f)
        moveTo(15f, 12f); lineTo(15f, 12.01f)
        moveTo(11f, 17f); lineTo(11f, 17.01f)
    }

    /**
     * Gamepad: kontroler digambar sendiri (permintaan pemilik 2026-10-10 —
     * jangan pakai ikon punya orang). Badan + dua grip digaris, D-Pad dan dua
     * tombol kanan diisi.
     */
    val Gamepad: ImageVector = kzTwoPath(
        "KzGamepad",
        outline = {
            moveTo(8f, 5.5f)
            lineTo(16f, 5.5f)
            curveTo(17.6f, 5.5f, 18.6f, 6.3f, 19.2f, 7.7f)
            lineTo(21.1f, 12.7f)
            curveTo(21.7f, 14.5f, 20.5f, 16.2f, 18.7f, 16.2f)
            curveTo(17.6f, 16.2f, 16.9f, 15.7f, 16.4f, 14.9f)
            lineTo(15.6f, 13.5f)
            lineTo(8.4f, 13.5f)
            lineTo(7.6f, 14.9f)
            curveTo(7.1f, 15.7f, 6.4f, 16.2f, 5.3f, 16.2f)
            curveTo(3.5f, 16.2f, 2.3f, 14.5f, 2.9f, 12.7f)
            lineTo(4.8f, 7.7f)
            curveTo(5.4f, 6.3f, 6.4f, 5.5f, 8f, 5.5f)
            close()
        },
        solid = {
            // D-Pad kiri.
            moveTo(6.9f, 8.2f); lineTo(8.1f, 8.2f); lineTo(8.1f, 9.2f); lineTo(9.1f, 9.2f)
            lineTo(9.1f, 10.4f); lineTo(8.1f, 10.4f); lineTo(8.1f, 11.4f); lineTo(6.9f, 11.4f)
            lineTo(6.9f, 10.4f); lineTo(5.9f, 10.4f); lineTo(5.9f, 9.2f); lineTo(6.9f, 9.2f)
            close()
            // Dua tombol kanan.
            moveTo(14.65f, 8.6f)
            arcToRelative(0.85f, 0.85f, 0f, true, false, 1.7f, 0f)
            arcToRelative(0.85f, 0.85f, 0f, true, false, -1.7f, 0f)
            close()
            moveTo(16.35f, 10.9f)
            arcToRelative(0.85f, 0.85f, 0f, true, false, 1.7f, 0f)
            arcToRelative(0.85f, 0.85f, 0f, true, false, -1.7f, 0f)
            close()
        },
    )

    /** Rumah: kembali ke beranda. */
    val Home: ImageVector = kzIcon("KzHome") {
        moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f)
        moveTo(6.5f, 9.5f); lineTo(6.5f, 19f); lineTo(17.5f, 19f); lineTo(17.5f, 9.5f)
        moveTo(10f, 19f); lineTo(10f, 14f); lineTo(14f, 14f); lineTo(14f, 19f)
    }

    val Lock: ImageVector = kzIcon("KzLock") {
        moveTo(5.5f, 10.5f); lineTo(18.5f, 10.5f); lineTo(18.5f, 20f); lineTo(5.5f, 20f); close()
        moveTo(8.5f, 10.5f); lineTo(8.5f, 7.5f)
        curveTo(8.5f, 4.7f, 10.2f, 3.5f, 12f, 3.5f)
        curveTo(13.8f, 3.5f, 15.5f, 4.7f, 15.5f, 7.5f)
        lineTo(15.5f, 10.5f)
    }

    val Shield: ImageVector = kzIcon("KzShield") {
        // Perisai: keamanan (vault, kepercayaan sertifikat). Garis sama:
        // stroke 1.7, ujung bulat, viewport 24.
        moveTo(12f, 3.2f)
        lineTo(18.4f, 5.6f)
        lineTo(18.4f, 11.2f)
        curveTo(18.4f, 15.3f, 15.9f, 18.6f, 12f, 20.8f)
        curveTo(8.1f, 18.6f, 5.6f, 15.3f, 5.6f, 11.2f)
        lineTo(5.6f, 5.6f)
        close()
    }

    val Info: ImageVector = kzIcon("KzInfo") {
        moveTo(12f, 3.5f)
        curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f)
        curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f)
        curveTo(7.3f, 20.5f, 3.5f, 16.7f, 3.5f, 12f)
        curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f)
        close()
        moveTo(12f, 11f); lineTo(12f, 16.5f)
        moveTo(12f, 7.6f); lineTo(12f, 7.61f)
    }

    val Trash: ImageVector = kzIcon("KzTrash") {
        moveTo(4.5f, 7f); lineTo(19.5f, 7f)
        moveTo(9.5f, 7f); lineTo(9.5f, 4.5f); lineTo(14.5f, 4.5f); lineTo(14.5f, 7f)
        moveTo(6.5f, 7f); lineTo(7.6f, 20f); lineTo(16.4f, 20f); lineTo(17.5f, 7f)
        moveTo(10.5f, 11f); lineTo(10.5f, 16.5f)
        moveTo(13.5f, 11f); lineTo(13.5f, 16.5f)
    }

    val Wifi: ImageVector = kzIcon("KzWifi") {
        moveTo(3f, 9.2f)
        curveTo(8.2f, 4.4f, 15.8f, 4.4f, 21f, 9.2f)
        moveTo(6.2f, 12.8f)
        curveTo(9.4f, 9.9f, 14.6f, 9.9f, 17.8f, 12.8f)
        moveTo(9.4f, 16.4f)
        curveTo(10.9f, 15.1f, 13.1f, 15.1f, 14.6f, 16.4f)
        moveTo(12f, 19.6f); lineTo(12f, 19.61f)
    }

    val Volume: ImageVector = kzIcon("KzVolume") {
        moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f)
        lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close()
        moveTo(15f, 9f); moveTo(15f, 9f)
        curveTo(16.6f, 10.6f, 16.6f, 13.4f, 15f, 15f)
    }

    val Mic: ImageVector = kzIcon("KzMic") {
        moveTo(9f, 6f)
        curveTo(9f, 4.3f, 10.3f, 3f, 12f, 3f)
        curveTo(13.7f, 3f, 15f, 4.3f, 15f, 6f)
        lineTo(15f, 11f)
        curveTo(15f, 12.7f, 13.7f, 14f, 12f, 14f)
        curveTo(10.3f, 14f, 9f, 12.7f, 9f, 11f)
        close()
        moveTo(6f, 11.5f)
        curveTo(6f, 15.1f, 8.7f, 17.5f, 12f, 17.5f)
        curveTo(15.3f, 17.5f, 18f, 15.1f, 18f, 11.5f)
        moveTo(12f, 17.5f); lineTo(12f, 21f)
    }

    val Folder: ImageVector = kzIcon("KzFolder") {
        moveTo(3f, 6.5f); lineTo(9.5f, 6.5f); lineTo(11.5f, 9f); lineTo(21f, 9f)
        lineTo(21f, 18.5f); lineTo(3f, 18.5f); close()
    }

    val Clip: ImageVector = kzIcon("KzClip") {
        moveTo(8.5f, 6f); lineTo(15.5f, 6f); lineTo(15.5f, 9f); lineTo(8.5f, 9f); close()
        moveTo(10f, 6f); lineTo(10f, 3.5f); lineTo(14f, 3.5f); lineTo(14f, 6f)
        moveTo(6.5f, 9f); lineTo(6.5f, 20.5f); lineTo(17.5f, 20.5f); lineTo(17.5f, 9f)
    }

    /** Cog-shaped settings glyph (teeth, not sun rays). */
    val Gear: ImageVector = kzIcon("KzGear") {
        val profile = listOf(
            -PI / 8.0 to 7.8f,
            -PI / 12.0 to 7.8f,
            -PI / 12.0 to 9.4f,
            PI / 12.0 to 9.4f,
            PI / 12.0 to 7.8f,
            PI / 8.0 to 7.8f,
        )
        var first = true
        for (tooth in 0 until 8) {
            val center = -PI / 2.0 + tooth * PI / 4.0
            for ((offset, radius) in profile) {
                val angle = center + offset
                val x = 12f + radius * cos(angle).toFloat()
                val y = 12f + radius * sin(angle).toFloat()
                if (first) {
                    moveTo(x, y)
                    first = false
                } else {
                    lineTo(x, y)
                }
            }
        }
        close()
        moveTo(15.3f, 12f)
        curveTo(15.3f, 13.8f, 13.8f, 15.3f, 12f, 15.3f)
        curveTo(10.2f, 15.3f, 8.7f, 13.8f, 8.7f, 12f)
        curveTo(8.7f, 10.2f, 10.2f, 8.7f, 12f, 8.7f)
        curveTo(13.8f, 8.7f, 15.3f, 10.2f, 15.3f, 12f)
        close()
    }

    val Menu: ImageVector = kzIcon("KzMenu") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(14f, 12f)
        moveTo(4f, 17f); lineTo(20f, 17f)
    }

    val Grid: ImageVector = kzIcon("KzGrid") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }

    /** Aksi klik kiri: siluet mouse dengan tombol kiri terisi penuh. */
    val ClickLeft: ImageVector = kzTwoPath(
        name = "KzClickLeft",
        outline = {
            moveTo(6.5f, 9f)
            curveTo(6.5f, 5.5f, 8.8f, 3.5f, 12f, 3.5f)
            curveTo(15.2f, 3.5f, 17.5f, 5.5f, 17.5f, 9f)
            lineTo(17.5f, 15f)
            curveTo(17.5f, 18.5f, 15.2f, 20.5f, 12f, 20.5f)
            curveTo(8.8f, 20.5f, 6.5f, 18.5f, 6.5f, 15f)
            close()
            moveTo(6.5f, 10.5f); lineTo(17.5f, 10.5f)
            moveTo(12f, 3.5f); lineTo(12f, 10.5f)
        },
        solid = {
            moveTo(11.6f, 4.2f)
            curveTo(9.1f, 4.4f, 7.2f, 6.1f, 7.2f, 9f)
            lineTo(7.2f, 10.1f)
            lineTo(11.6f, 10.1f)
            close()
        },
    )

    /** Tukar klik kiri & kanan mouse (Mouse Left/Right Swap). */
    val MouseSwap: ImageVector = kzIcon("KzMouseSwap") {
        moveTo(7f, 9.5f)
        curveTo(7f, 6.2f, 9f, 4f, 12f, 4f)
        curveTo(15f, 4f, 17f, 6.2f, 17f, 9.5f)
        lineTo(17f, 14.5f)
        curveTo(17f, 17.8f, 15f, 20f, 12f, 20f)
        curveTo(9f, 20f, 7f, 17.8f, 7f, 14.5f)
        close()
        moveTo(12f, 4f); lineTo(12f, 10f)
        moveTo(8.5f, 13.5f); lineTo(15.5f, 13.5f)
        moveTo(10.5f, 11.7f); lineTo(8.5f, 13.5f); lineTo(10.5f, 15.3f)
        moveTo(13.5f, 11.7f); lineTo(15.5f, 13.5f); lineTo(13.5f, 15.3f)
    }

    /** Ikon tombol Windows (4 panel jendela). */
    val Windows: ImageVector = kzSolid("KzWindows") {
        moveTo(4f, 4.5f); lineTo(11f, 4.5f); lineTo(11f, 11.2f); lineTo(4f, 11.2f); close()
        moveTo(12.8f, 4.5f); lineTo(20f, 4.5f); lineTo(20f, 11.2f); lineTo(12.8f, 11.2f); close()
        moveTo(4f, 12.8f); lineTo(11f, 12.8f); lineTo(11f, 19.5f); lineTo(4f, 19.5f); close()
        moveTo(12.8f, 12.8f); lineTo(20f, 12.8f); lineTo(20f, 19.5f); lineTo(12.8f, 19.5f); close()
    }

    /** Dual Monitor / Multi-Display switcher icon. */
    val DualMonitor: ImageVector = kzIcon("KzDualMonitor") {
        moveTo(2.5f, 6.5f); lineTo(15.5f, 6.5f); lineTo(15.5f, 15.5f); lineTo(2.5f, 15.5f); close()
        moveTo(6.5f, 19f); lineTo(11.5f, 19f)
        moveTo(9f, 15.5f); lineTo(9f, 19f)
        moveTo(9f, 4f); lineTo(21.5f, 4f); lineTo(21.5f, 13f); lineTo(15.5f, 13f)
    }

    /** Users / Active Windows Session switcher icon. */
    val Users: ImageVector = kzIcon("KzUsers") {
        moveTo(9.5f, 11f)
        curveTo(11.4f, 11f, 13f, 9.4f, 13f, 7.5f)
        curveTo(13f, 5.6f, 11.4f, 4f, 9.5f, 4f)
        curveTo(7.6f, 4f, 6f, 5.6f, 6f, 7.5f)
        curveTo(6f, 9.4f, 7.6f, 11f, 9.5f, 11f)
        close()
        moveTo(3.5f, 19.5f)
        curveTo(3.5f, 16f, 6.1f, 13.8f, 9.5f, 13.8f)
        curveTo(12.9f, 13.8f, 15.5f, 16f, 15.5f, 19.5f)
        moveTo(15.5f, 4.5f)
        curveTo(17.2f, 5f, 18.3f, 6.4f, 18.3f, 8f)
        curveTo(18.3f, 9.6f, 17.2f, 11f, 15.5f, 11.5f)
        moveTo(17f, 14.2f)
        curveTo(19.3f, 14.8f, 20.8f, 16.7f, 20.8f, 19.5f)
    }

    val ArrowUp: ImageVector = kzIcon("KzArrowUp") {
        moveTo(12f, 19f); lineTo(12f, 5f)
        moveTo(6.5f, 10.5f); lineTo(12f, 5f); lineTo(17.5f, 10.5f)
    }

    val ArrowDown: ImageVector = kzIcon("KzArrowDown") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(6.5f, 13.5f); lineTo(12f, 19f); lineTo(17.5f, 13.5f)
    }

    val ArrowLeft: ImageVector = kzIcon("KzArrowLeft") {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(10.5f, 6.5f); lineTo(5f, 12f); lineTo(10.5f, 17.5f)
    }

    val ArrowRight: ImageVector = kzIcon("KzArrowRight") {
        moveTo(5f, 12f); lineTo(19f, 12f)
        moveTo(13.5f, 6.5f); lineTo(19f, 12f); lineTo(13.5f, 17.5f)
    }

    val EnterKey: ImageVector = kzIcon("KzEnterKey") {
        moveTo(18.5f, 6f); lineTo(18.5f, 13.5f); lineTo(5.5f, 13.5f)
        moveTo(9.5f, 9.5f); lineTo(5.5f, 13.5f); lineTo(9.5f, 17.5f)
    }

    val BackspaceKey: ImageVector = kzIcon("KzBackspaceKey") {
        moveTo(20.5f, 6.5f); lineTo(9f, 6.5f); lineTo(3.5f, 12f); lineTo(9f, 17.5f); lineTo(20.5f, 17.5f); close()
        moveTo(11.5f, 9.5f); lineTo(16.5f, 14.5f)
        moveTo(16.5f, 9.5f); lineTo(11.5f, 14.5f)
    }

    val TabKey: ImageVector = kzIcon("KzTabKey") {
        moveTo(4f, 12f); lineTo(16.5f, 12f)
        moveTo(12f, 7.5f); lineTo(16.5f, 12f); lineTo(12f, 16.5f)
        moveTo(19.5f, 6.5f); lineTo(19.5f, 17.5f)
    }

    val SpaceKey: ImageVector = kzIcon("KzSpaceKey") {
        moveTo(4f, 10.5f); lineTo(4f, 15.5f); lineTo(20f, 15.5f); lineTo(20f, 10.5f)
    }

    val ShiftKey: ImageVector = kzIcon("KzShiftKey") {
        moveTo(12f, 4.5f); lineTo(20f, 12.5f); lineTo(15.5f, 12.5f)
        lineTo(15.5f, 19.5f); lineTo(8.5f, 19.5f); lineTo(8.5f, 12.5f)
        lineTo(4f, 12.5f); close()
    }

    val Copy: ImageVector = kzIcon("KzCopy") {
        moveTo(9f, 8.5f); lineTo(19.5f, 8.5f); lineTo(19.5f, 20f); lineTo(9f, 20f); close()
        moveTo(15f, 8.5f); lineTo(15f, 4.5f); lineTo(4.5f, 4.5f); lineTo(4.5f, 16f); lineTo(9f, 16f)
    }

    val Undo: ImageVector = kzIcon("KzUndo") {
        moveTo(9f, 7f); lineTo(4.5f, 11.5f); lineTo(9f, 16f)
        moveTo(4.5f, 11.5f); lineTo(14.5f, 11.5f)
        curveTo(17.5f, 11.5f, 19.5f, 13.5f, 19.5f, 16f)
        curveTo(19.5f, 17.5f, 18.8f, 18.8f, 17.5f, 19.5f)
    }

    val Redo: ImageVector = kzIcon("KzRedo") {
        moveTo(15f, 7f); lineTo(19.5f, 11.5f); lineTo(15f, 16f)
        moveTo(19.5f, 11.5f); lineTo(9.5f, 11.5f)
        curveTo(6.5f, 11.5f, 4.5f, 13.5f, 4.5f, 16f)
        curveTo(4.5f, 17.5f, 5.2f, 18.8f, 6.5f, 19.5f)
    }

    val Search: ImageVector = kzIcon("KzSearch") {
        moveTo(11f, 4.5f)
        curveTo(14.6f, 4.5f, 17.5f, 7.4f, 17.5f, 11f)
        curveTo(17.5f, 14.6f, 14.6f, 17.5f, 11f, 17.5f)
        curveTo(7.4f, 17.5f, 4.5f, 14.6f, 4.5f, 11f)
        curveTo(4.5f, 7.4f, 7.4f, 4.5f, 11f, 4.5f)
        close()
        moveTo(15.8f, 15.8f); lineTo(20f, 20f)
    }

    val Terminal: ImageVector = kzIcon("KzTerminal") {
        moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close()
        moveTo(7f, 9.5f); lineTo(10.5f, 12.5f); lineTo(7f, 15.5f)
        moveTo(12.5f, 15.5f); lineTo(16.5f, 15.5f)
    }

    /** Hati (dukung lewat Saweria). */
    val Heart: ImageVector = kzIcon("KzHeart") {
        moveTo(12f, 20f)
        curveTo(4.5f, 15f, 3f, 11.6f, 3f, 9.2f)
        curveTo(3f, 6.4f, 5.2f, 4.5f, 7.7f, 4.5f)
        curveTo(9.6f, 4.5f, 11.2f, 5.7f, 12f, 7.4f)
        curveTo(12.8f, 5.7f, 14.4f, 4.5f, 16.3f, 4.5f)
        curveTo(18.8f, 4.5f, 21f, 6.4f, 21f, 9.2f)
        curveTo(21f, 11.6f, 19.5f, 15f, 12f, 20f)
        close()
    }

    /** Sponsor: dua tangan menopang hati kecil. */
    val Sponsor: ImageVector = kzTwoPath(
        name = "KzSponsor",
        outline = {
            moveTo(3.5f, 13.5f); lineTo(3.5f, 20f)
            moveTo(20.5f, 13.5f); lineTo(20.5f, 20f)
            moveTo(3.5f, 14.6f); lineTo(7.6f, 13.4f)
            moveTo(20.5f, 14.6f); lineTo(16.4f, 13.4f)
            moveTo(12f, 12.6f)
            curveTo(9.2f, 10.6f, 8.6f, 9.2f, 8.6f, 8f)
            curveTo(8.6f, 6.6f, 9.6f, 5.7f, 10.8f, 5.7f)
            curveTo(11.5f, 5.7f, 12f, 6.2f, 12f, 6.8f)
            curveTo(12f, 6.2f, 12.5f, 5.7f, 13.2f, 5.7f)
            curveTo(14.4f, 5.7f, 15.4f, 6.6f, 15.4f, 8f)
            curveTo(15.4f, 9.2f, 14.8f, 10.6f, 12f, 12.6f)
            close()
        },
        solid = {
            moveTo(4.6f, 15.4f); lineTo(6.4f, 15f); lineTo(6.4f, 16.8f); lineTo(4.6f, 16.8f); close()
        },
    )

    /** Bintang (beri bintang di GitHub). */
    val Star: ImageVector = kzIcon("KzStar") {
        moveTo(12f, 3.6f); lineTo(14.5f, 9.2f); lineTo(20.5f, 9.8f)
        lineTo(16f, 13.8f); lineTo(17.4f, 19.7f); lineTo(12f, 16.5f)
        lineTo(6.6f, 19.7f); lineTo(8f, 13.8f); lineTo(3.5f, 9.8f)
        lineTo(9.5f, 9.2f); close()
    }

    /** Tautan keluar (buat di browser). */
    /** Mata: toggle lihat/sembunyikan password. */
    val Eye: ImageVector = kzIcon("KzEye") {
        moveTo(3.5f, 12f)
        curveTo(5.4f, 8.2f, 8.4f, 5.8f, 12f, 5.8f)
        curveTo(15.6f, 5.8f, 18.6f, 8.2f, 20.5f, 12f)
        curveTo(18.6f, 15.8f, 15.6f, 18.2f, 12f, 18.2f)
        curveTo(8.4f, 18.2f, 5.4f, 15.8f, 3.5f, 12f)
        close()
        moveTo(14.9f, 12f)
        curveTo(14.9f, 13.6f, 13.6f, 14.9f, 12f, 14.9f)
        curveTo(10.4f, 14.9f, 9.1f, 13.6f, 9.1f, 12f)
        curveTo(9.1f, 10.4f, 10.4f, 9.1f, 12f, 9.1f)
        curveTo(13.6f, 9.1f, 14.9f, 10.4f, 14.9f, 12f)
        close()
    }

    /** Mata dicoret: password sedang tampil, ketuk untuk sembunyikan. */
    val EyeOff: ImageVector = kzIcon("KzEyeOff") {
        moveTo(3.5f, 12f)
        curveTo(5.4f, 8.2f, 8.4f, 5.8f, 12f, 5.8f)
        curveTo(15.6f, 5.8f, 18.6f, 8.2f, 20.5f, 12f)
        curveTo(18.6f, 15.8f, 15.6f, 18.2f, 12f, 18.2f)
        curveTo(8.4f, 18.2f, 5.4f, 15.8f, 3.5f, 12f)
        close()
        moveTo(14.9f, 12f)
        curveTo(14.9f, 13.6f, 13.6f, 14.9f, 12f, 14.9f)
        curveTo(10.4f, 14.9f, 9.1f, 13.6f, 9.1f, 12f)
        curveTo(9.1f, 10.4f, 10.4f, 9.1f, 12f, 9.1f)
        curveTo(13.6f, 9.1f, 14.9f, 10.4f, 14.9f, 12f)
        close()
        moveTo(4.5f, 19.5f); lineTo(19.5f, 4.5f)
    }

    val ExternalLink: ImageVector = kzIcon("KzExternalLink") {
        moveTo(14f, 4.5f); lineTo(19.5f, 4.5f); lineTo(19.5f, 10f)
        moveTo(19.5f, 4.5f); lineTo(11.5f, 12.5f)
        moveTo(17f, 14.2f); lineTo(17f, 19f); lineTo(5f, 19f); lineTo(5f, 7f); lineTo(9.8f, 7f)
    }

    // ---- kontrol pemutar musik ----

    /** Acak: dua jalur bersilang. */
    val Shuffle: ImageVector = kzIcon("KzShuffle") {
        moveTo(3f, 6.5f); lineTo(7f, 6.5f); lineTo(17f, 17.5f); lineTo(21f, 17.5f)
        moveTo(3f, 17.5f); lineTo(7f, 17.5f); lineTo(17f, 6.5f); lineTo(21f, 6.5f)
        moveTo(18.2f, 4.2f); lineTo(21f, 6.5f); lineTo(18.2f, 8.8f)
        moveTo(18.2f, 15.2f); lineTo(21f, 17.5f); lineTo(18.2f, 19.8f)
    }

    /** Ulang semua: putaran dengan dua kepala panah. */
    val Repeat: ImageVector = kzIcon("KzRepeat") {
        moveTo(6.5f, 7f); lineTo(17.5f, 7f)
        curveTo(19.4f, 7f, 21f, 8.6f, 21f, 10.5f); lineTo(21f, 12f)
        moveTo(17.5f, 17f); lineTo(6.5f, 17f)
        curveTo(4.6f, 17f, 3f, 15.4f, 3f, 13.5f); lineTo(3f, 12f)
        moveTo(15.2f, 4.6f); lineTo(17.8f, 7f); lineTo(15.2f, 9.4f)
        moveTo(8.8f, 14.6f); lineTo(6.2f, 17f); lineTo(8.8f, 19.4f)
    }

    /** Ulang satu lagu: putaran yang sama dengan angka 1 di tengahnya. */
    val RepeatOne: ImageVector = kzIcon("KzRepeatOne") {
        moveTo(6.5f, 7f); lineTo(17.5f, 7f)
        curveTo(19.4f, 7f, 21f, 8.6f, 21f, 10.5f); lineTo(21f, 12f)
        moveTo(17.5f, 17f); lineTo(6.5f, 17f)
        curveTo(4.6f, 17f, 3f, 15.4f, 3f, 13.5f); lineTo(3f, 12f)
        moveTo(15.2f, 4.6f); lineTo(17.8f, 7f); lineTo(15.2f, 9.4f)
        moveTo(8.8f, 14.6f); lineTo(6.2f, 17f); lineTo(8.8f, 19.4f)
        moveTo(11.1f, 10.6f); lineTo(12.7f, 9.8f); lineTo(12.7f, 14.6f)
    }

    /** Antrian lagu: daftar dengan tanda tambah. */
    val Queue: ImageVector = kzIcon("KzQueue") {
        moveTo(3.5f, 6.5f); lineTo(15f, 6.5f)
        moveTo(3.5f, 11f); lineTo(15f, 11f)
        moveTo(3.5f, 15.5f); lineTo(11.5f, 15.5f)
        moveTo(17.5f, 14.5f); lineTo(17.5f, 20f)
        moveTo(14.8f, 17.2f); lineTo(20.2f, 17.2f)
    }

    /** Mundur cepat 10 detik. */
    val Rewind: ImageVector = kzIcon("KzRewind") {
        moveTo(12.5f, 6.5f); lineTo(6.5f, 12f); lineTo(12.5f, 17.5f)
        moveTo(19f, 6.5f); lineTo(13f, 12f); lineTo(19f, 17.5f)
    }

    /** Maju cepat 10 detik. */
    val Forward: ImageVector = kzIcon("KzForward") {
        moveTo(11.5f, 6.5f); lineTo(17.5f, 12f); lineTo(11.5f, 17.5f)
        moveTo(5f, 6.5f); lineTo(11f, 12f); lineTo(5f, 17.5f)
    }

    /** Hentikan pemutaran. */
    val Stop: ImageVector = kzSolid("KzStop") {
        moveTo(6.5f, 6.5f); lineTo(17.5f, 6.5f); lineTo(17.5f, 17.5f); lineTo(6.5f, 17.5f); close()
    }

    /** Pustaka: rak berisi tiga media. */
    val Library: ImageVector = kzIcon("KzLibrary") {
        moveTo(3.5f, 5.5f); lineTo(8f, 5.5f); lineTo(8f, 19f); lineTo(3.5f, 19f); close()
        moveTo(10f, 5.5f); lineTo(14f, 5.5f); lineTo(14f, 19f); lineTo(10f, 19f); close()
        moveTo(16.4f, 6.2f); lineTo(20.2f, 7.2f); lineTo(17.4f, 18.4f); lineTo(16.4f, 6.2f)
    }

    /** Segarkan daftar. */
    val Refresh: ImageVector = kzIcon("KzRefresh") {
        moveTo(19.5f, 8.6f)
        curveTo(18.1f, 6f, 15.3f, 4.2f, 12f, 4.2f)
        curveTo(7.7f, 4.2f, 4.2f, 7.7f, 4.2f, 12f)
        curveTo(4.2f, 16.3f, 7.7f, 19.8f, 12f, 19.8f)
        curveTo(15.6f, 19.8f, 18.6f, 17.4f, 19.5f, 14.1f)
        moveTo(19.8f, 4.4f); lineTo(19.8f, 8.7f); lineTo(15.5f, 8.7f)
    }

    /** Kirim ke PC. */
    val Upload: ImageVector = kzIcon("KzUpload") {
        moveTo(12f, 15.5f); lineTo(12f, 4.5f)
        moveTo(8f, 8.5f); lineTo(12f, 4.5f); lineTo(16f, 8.5f)
        moveTo(4.5f, 14.5f); lineTo(4.5f, 19.5f); lineTo(19.5f, 19.5f); lineTo(19.5f, 14.5f)
    }

    /** Simpan dari PC ke HP. */
    val Download: ImageVector = kzIcon("KzDownload") {
        moveTo(12f, 4.5f); lineTo(12f, 15.5f)
        moveTo(8f, 11.5f); lineTo(12f, 15.5f); lineTo(16f, 11.5f)
        moveTo(4.5f, 14.5f); lineTo(4.5f, 19.5f); lineTo(19.5f, 19.5f); lineTo(19.5f, 14.5f)
    }

    /** Satu dokumen. */
    val File: ImageVector = kzIcon("KzFile") {
        moveTo(6f, 3.5f); lineTo(14f, 3.5f); lineTo(18.5f, 8f); lineTo(18.5f, 20.5f)
        lineTo(6f, 20.5f); close()
        moveTo(14f, 3.5f); lineTo(14f, 8f); lineTo(18.5f, 8f)
    }

    // ------------------------------------------------------------------
    // Ikon spesifik untuk tombol HUD (aksi umum PC) — supaya langsung dikenali.
    // ------------------------------------------------------------------

    /** Folder sungguhan (File Explorer). `Folder` lama bergambar panah unduh. */
    val FolderOpen: ImageVector = kzIcon("KzFolderOpen") {
        moveTo(3.5f, 6.5f); lineTo(9.5f, 6.5f); lineTo(11.5f, 8.5f); lineTo(20.5f, 8.5f)
        lineTo(20.5f, 18.5f); lineTo(3.5f, 18.5f); close()
        moveTo(3.5f, 11.5f); lineTo(20.5f, 11.5f)
    }

    /** Disket: simpan (Ctrl+S). */
    val Save: ImageVector = kzIcon("KzSave") {
        moveTo(4f, 4f); lineTo(16.5f, 4f); lineTo(20f, 7.5f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        moveTo(8f, 4f); lineTo(8f, 9f); lineTo(15f, 9f); lineTo(15f, 4f)
        moveTo(7f, 20f); lineTo(7f, 14f); lineTo(17f, 14f); lineTo(17f, 20f)
    }

    /** Gunting: potong (Ctrl+X). */
    val Cut: ImageVector = kzIcon("KzCut") {
        moveTo(4.4f, 17f)
        curveTo(4.4f, 15.7f, 5.5f, 14.6f, 6.8f, 14.6f)
        curveTo(8.1f, 14.6f, 9.2f, 15.7f, 9.2f, 17f)
        curveTo(9.2f, 18.3f, 8.1f, 19.4f, 6.8f, 19.4f)
        curveTo(5.5f, 19.4f, 4.4f, 18.3f, 4.4f, 17f)
        close()
        moveTo(14.8f, 17f)
        curveTo(14.8f, 15.7f, 15.9f, 14.6f, 17.2f, 14.6f)
        curveTo(18.5f, 14.6f, 19.6f, 15.7f, 19.6f, 17f)
        curveTo(19.6f, 18.3f, 18.5f, 19.4f, 17.2f, 19.4f)
        curveTo(15.9f, 19.4f, 14.8f, 18.3f, 14.8f, 17f)
        close()
        moveTo(8.2f, 14.9f); lineTo(17.5f, 4.5f)
        moveTo(15.8f, 14.9f); lineTo(6.5f, 4.5f)
    }

    /** Pilih semua (Ctrl+A): bingkai putus-putus dengan huruf A. */
    val SelectAll: ImageVector = kzIcon("KzSelectAll") {
        moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
        moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
        moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
        moveTo(9.5f, 15.5f); lineTo(12f, 8.5f); lineTo(14.5f, 15.5f)
        moveTo(10.4f, 13.2f); lineTo(13.6f, 13.2f)
    }

    /** Layar penuh (F11): empat sudut. */
    val Fullscreen: ImageVector = kzIcon("KzFullscreen") {
        moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
        moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
        moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
    }

    /** Muat ulang (F5 / Ctrl+R). */
    val Refresh: ImageVector = kzIcon("KzRefresh") {
        moveTo(19f, 12f)
        curveTo(19f, 15.9f, 15.9f, 19f, 12f, 19f)
        curveTo(8.1f, 19f, 5f, 15.9f, 5f, 12f)
        curveTo(5f, 8.1f, 8.1f, 5f, 12f, 5f)
        curveTo(14.1f, 5f, 16f, 5.9f, 17.3f, 7.4f)
        moveTo(17.5f, 3.8f); lineTo(17.5f, 7.5f); lineTo(13.8f, 7.5f)
    }

    /** Cetak (Ctrl+P). */
    val Print: ImageVector = kzIcon("KzPrint") {
        moveTo(7f, 9f); lineTo(7f, 4f); lineTo(17f, 4f); lineTo(17f, 9f)
        moveTo(7f, 17f); lineTo(4f, 17f); lineTo(4f, 9f); lineTo(20f, 9f); lineTo(20f, 17f); lineTo(17f, 17f)
        moveTo(7f, 14f); lineTo(17f, 14f); lineTo(17f, 20f); lineTo(7f, 20f); close()
    }

    val PageUp: ImageVector = kzIcon("KzPageUp") {
        moveTo(7f, 11f); lineTo(12f, 6f); lineTo(17f, 11f)
        moveTo(7f, 18f); lineTo(12f, 13f); lineTo(17f, 18f)
    }

    val PageDown: ImageVector = kzIcon("KzPageDown") {
        moveTo(7f, 6f); lineTo(12f, 11f); lineTo(17f, 6f)
        moveTo(7f, 13f); lineTo(12f, 18f); lineTo(17f, 13f)
    }

    /** Home: panah ke batas kiri. */
    val HomeKey: ImageVector = kzIcon("KzHomeKey") {
        moveTo(4f, 5f); lineTo(4f, 19f)
        moveTo(20f, 12f); lineTo(8f, 12f)
        moveTo(12f, 8f); lineTo(8f, 12f); lineTo(12f, 16f)
    }

    /** End: panah ke batas kanan. */
    val EndKey: ImageVector = kzIcon("KzEndKey") {
        moveTo(20f, 5f); lineTo(20f, 19f)
        moveTo(4f, 12f); lineTo(16f, 12f)
        moveTo(12f, 8f); lineTo(16f, 12f); lineTo(12f, 16f)
    }

    /** Task Manager (Ctrl+Shift+Esc): jendela dengan grafik denyut. */
    val TaskManager: ImageVector = kzIcon("KzTaskManager") {
        moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close()
        moveTo(6.5f, 13f); lineTo(9.5f, 13f); lineTo(11f, 9f); lineTo(13f, 16f); lineTo(14.5f, 12f); lineTo(17.5f, 12f)
    }

    val ZoomIn: ImageVector = kzIcon("KzZoomIn") {
        moveTo(11f, 4.5f)
        curveTo(14.6f, 4.5f, 17.5f, 7.4f, 17.5f, 11f)
        curveTo(17.5f, 14.6f, 14.6f, 17.5f, 11f, 17.5f)
        curveTo(7.4f, 17.5f, 4.5f, 14.6f, 4.5f, 11f)
        curveTo(4.5f, 7.4f, 7.4f, 4.5f, 11f, 4.5f)
        close()
        moveTo(15.8f, 15.8f); lineTo(20f, 20f)
        moveTo(8.5f, 11f); lineTo(13.5f, 11f)
        moveTo(11f, 8.5f); lineTo(11f, 13.5f)
    }

    val ZoomOut: ImageVector = kzIcon("KzZoomOut") {
        moveTo(11f, 4.5f)
        curveTo(14.6f, 4.5f, 17.5f, 7.4f, 17.5f, 11f)
        curveTo(17.5f, 14.6f, 14.6f, 17.5f, 11f, 17.5f)
        curveTo(7.4f, 17.5f, 4.5f, 14.6f, 4.5f, 11f)
        curveTo(4.5f, 7.4f, 7.4f, 4.5f, 11f, 4.5f)
        close()
        moveTo(15.8f, 15.8f); lineTo(20f, 20f)
        moveTo(8.5f, 11f); lineTo(13.5f, 11f)
    }

    val VolumeUp: ImageVector = kzIcon("KzVolumeUp") {
        moveTo(3.5f, 9.5f); lineTo(7f, 9.5f); lineTo(11.5f, 5.5f); lineTo(11.5f, 18.5f)
        lineTo(7f, 14.5f); lineTo(3.5f, 14.5f); close()
        moveTo(14.5f, 9.3f); curveTo(16f, 10.8f, 16f, 13.2f, 14.5f, 14.7f)
        moveTo(17.2f, 6.8f); curveTo(20.2f, 9.8f, 20.2f, 14.2f, 17.2f, 17.2f)
    }

    val VolumeDown: ImageVector = kzIcon("KzVolumeDown") {
        moveTo(4.5f, 9.5f); lineTo(8f, 9.5f); lineTo(12.5f, 5.5f); lineTo(12.5f, 18.5f)
        lineTo(8f, 14.5f); lineTo(4.5f, 14.5f); close()
        moveTo(15.8f, 9.6f); curveTo(17.3f, 11.1f, 17.3f, 12.9f, 15.8f, 14.4f)
    }

    val VolumeMute: ImageVector = kzIcon("KzVolumeMute") {
        moveTo(3.5f, 9.5f); lineTo(7f, 9.5f); lineTo(11.5f, 5.5f); lineTo(11.5f, 18.5f)
        lineTo(7f, 14.5f); lineTo(3.5f, 14.5f); close()
        moveTo(15f, 9.5f); lineTo(20.5f, 15f)
        moveTo(20.5f, 9.5f); lineTo(15f, 15f)
    }

    /** Mikrofon dicoret: mikrofon mati. */
    val MicOff: ImageVector = kzIcon("KzMicOff") {
        moveTo(9f, 6f)
        curveTo(9f, 4.3f, 10.3f, 3f, 12f, 3f)
        curveTo(13.7f, 3f, 15f, 4.3f, 15f, 6f)
        lineTo(15f, 11f)
        curveTo(15f, 12.7f, 13.7f, 14f, 12f, 14f)
        curveTo(10.3f, 14f, 9f, 12.7f, 9f, 11f)
        close()
        moveTo(6f, 11.5f)
        curveTo(6f, 15.1f, 8.7f, 17.5f, 12f, 17.5f)
        curveTo(15.3f, 17.5f, 18f, 15.1f, 18f, 11.5f)
        moveTo(12f, 17.5f); lineTo(12f, 21f)
        moveTo(4f, 4f); lineTo(20f, 20f)
    }

    /** Joystick: bola di atas tuas dan dudukan (beda dari ikon gamepad). */
    val Joystick: ImageVector = kzIcon("KzJoystick") {
        moveTo(8.4f, 7.5f)
        curveTo(8.4f, 5.5f, 10f, 3.9f, 12f, 3.9f)
        curveTo(14f, 3.9f, 15.6f, 5.5f, 15.6f, 7.5f)
        curveTo(15.6f, 9.5f, 14f, 11.1f, 12f, 11.1f)
        curveTo(10f, 11.1f, 8.4f, 9.5f, 8.4f, 7.5f)
        close()
        moveTo(12f, 11.1f); lineTo(12f, 15.5f)
        moveTo(3.5f, 20f); lineTo(5.5f, 15.5f); lineTo(18.5f, 15.5f); lineTo(20.5f, 20f); close()
    }

    /** Petir: makro otomatis. */
    val Bolt: ImageVector = kzIcon("KzBolt") {
        moveTo(13f, 3.5f); lineTo(6f, 13.5f); lineTo(11.5f, 13.5f); lineTo(10.5f, 20.5f)
        lineTo(18f, 10f); lineTo(12.5f, 10f); close()
    }
}
