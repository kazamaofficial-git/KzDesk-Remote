package com.kazamatech.kzdesk.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RttSmootherTest {
    @Test
    fun `sampel pertama dipakai apa adanya`() {
        assertEquals(42, RttSmoother.next(-1, 42))
    }

    @Test
    fun `sampel nol atau negatif dibulatkan ke satu`() {
        assertEquals(1, RttSmoother.next(-1, 0))
        assertEquals(1, RttSmoother.next(-1, -5))
    }

    @Test
    fun `sampel negatif ikut EMA sebagai satu`() {
        // 20*0.65 + 1*0.35 = 13.35 -> 13
        assertEquals(13, RttSmoother.next(20, -5))
    }

    @Test
    fun `ema mengikuti sampel baru`() {
        // prev 100, sample 40 -> 100*0.65 + 40*0.35 = 79
        assertEquals(79, RttSmoother.next(100, 40))
    }

    @Test
    fun `deret konvergen ke sampel tetap`() {
        var v = -1
        repeat(60) { v = RttSmoother.next(v, 60) }
        assertEquals(60, v)
    }
}
