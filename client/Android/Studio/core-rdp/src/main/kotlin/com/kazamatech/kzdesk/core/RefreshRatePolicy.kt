package com.kazamatech.kzdesk.core

/**
 * Picks a handset display mode refresh request for an RDP session.
 *
 * This is a local display preference only: the remote server, negotiated RDP
 * codec, network and device compositor still determine the actual stream FPS.
 */
object RefreshRatePolicy {
    fun choose(requestedFps: Int?, supportedRefreshRates: Iterable<Float>): Float? {
        val rates = supportedRefreshRates
            .filter { it.isFinite() && it in 24f..360f }
            .distinct()
            .sorted()
        if (rates.isEmpty()) return null
        if (requestedFps == null || requestedFps <= 0) return rates.last()
        return rates.firstOrNull { it >= requestedFps.toFloat() } ?: rates.last()
    }
}
