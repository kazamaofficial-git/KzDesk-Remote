package com.kazamatech.kzdesk.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KzPadLayoutTest {
    private val w = 2400
    private val h = 1080
    private val cw = 300f
    private val ch = 200f

    @Test
    fun draggingNeverPushesAClusterOffScreen() {
        val start = KzPadPos(0.5f, 0.5f)
        val farRight = KzPadLayout.drag(start, 99_999f, 0f, w, h, cw, ch)
        val (right, top) = KzPadLayout.topLeftPx(farRight, w, h, cw, ch)
        assertEquals(w - cw, right, 0.5f)
        assertTrue(top >= 0f && top <= h - ch)

        val farUpLeft = KzPadLayout.drag(start, -99_999f, -99_999f, w, h, cw, ch)
        val (lx, ly) = KzPadLayout.topLeftPx(farUpLeft, w, h, cw, ch)
        assertEquals(0f, lx, 0.5f)
        assertEquals(0f, ly, 0.5f)
    }

    @Test
    fun dragMovesByExactlyTheDeltaInTheMiddleOfTheScreen() {
        val start = KzPadLayout.topLeftPx(KzPadPos(0.5f, 0.5f), w, h, cw, ch)
        val moved = KzPadLayout.drag(KzPadPos(0.5f, 0.5f), 40f, -25f, w, h, cw, ch)
        val after = KzPadLayout.topLeftPx(moved, w, h, cw, ch)
        assertEquals(start.first + 40f, after.first, 1f)
        assertEquals(start.second - 25f, after.second, 1f)
    }

    @Test
    fun fractionRoundTripIsStable() {
        val original = KzPadPos(0.31f, 0.77f)
        val px = KzPadLayout.topLeftPx(original, w, h, cw, ch)
        val back = KzPadLayout.fromTopLeftPx(px.first, px.second, w, h, cw, ch)
        assertEquals(original.x, back.x, 1e-3f)
        assertEquals(original.y, back.y, 1e-3f)
    }

    @Test
    fun defaultLayoutKeepsEveryClusterFullyOnScreen() {
        KzPadLayout.defaults().forEach { (cluster, pos) ->
            val (x, y) = KzPadLayout.topLeftPx(pos, w, h, cw, ch)
            assertTrue("$cluster x", x >= 0f && x <= w - cw)
            assertTrue("$cluster y", y >= 0f && y <= h - ch)
        }
    }

    @Test
    fun defaultTopBarDoesNotCollideWithTheCenteredSpotifyPlayer() {
        // Pemutar Spotify mengambang di tengah-atas: kira-kira 240dp lebar,
        // dipusatkan, mulai ~10dp dari atas.
        val bar = KzPadLayout.defaults().getValue(KzPadCluster.TOP_BAR)
        val (bx, by) = KzPadLayout.topLeftPx(bar, w, h, 620f, 110f)
        val spotifyX = w / 2f - 360f
        val overlaps = KzPadLayout.overlaps(bx, by, 620f, 110f, spotifyX, 30f, 720f, 110f)
        assertFalse("TOP_BAR menutupi pemutar Spotify", overlaps)
    }

    @Test
    fun overlapDetectionIsSymmetricAndIgnoresTouchingEdges() {
        assertTrue(KzPadLayout.overlaps(0f, 0f, 10f, 10f, 5f, 5f, 10f, 10f))
        assertTrue(KzPadLayout.overlaps(5f, 5f, 10f, 10f, 0f, 0f, 10f, 10f))
        // Hanya bersentuhan di tepi tidak dihitung sebagai tumpang tindih.
        assertFalse(KzPadLayout.overlaps(0f, 0f, 10f, 10f, 10f, 0f, 10f, 10f))
        assertFalse(KzPadLayout.overlaps(0f, 0f, 10f, 10f, 50f, 50f, 10f, 10f))
    }

    @Test
    fun layoutCodecSurvivesRoundTripAndRejectsGarbage() {
        val layout = KzPadLayout.defaults()
        val encoded = KzPadLayout.encode(layout)
        val decoded = KzPadLayout.decode(encoded)
        assertNotNull(decoded)
        assertEquals(layout.keys, decoded!!.keys)
        layout.forEach { (k, v) ->
            assertEquals(v.x, decoded.getValue(k).x, 1e-3f)
            assertEquals(v.y, decoded.getValue(k).y, 1e-3f)
        }

        assertNull(KzPadLayout.decode(null))
        assertNull(KzPadLayout.decode(""))
        assertNull(KzPadLayout.decode("BUKAN_KLASTER:0.1,0.2"))
        // Entri rusak dilewati, bukan membuat seluruh tata letak gagal.
        val partial = KzPadLayout.decode("DPAD:0.1,0.2|RUSAK|ABXY:0.3")
        assertEquals(setOf(KzPadCluster.DPAD), partial?.keys)
    }

    @Test
    fun resolveFillsMissingClustersFromDefaultsAndIgnoresUnknown() {
        val stored = mapOf(KzPadCluster.DPAD to KzPadPos(0.5f, 0.5f))
        val resolved = KzPadLayout.resolve(stored)
        assertEquals(KzPadPos(0.5f, 0.5f), resolved.getValue(KzPadCluster.DPAD))
        assertEquals(
            KzPadLayout.defaults().getValue(KzPadCluster.LEFT_STICK),
            resolved.getValue(KzPadCluster.LEFT_STICK),
        )
        assertEquals(KzPadLayout.defaults(), KzPadLayout.resolve(null))
    }

    @Test
    fun scaleIsClampedToAUsableRange() {
        assertEquals(KzPadLayout.MIN_SCALE, KzPadLayout.clampScale(0.1f), 0f)
        assertEquals(KzPadLayout.MAX_SCALE, KzPadLayout.clampScale(9f), 0f)
        assertEquals(1f, KzPadLayout.clampScale(1f), 0f)
    }
}
