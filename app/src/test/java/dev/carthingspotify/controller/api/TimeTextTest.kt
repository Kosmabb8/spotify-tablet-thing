package dev.carthingspotify.controller.api

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeTextTest {
    @Test fun `elapsed formats minutes and zero-padded seconds`() {
        assertEquals("0:00", TimeText.elapsed(0))
        assertEquals("3:07", TimeText.elapsed(187_000))
    }

    @Test fun `remaining never becomes negative`() {
        assertEquals("−0:00", TimeText.remaining(5_000, 4_000))
        assertEquals("−1:01", TimeText.remaining(1_000, 62_000))
    }
}
