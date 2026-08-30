package com.github.pompomon.btapp

import com.github.pompomon.btapp.input.HidModifier
import com.github.pompomon.btapp.input.KeyboardInputMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KeyboardLayoutTest {
    @Test fun `keyboard contains every supported control exactly once`() {
        val controls = KeyboardLayout.rows.flatten()
        val commands = controls.mapNotNull(KeyboardKey::command)
        val expectedCommands = buildSet {
            addAll((1..12).map { "F$it" })
            addAll("1234567890".map(Char::toString))
            addAll("ABCDEFGHIJKLMNOPQRSTUVWXYZ".map(Char::toString))
            addAll(listOf("ESC", "TAB", "BACKSPACE", "ENTER", "SPACE", "LEFT", "UP", "DOWN", "RIGHT"))
        }

        assertEquals(listOf(13, 12, 10, 10, 9, 7), KeyboardLayout.rows.map { it.size })
        assertEquals(61, controls.size)
        assertEquals(expectedCommands.size, commands.size)
        assertEquals(expectedCommands, commands.toSet())
        commands.forEach { assertNotNull("$it must remain mappable", KeyboardInputMapper().map(it)) }

        val modifiers = controls.mapNotNull(KeyboardKey::modifier)
        assertEquals(
            setOf(HidModifier.CTRL, HidModifier.SHIFT, HidModifier.ALT, HidModifier.META),
            modifiers.toSet()
        )
        assertEquals(4, modifiers.size)
    }
}
