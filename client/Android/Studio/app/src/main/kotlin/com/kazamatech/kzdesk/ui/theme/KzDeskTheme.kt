package com.kazamatech.kzdesk.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.kazamatech.kzdesk.R
import com.kazamatech.kzdesk.ui.AppPrefs

// =============================================================
// KzDesk design tokens — gaya SaaS: hitam pekat + aksen ungu.
//
// Latar nyaris hitam dengan semburat ungu, permukaan solid berlapis tipis,
// garis halus, sudut membulat, tanpa efek kaca/blur. Ungu dipakai untuk aksi
// utama dan penanda aktif; status memakai warna semantik (hijau/kuning/merah).
// =============================================================
val KzBg = Color(0xFF07070B)
val KzSurface = Color(0xFF0F0F17)
val KzSurfaceAlt = Color(0xFF171724)
val KzPrimaryContainer = Color(0xFF2B1D5E)
val KzLine = Color(0xFF25253A)
val KzLineStrong = Color(0xFF3A3A58)
val KzText = Color(0xFFF4F4FA)
val KzTextDim = Color(0xFFB4B4CC)
val KzTextFaint = Color(0xFF82829C)
val KzAccent = Color(0xFF8B5CF6)
val KzAccentSoft = Color(0xFFA78BFA)
val KzAccentInk = Color(0xFFFFFFFF)
val KzOk = Color(0xFF34D399)
val KzWarn = Color(0xFFFBBF24)
val KzDanger = Color(0xFFF87171)

val DarkScheme = darkColorScheme(
    primary = KzAccent,
    onPrimary = KzAccentInk,
    primaryContainer = KzPrimaryContainer,
    onPrimaryContainer = Color(0xFFE9E1FF),
    secondary = KzAccentSoft,
    onSecondary = Color(0xFF1B0F42),
    secondaryContainer = KzSurfaceAlt,
    onSecondaryContainer = KzText,
    tertiary = KzOk,
    background = KzBg,
    onBackground = KzText,
    surface = KzSurface,
    onSurface = KzText,
    surfaceVariant = KzSurfaceAlt,
    onSurfaceVariant = KzTextDim,
    outline = KzLine,
    outlineVariant = KzLineStrong,
    error = KzDanger,
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF3A1620),
    onErrorContainer = Color(0xFFFFD6DC),
    scrim = Color(0x66000000),
)

val LightScheme = lightColorScheme(
    primary = Color(0xFF6D28D9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF2E1065),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3EFFF),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = Color(0xFF059669),
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF14121F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14121F),
    surfaceVariant = Color(0xFFF1EFF8),
    onSurfaceVariant = Color(0xFF5B5873),
    outline = Color(0xFFDEDAEE),
    outlineVariant = Color(0xFFCBC6E0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    scrim = Color(0x38000000),
)

// Space Grotesk = display, Inter = body (variable fonts, OFL).
val KzDisplay = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold),
)
val KzBody = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold),
)

/** Radius lebih ramah sentuh; kontrol pill tetap terbaca sebagai kontrol. */
val KzPill = RoundedCornerShape(18.dp)

val KzShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/**
 * Mode tema sebagai state Compose yang di-share lintas activity.
 *
 * Dulu mengganti tema memanggil Activity.recreate() — seluruh layar
 * kedip-kedip dan state UI (posisi scroll, drawer) ikut reset. Sekarang
 * mode disimpan di sini: mengubahnya membuat semua layar yang memakai
 * [kzDark] recompose sendiri, tanpa recreate.
 *
 * [mode] bernilai Int.MIN_VALUE sampai [init] dipanggil di activity
 * pertama (membaca preferensi tersimpan).
 */
object KzThemeState {
    const val FOLLOW_SYSTEM = 0
    const val DARK = 1
    const val LIGHT = 2

    var mode by mutableIntStateOf(Int.MIN_VALUE)
        private set

    /** Baca preferensi sekali per proses; panggil di setContent activity. */
    fun init(prefs: AppPrefs) {
        if (mode == Int.MIN_VALUE) mode = prefs.themeMode
    }

    /** Set dari UI pengaturan: tulis prefs + state sekaligus. */
    fun set(prefs: AppPrefs, value: Int) {
        mode = value
        prefs.themeMode = value
    }
}

/**
 * Gelap atau tidaknya app SAAT INI, sebagai state: ikut pilihan user,
 * fallback ke tema sistem.
 */
@Composable
fun kzDark(): Boolean = when (KzThemeState.mode) {
    KzThemeState.DARK -> true
    KzThemeState.LIGHT -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun KzDeskTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    val scheme = if (dark) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.decorView.setBackgroundColor(scheme.background.toArgb())
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !dark
            insets.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = KzShapes,
        typography = MaterialTheme.typography.copy(
            displaySmall = TextStyle(
                fontFamily = KzDisplay, fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp,
            ),
            headlineMedium = TextStyle(
                fontFamily = KzDisplay, fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp,
            ),
            headlineSmall = TextStyle(
                fontFamily = KzDisplay, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
            ),
            titleLarge = TextStyle(
                fontFamily = KzDisplay, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            ),
            titleMedium = TextStyle(
                fontFamily = KzDisplay, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            ),
            titleSmall = TextStyle(
                fontFamily = KzBody, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            ),
            bodyLarge = TextStyle(fontFamily = KzBody, fontSize = 15.5.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontFamily = KzBody, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontFamily = KzBody, fontSize = 12.5.sp, lineHeight = 17.sp),
            labelLarge = TextStyle(
                fontFamily = KzDisplay, fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp,
            ),
            labelMedium = TextStyle(
                fontFamily = KzBody, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp,
            ),
            labelSmall = TextStyle(
                fontFamily = KzBody, fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp,
            ),
        ),
        content = {
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                content()
            }
        },
    )
}
