package com.kazamatech.kzdesk.ui

import com.kazamatech.kzdesk.ui.input.KzStickMode
import kotlin.math.hypot

/**
 * Mode satu joystick HUD. Tiap joystick boleh punya mode sendiri; `null` pada
 * [HudKey.stickMode] berarti mengikuti pengaturan global (perilaku bawaan).
 */
enum class HudStickMode(val title: String, val titleEn: String) {
    WASD("WASD", "WASD"),
    ARROWS("Panah", "Arrows"),
    POINTER("Kursor / Kamera", "Cursor / Camera"),
    ;

    fun toKzMode(): KzStickMode = when (this) {
        WASD -> KzStickMode.WASD
        ARROWS -> KzStickMode.ARROWS
        POINTER -> KzStickMode.POINTER
    }
}

/** Matematika joystick tanpa dependensi Android supaya bisa diuji di JVM. */
object HudStickMath {
    const val MIN_SENS = 0.3f
    const val MAX_SENS = 3f
    const val DEFAULT_SENS = 1f
    const val MIN_DEAD = 0.05f
    const val MAX_DEAD = 0.5f
    const val DEFAULT_DEAD = 0.15f

    /**
     * Deadzone radial + sensitivitas untuk mode gerak kursor/kamera. Di dalam
     * deadzone hasilnya (0,0); di luar, besarnya diskalakan ulang agar gerak mulus
     * dimulai dari nol (tanpa lompatan), lalu dikali [sens].
     */
    fun shapeAnalog(x: Float, y: Float, dead: Float, sens: Float): Pair<Float, Float> {
        val mag = hypot(x, y)
        val d = dead.coerceIn(MIN_DEAD, MAX_DEAD)
        if (mag <= d || mag == 0f) return 0f to 0f
        val scaled = ((mag - d) / (1f - d)).coerceIn(0f, 1f) * sens.coerceIn(MIN_SENS, MAX_SENS)
        val k = scaled / mag
        return x * k to y * k
    }

    /** Mode tombol (WASD/Panah): hanya deadzone; arah dipertahankan apa adanya. */
    fun shapeDigital(x: Float, y: Float, dead: Float): Pair<Float, Float> =
        if (hypot(x, y) <= dead.coerceIn(MIN_DEAD, MAX_DEAD)) 0f to 0f else x to y
}
