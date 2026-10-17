package com.kazamatech.kzdesk.core

import kotlin.math.roundToInt

/**
 * Penghalus sampel RTT untuk pill telemetri.
 *
 * Murni dan deterministik supaya bisa diuji unit: [next] menggabungkan sampel
 * baru ke nilai sebelumnya dengan EMA (alpha [ALPHA]) sehingga angka di layar
 * tidak melompat-lompat, tetapi tetap mengikuti perubahan jaringan dalam
 * beberapa sampel.
 */
object RttSmoother {
    const val ALPHA = 0.35f

    /** `prev` -1 berarti belum ada sampel; sampel pertama dipakai apa adanya. */
    fun next(prev: Int, sampleMs: Int): Int {
        val s = sampleMs.coerceAtLeast(1)
        if (prev < 0) return s
        val mixed = prev * (1f - ALPHA) + s * ALPHA
        return mixed.roundToInt().coerceAtLeast(1)
    }
}
