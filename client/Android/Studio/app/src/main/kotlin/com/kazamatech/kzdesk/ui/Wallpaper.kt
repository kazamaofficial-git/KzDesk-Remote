package com.kazamatech.kzdesk.ui

import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kazamatech.kzdesk.R

/**
 * Wallpaper desktop untuk preview perangkat + latar layar koneksi.
 *
 * Windows 11 memakai foto asli (drawable-nodpi/kz_win11_wall.jpg) supaya
 * preview perangkat Windows terasa seperti desktop yang benar-benar dilihat
 * user. Sisanya digambar prosedural (Canvas): bobot APK nol dan tidak ada
 * urusan lisensi gambar.
 */
enum class KzWall(val label: String) {
    WIN11("Windows 11"),
    WIN10("Windows 10"),
    MACOS("macOS"),
    NEUTRAL("Netral"),
}

/** Tebak motif dari nama/label perangkat. */
fun KzWall.forDevice(label: String?): KzWall {
    val l = label?.lowercase().orEmpty()
    return when {
        l.contains("mac") || l.contains("osx") || l.contains("macbook") -> KzWall.MACOS
        l.contains("win 10") || l.contains("win10") || l.contains("windows 10") -> KzWall.WIN10
        l.contains("win") || l.contains("11") -> KzWall.WIN11
        else -> this
    }
}

@Composable
fun KzWallpaper(
    wall: KzWall,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 0.dp,
    dim: Float = 0f,
) {
    val blurModifier = if (blurRadius > 0.dp && Build.VERSION.SDK_INT >= 31) {
        Modifier.blur(blurRadius)
    } else {
        Modifier
    }
    Box(modifier.fillMaxSize().then(blurModifier)) {
        if (wall == KzWall.WIN11) {
            Image(
                painter = painterResource(R.drawable.kz_win11_wall),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            when (wall) {
                KzWall.WIN11 -> {
                    // Fotonya digambar di luar Canvas (lihat Image di atas) —
                    // scrim tipis hanya untuk menjaga teks terbaca; sengaja
                    // ringan supaya wallpaper tidak lagi terlihat hitam.
                    drawRect(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x33000000), Color(0x14000000), Color(0x66000000),
                            ),
                        )
                    )
                }

                KzWall.WIN10 -> {
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF17364F), Color(0xFF10283C), Color(0xFF0C1D2C)),
                            start = Offset(0f, 0f),
                            end = Offset(w * 0.6f, h),
                        )
                    )
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF2E79B8), Color(0x00153248)),
                            start = Offset(0f, 0f),
                            end = Offset(w * 0.75f, h * 0.9f),
                        )
                    )
                    drawOval(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF55A8DC), Color(0x00000000)),
                            center = Offset(w * 0.3f, h * 0.3f),
                            radius = h * 0.55f,
                        ),
                        topLeft = Offset(-w * 0.1f, -h * 0.05f),
                        size = Size(w, h),
                    )
                }

                KzWall.MACOS -> {
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF2A1E5C), Color(0xFF6A3E9E),
                                Color(0xFFB85C88), Color(0xFFE08A5A),
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(w, h),
                        )
                    )
                    drawOval(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFFFFC48A), Color(0x00000000)),
                            center = Offset(w * 0.62f, h * 0.72f),
                            radius = h * 0.5f,
                        ),
                        topLeft = Offset(w * 0.16f, h * 0.36f),
                        size = Size(w * 0.9f, h * 0.8f),
                    )
                    drawOval(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF4A6BE0), Color(0x00000000)),
                            center = Offset(w * 0.24f, h * 0.26f),
                            radius = h * 0.44f,
                        ),
                        topLeft = Offset(-w * 0.2f, -h * 0.1f),
                        size = Size(w * 0.8f, h * 0.8f),
                    )
                }

                KzWall.NEUTRAL -> {
                    drawRect(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF262C33), Color(0xFF171C22)),
                            start = Offset(0f, 0f),
                            end = Offset(w, h),
                        )
                    )
                    drawOval(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF424A54), Color(0x00000000)),
                            center = Offset(w * 0.4f, h * 0.3f),
                            radius = h * 0.6f,
                        ),
                        topLeft = Offset(-w * 0.2f, -h * 0.2f),
                        size = Size(w * 1.2f, h),
                    )
                }
            }
            if (dim > 0f) {
                drawRect(Color.Black.copy(alpha = dim.coerceIn(0f, 0.9f)))
            }
        }
    }
}

/** Latar gelap polos untuk layar app (bukan wallpaper). */
@Composable
fun KzBackdrop(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color(0xFF07080A)))
}

/**
 * Art preview kartu perangkat.
 *
 * Ini BUKAN wallpaper RDP dan bukan wallpaper OS: gambar geometris milik
 * app sendiri, diturunkan dari nama perangkat (hash) sehingga tiap kartu
 * berbeda tapi tetap satu keluarga. Palet: grafit + baja + satu aksen amber.
 * Tidak ada glow neon dan tidak ada gradien ungu — sesuai aturan visual app.
 */
@Composable
fun DevicePreviewArt(
    seed: String,
    os: KzWall,
    modifier: Modifier = Modifier,
) {
    val h = seed.hashCode()

    Box(
        modifier.fillMaxSize(),
    ) {
        if (os == KzWall.WIN11) {
            // Preview perangkat Windows 11 memakai wallpaper aslinya, lalu
            // ditutup scrim gelap: kartu tetap bagian UI app, bukan pameran foto.
            Image(
                painter = painterResource(R.drawable.kz_win11_wall),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x4D000000), Color(0x40000000), Color(0x99000000)),
                        )
                    ),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF15191E), Color(0xFF0C0F12), Color(0xFF070909)),
                            start = Offset(0f, 0f),
                            end = Offset(720f, 420f),
                        ),
                    ),
            )
        }
        // Overlay dekoratif (cincin, garis diagonal, titik aksen kuning) dihapus:
        // di preview perangkat terlihat seperti noda dan tidak menambah informasi.
        // Sisa: foto/gradien dasar + scrim gelap supaya teks tetap terbaca.
        // Penanda jenis OS: siluet jendela kecil, bukan logo resmi.
        Canvas(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(26.dp),
        ) {
            val stroke = Stroke(width = 1.4f)
            when (os) {
                KzWall.MACOS -> {
                    drawRoundRect(
                        color = Color(0xFFB9C4CC),
                        topLeft = Offset(2f, 2f),
                        size = Size(size.width - 4f, size.height - 6f),
                        cornerRadius = CornerRadius(3f, 3f),
                        style = stroke,
                    )
                    drawLine(
                        color = Color(0xFFB9C4CC),
                        start = Offset(size.width * 0.42f, size.height - 4f),
                        end = Offset(size.width * 0.58f, size.height - 4f),
                        strokeWidth = 1.4f,
                    )
                }

                KzWall.WIN10 -> {
                    drawRect(
                        color = Color(0xFFB9C4CC),
                        topLeft = Offset(2f, 4f),
                        size = Size(size.width - 4f, size.height - 9f),
                        style = stroke,
                    )
                    drawLine(
                        color = Color(0xFFB9C4CC),
                        start = Offset(size.width * 0.35f, 4f),
                        end = Offset(size.width * 0.35f, size.height - 5f),
                        strokeWidth = 1.4f,
                    )
                }

                KzWall.WIN11 -> {
                    drawRoundRect(
                        color = Color(0xFFB9C4CC),
                        topLeft = Offset(2f, 4f),
                        size = Size(size.width - 4f, size.height - 9f),
                        cornerRadius = CornerRadius(2f, 2f),
                        style = stroke,
                    )
                    drawLine(
                        color = Color(0xFFB9C4CC),
                        start = Offset(size.width * 0.66f, 4f),
                        end = Offset(size.width * 0.66f, size.height - 5f),
                        strokeWidth = 1.4f,
                    )
                }

                KzWall.NEUTRAL -> {
                    drawCircle(
                        color = Color(0xFFB9C4CC),
                        radius = size.minDimension * 0.36f,
                        center = Offset(size.width / 2f, size.height / 2f),
                        style = stroke,
                    )
                    drawLine(
                        color = Color(0xFFB9C4CC),
                        start = Offset(size.width / 2f, size.height * 0.14f),
                        end = Offset(size.width / 2f, size.height * 0.86f),
                        strokeWidth = 1.2f,
                    )
                }
            }
        }
    }
}
