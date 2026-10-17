package com.kazamatech.kzdesk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kontrol penuh pemutar berarti angka waktu dan posisi yang bisa dipercaya.
 * Test ini mengunci perilaku yang paling gampang rusak: label jam, ekstrapolasi
 * posisi selama lagu berjalan, dan tombol maju/mundur yang tidak boleh
 * menembus ujung lagu.
 */
class MediaTransportMathTest {

    @Test
    fun clockLabelsCoverSecondsMinutesAndHours() {
        assertEquals("0:00", MediaTransportMath.formatClock(0L))
        assertEquals("0:00", MediaTransportMath.formatClock(-5_000L))
        assertEquals("0:00", MediaTransportMath.formatClock(999L))
        assertEquals("0:01", MediaTransportMath.formatClock(1_000L))
        assertEquals("1:05", MediaTransportMath.formatClock(65_000L))
        assertEquals("12:00", MediaTransportMath.formatClock(720_000L))
        assertEquals("1:02:03", MediaTransportMath.formatClock(3_723_000L))
    }

    @Test
    fun positionStandsStillWhenNotPlaying() {
        assertEquals(
            42_000L,
            MediaTransportMath.extrapolate(
                positionMs = 42_000L,
                elapsedSinceUpdateMs = 30_000L,
                speed = 1f,
                playing = false,
                durationMs = 0L,
            ),
        )
    }

    @Test
    fun positionAdvancesWithElapsedTimeAndSpeed() {
        assertEquals(
            3_000L,
            MediaTransportMath.extrapolate(1_000L, 2_000L, 1f, true, 0L),
        )
        assertEquals(
            5_000L,
            MediaTransportMath.extrapolate(1_000L, 2_000L, 2f, true, 0L),
        )
    }

    @Test
    fun extrapolatedPositionNeverPassesTheEndOfTheTrack() {
        assertEquals(
            10_000L,
            MediaTransportMath.extrapolate(9_000L, 5_000L, 1f, true, 10_000L),
        )
        assertEquals(
            0L,
            MediaTransportMath.extrapolate(-100L, 0L, 1f, false, 10_000L),
        )
    }

    @Test
    fun unknownSpeedFallsBackToOne() {
        assertEquals(
            3_000L,
            MediaTransportMath.extrapolate(1_000L, 2_000L, 0f, true, 0L),
        )
        assertEquals(
            3_000L,
            MediaTransportMath.extrapolate(1_000L, 2_000L, Float.NaN, true, 0L),
        )
    }

    @Test
    fun scrubFractionAndPositionAreInverseOperations() {
        assertEquals(0.5f, MediaTransportMath.progressFraction(5_000L, 10_000L), 0.0001f)
        assertEquals(0f, MediaTransportMath.progressFraction(0L, 10_000L), 0.0001f)
        assertEquals(1f, MediaTransportMath.progressFraction(15_000L, 10_000L), 0.0001f)
        // Durasi belum diketahui dari pemutar: bar kosong, bukan crash.
        assertEquals(0f, MediaTransportMath.progressFraction(5_000L, 0L), 0.0001f)

        assertEquals(5_000L, MediaTransportMath.positionAtFraction(0.5f, 10_000L))
        assertEquals(0L, MediaTransportMath.positionAtFraction(0f, 10_000L))
        assertEquals(10_000L, MediaTransportMath.positionAtFraction(1.5f, 10_000L))
        assertEquals(0L, MediaTransportMath.positionAtFraction(0.5f, 0L))
    }

    @Test
    fun skipButtonsStayInsideTheTrack() {
        assertEquals(15_000L, MediaTransportMath.seekBy(5_000L, 60_000L, 10_000L))
        assertEquals(0L, MediaTransportMath.seekBy(5_000L, 60_000L, -10_000L))
        assertEquals(60_000L, MediaTransportMath.seekBy(55_000L, 60_000L, 10_000L))
        // Tanpa durasi, maju tetap boleh — banyak pemutar tidak melapor durasi.
        assertEquals(15_000L, MediaTransportMath.seekBy(5_000L, 0L, 10_000L))
    }

    @Test
    fun repeatButtonCyclesOffOneAllOff() {
        assertEquals(
            MediaTransportMath.REPEAT_ONE,
            MediaTransportMath.nextRepeatMode(MediaTransportMath.REPEAT_OFF),
        )
        assertEquals(
            MediaTransportMath.REPEAT_ALL,
            MediaTransportMath.nextRepeatMode(MediaTransportMath.REPEAT_ONE),
        )
        assertEquals(
            MediaTransportMath.REPEAT_OFF,
            MediaTransportMath.nextRepeatMode(MediaTransportMath.REPEAT_ALL),
        )
        // Mode tak dikenal (mis. -1 dari pemutar aneh) tidak boleh mematikan tombol.
        assertEquals(
            MediaTransportMath.REPEAT_ONE,
            MediaTransportMath.nextRepeatMode(-1),
        )
    }

    @Test
    fun playbackSpeedIsClampedToASaneRange() {
        assertEquals(1f, MediaTransportMath.safeSpeed(0f), 0.0001f)
        assertEquals(1f, MediaTransportMath.safeSpeed(-2f), 0.0001f)
        assertEquals(1f, MediaTransportMath.safeSpeed(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(0.25f, MediaTransportMath.safeSpeed(0.05f), 0.0001f)
        assertEquals(4f, MediaTransportMath.safeSpeed(16f), 0.0001f)
        assertEquals(1.5f, MediaTransportMath.safeSpeed(1.5f), 0.0001f)
    }
}
