package com.github.pompomon.btapp.input

sealed interface PointerEvent {
    data class Move(val x: Int, val y: Int) : PointerEvent
    data class Scroll(val amount: Int) : PointerEvent
    data class Button(val buttonMask: Int, val pressed: Boolean) : PointerEvent
}

class TouchpadGestureDetector(
    private val pointerSensitivity: Float = 1f,
    private val scrollSensitivity: Float = 1f
) {
    fun move(deltaX: Float, deltaY: Float): PointerEvent.Move =
        PointerEvent.Move((deltaX * pointerSensitivity).toInt(), (deltaY * pointerSensitivity).toInt())

    fun scroll(deltaY: Float): PointerEvent.Scroll =
        PointerEvent.Scroll((deltaY * scrollSensitivity).toInt().coerceIn(-127, 127))

    fun drag(fingerCount: Int, deltaX: Float, deltaY: Float): PointerEvent? = when (fingerCount) {
        1 -> move(deltaX, deltaY)
        2 -> scroll(deltaY)
        else -> null
    }

    fun tap(fingerCount: Int): PointerEvent.Button? = when (fingerCount) {
        1 -> PointerEvent.Button(1, true)
        2 -> PointerEvent.Button(2, true)
        else -> null
    }

    fun cancel(): PointerEvent.Button = PointerEvent.Button(0, false)
}
