package com.github.pompomon.btapp

import com.github.pompomon.btapp.input.HidModifier

internal data class KeyboardKey(
    val label: String,
    val command: String? = null,
    val modifier: Int? = null,
    val weight: Float = 1f,
    val contentDescription: String = label
)

internal object KeyboardLayout {
    val rows: List<List<KeyboardKey>> = listOf(
        listOf(key("ESC", "Esc", 1.25f, "Escape")) +
            (1..12).map { key("F$it", description = "Function $it") },
        listOf(key("TAB", "Tab", 1.4f, "Tab")) +
            "1234567890".map { key(it.toString()) } +
            listOf(key("BACKSPACE", "⌫", 2f, "Backspace")),
        "QWERTYUIOP".map { key(it.toString()) },
        "ASDFGHJKL".map { key(it.toString()) } +
            listOf(key("ENTER", "Enter", 1.8f, "Enter")),
        listOf(modifier("Shift", HidModifier.SHIFT, 1.8f, "Shift modifier")) +
            "ZXCVBNM".map { key(it.toString()) } +
            listOf(key("UP", "↑", 1.2f, "Up arrow")),
        listOf(
            modifier("Ctrl", HidModifier.CTRL, 1.3f, "Control modifier"),
            modifier("Alt", HidModifier.ALT, 1.2f, "Alt modifier"),
            modifier("Meta", HidModifier.META, 1.4f, "Meta modifier"),
            key("SPACE", "Space", 4f, "Space"),
            key("LEFT", "←", 1.1f, "Left arrow"),
            key("DOWN", "↓", 1.1f, "Down arrow"),
            key("RIGHT", "→", 1.1f, "Right arrow")
        )
    )

    private fun key(
        command: String,
        label: String = command,
        weight: Float = 1f,
        description: String = label
    ) = KeyboardKey(label, command = command, weight = weight, contentDescription = description)

    private fun modifier(
        label: String,
        modifier: Int,
        weight: Float,
        description: String
    ) = KeyboardKey(label, modifier = modifier, weight = weight, contentDescription = description)
}
