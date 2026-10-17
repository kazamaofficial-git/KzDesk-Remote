package com.kazamatech.kzdesk.ui

/** RDP wheel units queued from one vertical touch delta and the carried remainder. */
internal data class ScrollWheelStep(val units: Int, val remainder: Float)

internal class ScrollWheelAccumulator {
    private var remainder = 0f

    fun consume(deltaY: Float, speed: Float): Int {
        val step = scrollWheelStep(deltaY, remainder, speed)
        remainder = step.remainder
        return step.units
    }

    fun reset() {
        remainder = 0f
    }
}

/**
 * Convert touch pixels to high-resolution RDP wheel units. One notch remains
 * 120 units at the default speed.
 *
 * Batch diturunkan dari 24 ke **12 unit** (1/10 takik, dari 1/5) supaya scroll
 * terasa halus, bukan melompat per seperlima takik. Windows mengakumulasi delta
 * roda di bawah 120, jadi batch kecil tetap menghasilkan satu takik penuh setelah
 * lima langkah; yang berubah hanya seberapa sering delta dikirim.
 *
 * Positive finger movement is down, so it produces negative wheel units.
 */
internal fun scrollWheelStep(
    deltaY: Float,
    remainder: Float,
    speed: Float,
): ScrollWheelStep {
    val safeRemainder = if (remainder.isFinite()) remainder.coerceIn(-11.99f, 11.99f) else 0f
    val safeDelta = if (deltaY.isFinite()) deltaY.coerceIn(-240f, 240f) else 0f
    val safeSpeed = if (speed.isFinite()) speed.coerceIn(0.4f, 2.5f) else 1f
    val accumulated = safeRemainder - safeDelta * 3f * safeSpeed
    val units = (accumulated / 12f).toInt() * 12
    return ScrollWheelStep(units, accumulated - units)
}

/** Keep a button's center fixed when its diameter changes. Coordinates are top-left normalized. */
internal fun resizeHudKeyPreservingCenter(
    key: HudKey,
    newSize: Float,
    viewportWidthDp: Float,
    viewportHeightDp: Float,
): HudKey {
    val oldSize = if (key.size.isFinite()) key.size.coerceIn(56f, 120f) else 64f
    val size = if (newSize.isFinite()) newSize.coerceIn(56f, 120f) else oldSize
    fun adjust(position: Float, viewport: Float): Float {
        val safePosition = if (position.isFinite()) position.coerceIn(0f, 1f) else 0f
        if (!viewport.isFinite() || viewport <= 0f) return safePosition
        val oldMax = (viewport - oldSize).coerceAtLeast(1f)
        val newMax = (viewport - size).coerceAtLeast(1f)
        val center = safePosition * oldMax + oldSize / 2f
        return ((center - size / 2f) / newMax).coerceIn(0f, 1f)
    }
    return key.copy(
        size = size,
        x = adjust(key.x, viewportWidthDp),
        y = adjust(key.y, viewportHeightDp),
    )
}

/**
 * Only layout editing (`mappingMode`) and the swipe-scroll button (`SCROLL_SLIDER`)
 * convert finger motion into a drag/scroll gesture after crossing touch slop.
 * Normal HUD buttons must not cancel taps on minor finger wobble.
 */
internal fun shouldStartHudButtonGesture(
    travelledPx: Float,
    touchSlopPx: Float,
    mappingMode: Boolean,
    kind: HudKind,
): Boolean {
    // Di luar mode atur posisi, hanya kontrol geser kontinu yang boleh memulai
    // gestur tarik: slider scroll dan joystick analog.
    if (!mappingMode && kind != HudKind.SCROLL_SLIDER && kind != HudKind.PAD_STICK) return false
    if (!travelledPx.isFinite() || travelledPx <= 0f) return false
    val threshold = if (touchSlopPx.isFinite() && touchSlopPx > 0f) touchSlopPx else 16f
    return travelledPx > threshold
}

/**
 * Hitung delta gerakan kursor trackpad dengan sensitivitas & akselerasi kecepatan.
 * Saat [acceleration] menyala, gerakan jari pelan tetap presisi piksel sementara
 * sapuan cepat mendapat pengali hingga ~1.88x.
 */
internal fun applyTrackpadDelta(
    dx: Float,
    dy: Float,
    sensitivity: Float,
    acceleration: Boolean,
): Pair<Float, Float> {
    val safeDx = if (dx.isFinite()) dx.coerceIn(-400f, 400f) else 0f
    val safeDy = if (dy.isFinite()) dy.coerceIn(-400f, 400f) else 0f
    val sens = if (sensitivity.isFinite()) sensitivity.coerceIn(0.4f, 2.5f) else 1f
    val accelFactor = if (acceleration) {
        val speed = kotlin.math.hypot(safeDx, safeDy)
        1f + (speed / 18f).coerceIn(0f, 1.6f) * 0.55f
    } else {
        1f
    }
    val scale = sens * accelFactor
    return (safeDx * scale) to (safeDy * scale)
}

/**
 * Haluskan delta gerakan kursor antar-frame dengan exponential moving average.
 * Sapuan jari mengirim banyak delta kecil yang kasar; tanpa perataan kursor
 * terasa "muter-muter" di dalam game. [alpha] tinggi = responsif (0 = kaku,
 * 1 = tanpa perataan). Delta pertama (prevDx/prevDy NaN) dilewatkan apa
 * adanya supaya tidak ada jeda di awal gestur.
 */
internal fun smoothPointerDelta(
    prevDx: Float,
    prevDy: Float,
    dx: Float,
    dy: Float,
    alpha: Float,
): Pair<Float, Float> {
    if (!dx.isFinite() || !dy.isFinite()) return 0f to 0f
    if (!prevDx.isFinite() || !prevDy.isFinite()) return dx to dy
    val a = if (alpha.isFinite()) alpha.coerceIn(0.15f, 1f) else 0.45f
    return (prevDx + (dx - prevDx) * a) to (prevDy + (dy - prevDy) * a)
}

/**
 * Deteksi apakah titik sentuh awal berada di jalur scroll tepi kanan layar
 * (Edge-Scroll ala touchpad laptop).
 */
internal fun isInRightEdgeScrollZone(
    touchX: Float,
    viewportWidthPx: Float,
    zoneWidthPx: Float,
    enabled: Boolean,
): Boolean {
    if (!enabled) return false
    if (!touchX.isFinite() || !viewportWidthPx.isFinite() || viewportWidthPx <= 0f) return false
    val safeZone = if (zoneWidthPx.isFinite()) zoneWidthPx.coerceIn(12f, 120f) else 32f
    return touchX >= (viewportWidthPx - safeZone)
}

/** Langkah peluruhan kecepatan kinetic/inertial scroll per frame. */
internal fun inertialScrollDecayStep(
    velocityY: Float,
    friction: Float = 0.88f,
): Float {
    if (!velocityY.isFinite()) return 0f
    val safeFriction = if (friction.isFinite()) friction.coerceIn(0.5f, 0.96f) else 0.88f
    val next = velocityY * safeFriction
    return if (kotlin.math.abs(next) < 0.8f) 0f else next
}

/**
 * Jepit simpangan joystick ke dalam lingkaran berjari-jari [radiusPx] supaya
 * knob tidak pernah keluar dari alasnya. Mengembalikan [x, y] dalam piksel.
 */
internal fun clampStickOffset(dx: Float, dy: Float, radiusPx: Float): FloatArray {
    val radius = if (radiusPx.isFinite() && radiusPx > 0f) radiusPx else 1f
    val safeX = if (dx.isFinite()) dx else 0f
    val safeY = if (dy.isFinite()) dy else 0f
    val magnitude = kotlin.math.sqrt(safeX * safeX + safeY * safeY)
    if (magnitude <= radius || magnitude == 0f) return floatArrayOf(safeX, safeY)
    val scale = radius / magnitude
    return floatArrayOf(safeX * scale, safeY * scale)
}

/**
 * Normalisasi simpangan joystick ke -1..1 per sumbu. Arah dipertahankan
 * (tidak menempel ke sumbu) sehingga gerak diagonal tetap diagonal.
 */
internal fun stickAxis(dx: Float, dy: Float, radiusPx: Float): FloatArray {
    val radius = if (radiusPx.isFinite() && radiusPx > 0f) radiusPx else 1f
    val clamped = clampStickOffset(dx, dy, radiusPx)
    return floatArrayOf(
        (clamped[0] / radius).coerceIn(-1f, 1f),
        (clamped[1] / radius).coerceIn(-1f, 1f),
    )
}
