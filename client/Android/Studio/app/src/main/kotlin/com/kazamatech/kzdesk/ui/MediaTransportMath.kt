package com.kazamatech.kzdesk.ui

/**
 * Matematika transport pemutar musik.
 *
 * Dipisah dari Compose dan dari `MediaController` supaya bisa diuji di JVM:
 * angka-angka inilah yang menentukan apakah scrub bar melompat, apakah tombol
 * maju 10 detik menembus ujung lagu, dan apakah label waktu terbaca benar.
 * Tidak ada API Android di sini.
 */
internal object MediaTransportMath {
    /** Nilai `PlaybackState.REPEAT_MODE_*` disalin sebagai konstanta lokal. */
    const val REPEAT_OFF = 0
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2

    /** Langkah tombol maju/mundur cepat. */
    const val SKIP_STEP_MS = 10_000L

    /**
     * `1:05`, `12:00`, atau `1:02:03` kalau lewat satu jam.
     * Nilai negatif atau `Long.MIN_VALUE` (durasi tak diketahui) jadi `0:00`.
     */
    fun formatClock(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSeconds = ms / 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /**
     * Posisi perkiraan saat ini. `PlaybackState.position` adalah posisi saat
     * pemutar terakhir melapor, jadi selama lagu jalan posisinya harus
     * diekstrapolasi — kalau tidak, scrub bar diam sampai metadata berubah.
     */
    fun extrapolate(
        positionMs: Long,
        elapsedSinceUpdateMs: Long,
        speed: Float,
        playing: Boolean,
        durationMs: Long,
    ): Long {
        if (!playing) return positionMs.coerceAtLeast(0L)
        if (elapsedSinceUpdateMs <= 0L) return positionMs.coerceAtLeast(0L)
        val rate = if (speed.isFinite() && speed > 0f) speed else 1f
        val advanced = positionMs + (elapsedSinceUpdateMs.toDouble() * rate).toLong()
        return if (durationMs > 0L) advanced.coerceIn(0L, durationMs) else advanced.coerceAtLeast(0L)
    }

    /** Pecahan 0..1 untuk lebar isian scrub bar. Durasi 0/tak dikenal -> 0. */
    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        if (positionMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** Kebalikan [progressFraction]: sentuhan pada pecahan tertentu -> ms. */
    fun positionAtFraction(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
        return (durationMs.toDouble() * f).toLong().coerceIn(0L, durationMs)
    }

    /** Hasil tombol maju/mundur: selalu di dalam lagu, tidak pernah negatif. */
    fun seekBy(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
        val target = positionMs + deltaMs
        val upper = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return target.coerceIn(0L, upper)
    }

    /**
     * Urutan tombol ulang: mati -> satu lagu -> semua -> mati.
     * Nilai di luar tiga mode itu dianggap mati supaya tombol tidak macet.
     */
    fun nextRepeatMode(current: Int): Int = when (current) {
        REPEAT_ONE -> REPEAT_ALL
        REPEAT_ALL -> REPEAT_OFF
        else -> REPEAT_ONE
    }

    /** Pecahan kecepatan yang aman dipakai untuk ekstrapolasi. */
    fun safeSpeed(speed: Float): Float =
        if (speed.isFinite() && speed > 0f) speed.coerceIn(0.25f, 4f) else 1f
}
