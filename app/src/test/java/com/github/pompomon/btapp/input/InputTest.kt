package com.github.pompomon.btapp.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputTest {
    private val mapper = KeyboardInputMapper()

    @Test fun `maps letters function keys and shortcuts to HID usages`() {
        assertEquals(KeyStroke(4), mapper.map("A"))
        assertEquals(KeyStroke(0x3b), mapper.map("F2"))
        assertEquals(KeyStroke(0x13, HidModifier.CTRL), mapper.shortcut("P"))
    }

    @Test fun `unknown keys are not mapped`() {
        assertNull(mapper.map("emoji"))
    }

    @Test fun `tap and cancellation release buttons`() {
        val detector = TouchpadGestureDetector()
        assertEquals(PointerEvent.Button(1, true), detector.tap(1))
        assertEquals(PointerEvent.Button(2, true), detector.tap(2))
        assertNull(detector.tap(3))
        assertEquals(PointerEvent.Button(0, false), detector.cancel())
    }

    @Test fun `one finger drags move and two finger drags scroll`() {
        val detector = TouchpadGestureDetector(2f, 0.5f)
        assertEquals(PointerEvent.Move(6, -4), detector.drag(1, 3f, -2f))
        assertEquals(PointerEvent.Scroll(-1), detector.drag(2, 3f, -2f))
        assertNull(detector.drag(3, 3f, -2f))
    }

    @Test fun `scroll is clamped`() {
        assertEquals(PointerEvent.Scroll(127), TouchpadGestureDetector(1f, 2f).scroll(100f))
    }
}
