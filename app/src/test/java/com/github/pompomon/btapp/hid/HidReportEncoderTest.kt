package com.github.pompomon.btapp.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HidReportEncoderTest {
    @Test fun `keyboard report contains modifier and release-compatible usage`() {
        assertArrayEquals(
            byteArrayOf(1, 2, 0, 4, 0, 0, 0, 0, 0),
            HidReportEncoder.keyboard(2, listOf(4))
        )
    }

    @Test fun `large movement is split into valid relative reports`() {
        assertEquals(listOf(127 to -127, 127 to -127, 46 to -46), HidReportEncoder.splitMovement(300, -300))
    }

    @Test fun `wheel delta is emitted once when movement is split`() {
        assertEquals(listOf(1, 0, 0), HidReportEncoder.mouseSequence(0, 300, 0, 1).map { it[4].toInt() })
    }

    @Test fun `mouse report rejects values outside HID range`() {
        try {
            HidReportEncoder.mouse(0, 128, 0)
            throw AssertionError("Expected invalid delta to fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `consumer report encodes usages in little endian and supports release`() {
        assertArrayEquals(
            byteArrayOf(3, 0xE9.toByte(), 0),
            HidReportEncoder.consumer(ConsumerUsage.VOLUME_UP)
        )
        assertArrayEquals(byteArrayOf(3, 0, 0), HidReportEncoder.consumer(0))
    }

    @Test fun `consumer report rejects usages outside descriptor range`() {
        try {
            HidReportEncoder.consumer(0x0400)
            throw AssertionError("Expected invalid consumer usage to fail")
        } catch (_: IllegalArgumentException) {
        }
    }
}
