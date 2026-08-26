package com.github.pompomon.btapp.input

data class KeyStroke(val usage: Int, val modifiers: Int = 0)

object HidModifier {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val META = 0x08
}

class KeyboardInputMapper {
    fun map(key: String): KeyStroke? = when (key.uppercase()) {
        in "A".."Z" -> KeyStroke(0x04 + (key.uppercase()[0].code - 'A'.code))
        in "1".."9" -> KeyStroke(0x1e + (key[0].code - '1'.code))
        "0" -> KeyStroke(0x27)
        "SPACE" -> KeyStroke(0x2c)
        "ENTER" -> KeyStroke(0x28)
        "BACKSPACE" -> KeyStroke(0x2a)
        "TAB" -> KeyStroke(0x2b)
        "ESC" -> KeyStroke(0x29)
        "LEFT" -> KeyStroke(0x50)
        "RIGHT" -> KeyStroke(0x4f)
        "UP" -> KeyStroke(0x52)
        "DOWN" -> KeyStroke(0x51)
        else -> functionKey(key)
    }

    fun shortcut(key: String, modifier: Int = HidModifier.CTRL): KeyStroke? =
        map(key)?.copy(modifiers = modifier)

    private fun functionKey(key: String): KeyStroke? =
        key.removePrefix("F").toIntOrNull()?.takeIf { it in 1..12 }?.let { KeyStroke(0x3a + it - 1) }
}
