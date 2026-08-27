package com.github.pompomon.btapp.input

data class KeyStroke(val usage: Int, val modifiers: Int = 0)

object HidModifier {
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val META = 0x08
}

class KeyboardInputMapper {
    fun map(key: String): KeyStroke? {
        val normalized = key.uppercase()
        return when {
            normalized.length == 1 && normalized[0] in 'A'..'Z' ->
                KeyStroke(0x04 + (normalized[0].code - 'A'.code))
            normalized.length == 1 && normalized[0] in '1'..'9' ->
                KeyStroke(0x1e + (normalized[0].code - '1'.code))
            normalized == "0" -> KeyStroke(0x27)
            normalized == "SPACE" -> KeyStroke(0x2c)
            normalized == "ENTER" -> KeyStroke(0x28)
            normalized == "BACKSPACE" -> KeyStroke(0x2a)
            normalized == "TAB" -> KeyStroke(0x2b)
            normalized == "ESC" -> KeyStroke(0x29)
            normalized == "LEFT" -> KeyStroke(0x50)
            normalized == "RIGHT" -> KeyStroke(0x4f)
            normalized == "UP" -> KeyStroke(0x52)
            normalized == "DOWN" -> KeyStroke(0x51)
            else -> functionKey(normalized)
        }
    }

    fun shortcut(key: String, modifier: Int = HidModifier.CTRL): KeyStroke? =
        map(key)?.copy(modifiers = modifier)

    private fun functionKey(key: String): KeyStroke? =
        key.removePrefix("F").toIntOrNull()?.takeIf { it in 1..12 }?.let { KeyStroke(0x3a + it - 1) }
}
