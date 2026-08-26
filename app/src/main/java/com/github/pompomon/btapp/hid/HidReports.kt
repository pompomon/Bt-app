package com.github.pompomon.btapp.hid

import kotlin.math.abs
import kotlin.math.sign

object HidDescriptor {
    // Report IDs: 1 keyboard (modifier + six keys), 2 relative mouse (buttons, x, y, wheel).
    val bytes = byteArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01, 0x05, 0x07,
        0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
        0x95.toByte(), 0x08, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x08,
        0x81.toByte(), 0x01, 0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
        0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte(),
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02, 0x09, 0x01,
        0xA1.toByte(), 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00,
        0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01,
        0x75, 0x05, 0x81.toByte(), 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
        0x09, 0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03,
        0x81.toByte(), 0x06, 0xC0.toByte(), 0xC0.toByte()
    )
}

object HidReportEncoder {
    const val KEYBOARD_REPORT_ID = 1
    const val MOUSE_REPORT_ID = 2
    const val MAX_RELATIVE_DELTA = 127

    fun keyboard(modifiers: Int, usages: Collection<Int>): ByteArray {
        require(modifiers in 0..0xff)
        require(usages.size <= 6 && usages.all { it in 0..0x65 })
        return byteArrayOf(KEYBOARD_REPORT_ID.toByte(), modifiers.toByte(), 0, *usages.map(Int::toByte).toByteArray(), *ByteArray(6 - usages.size))
    }

    fun mouse(buttons: Int, x: Int, y: Int, wheel: Int = 0): ByteArray {
        require(buttons in 0..7)
        require(x in -127..127 && y in -127..127 && wheel in -127..127)
        return byteArrayOf(MOUSE_REPORT_ID.toByte(), buttons.toByte(), x.toByte(), y.toByte(), wheel.toByte())
    }

    fun splitMovement(x: Int, y: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var remainingX = x
        var remainingY = y
        while (remainingX != 0 || remainingY != 0) {
            val nextX = remainingX.coerceIn(-MAX_RELATIVE_DELTA, MAX_RELATIVE_DELTA)
            val nextY = remainingY.coerceIn(-MAX_RELATIVE_DELTA, MAX_RELATIVE_DELTA)
            result += nextX to nextY
            remainingX -= nextX
            remainingY -= nextY
        }
        return result
    }
}
