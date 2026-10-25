package io.github.mjohnson7cd.chronicleauto.features.car

import io.github.mjohnson7cd.chronicleauto.features.car.CarPlaybackSpeedActivity.Companion.format
import io.github.mjohnson7cd.chronicleauto.features.car.CarPlaybackSpeedActivity.Companion.snap
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlaybackSpeedActivityTest {
    @Test
    fun snapClampsAndRoundsToStep() {
        assertEquals(0.5f, snap(0.1f))
        assertEquals(2.5f, snap(3.0f))
        assertEquals(1.25f, snap(1.2401f))
        assertEquals(1.05f, snap(1.0f + 0.05f))
        assertEquals(0.95f, snap(1.0f - 0.05f))
    }

    @Test
    fun formatTrimsZeros() {
        assertEquals("1x", format(1.0f))
        assertEquals("1.5x", format(1.5f))
        assertEquals("1.25x", format(1.25f))
    }
}
