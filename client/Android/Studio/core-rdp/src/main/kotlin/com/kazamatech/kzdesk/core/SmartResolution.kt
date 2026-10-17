package com.kazamatech.kzdesk.core

/**
 * Remote desktop sizing policy. Automatic RDP uses the conservative 16:9
 * standard 720p preset; explicit presets remain available for larger displays.
 * PC Connect preserves the host console size instead of forcing a virtual one.
 */
object SmartResolution {

    const val AUTOMATIC_PRESET = "automatic"
    const val FOLLOW_PRESET = "follow"
    const val LEGACY_AUTOMATIC_PRESET = "smart169"

    /** Resolution presets offered to users, from smallest to largest. */
    val CANDIDATES: List<Pair<Int, Int>> = listOf(
        1280 to 720,
        1600 to 900,
        1920 to 1080,
        2560 to 1440,
        3840 to 2160,
    )

    /** Automatic sizing intentionally stops at standard 720p. */
    const val AUTO_MAX_W = 1280
    const val AUTO_MAX_H = 720

    /** Bounds shared with custom-resolution validation. */
    const val MIN_W = 640
    const val MIN_H = 480
    const val MAX_W = 8192
    const val MAX_H = 8192

    /**
     * Select a standard 16:9 size, capped at 1280x720. When no candidate fits,
     * keep the smallest standard desktop size rather than inventing a ratio.
     */
    fun forViewport(viewportW: Int, viewportH: Int): String {
        val vw = viewportW.coerceAtLeast(1)
        val vh = viewportH.coerceAtLeast(1)
        val standard = CANDIDATES.filter { it.first <= AUTO_MAX_W && it.second <= AUTO_MAX_H }
        val picked = standard.lastOrNull { it.first <= vw && it.second <= vh } ?: standard.first()
        return "${picked.first}x${picked.second}"
    }

    /**
     * Resolve a saved selection to an optional fixed RDP size. `null` means
     * follow the phone viewport or preserve the physical PC console resolution.
     */
    fun requestedForSession(
        storedPreset: String?,
        isPcConnectMode: Boolean,
        viewportW: Int,
        viewportH: Int,
    ): String? {
        if (isPcConnectMode || storedPreset == FOLLOW_PRESET) return null
        val candidate = when (storedPreset) {
            null, AUTOMATIC_PRESET, LEGACY_AUTOMATIC_PRESET -> forViewport(viewportW, viewportH)
            else -> storedPreset
        }
        return candidate.takeIf { parse(it) != null } ?: forViewport(viewportW, viewportH)
    }

    /**
     * Fit scale used to draw a remote desktop on the phone. ClearType's
     * subpixel edges can look colored or jagged when resampled, so the caller
     * disables it unless the image is effectively pixel-perfect.
     */
    fun fitFactor(viewportW: Int, viewportH: Int, resW: Int, resH: Int): Float {
        if (viewportW <= 0 || viewportH <= 0 || resW <= 0 || resH <= 0) return 1f
        return minOf(viewportW.toFloat() / resW, viewportH.toFloat() / resH)
    }

    /** True when the remote desktop is drawn 1:1 (without resampling). */
    fun isPixelPerfect(viewportW: Int, viewportH: Int, resW: Int, resH: Int): Boolean {
        val f = fitFactor(viewportW, viewportH, resW, resH)
        return kotlin.math.abs(f - 1f) <= 0.02f
    }

    /** "1920x1080" -> (1920, 1080); null when the value is invalid. */
    fun parse(preset: String): Pair<Int, Int>? {
        val parts = preset.split('x')
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        if (w !in MIN_W..MAX_W || w % 2 != 0 || h !in MIN_H..MAX_H) return null
        return w to h
    }

    /** Simple aspect-ratio label (16:9, 9:20, and similar). */
    fun ratioLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        var a = width
        var b = height
        while (b != 0) {
            val t = a % b
            a = b
            b = t
        }
        val gcd = if (a == 0) 1 else a
        val rw = width / gcd
        val rh = height / gcd
        val is169 = kotlin.math.abs(width * 9f / height - 16f) < 0.06f
        return if (is169) "16:9" else "$rw:$rh"
    }
}
