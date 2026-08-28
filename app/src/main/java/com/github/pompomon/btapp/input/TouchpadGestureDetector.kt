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
    private var remainingX = 0f
    private var remainingY = 0f
    private var remainingScroll = 0f

    fun move(deltaX: Float, deltaY: Float): PointerEvent.Move {
        val scaledX = deltaX * pointerSensitivity + remainingX
        val scaledY = deltaY * pointerSensitivity + remainingY
        val x = scaledX.toInt()
        val y = scaledY.toInt()
        remainingX = scaledX - x
        remainingY = scaledY - y
        return PointerEvent.Move(x, y)
    }

    fun scroll(deltaY: Float): PointerEvent.Scroll {
        val scaled = deltaY * scrollSensitivity + remainingScroll
        val amount = scaled.toInt()
        remainingScroll = scaled - amount
        return PointerEvent.Scroll(amount.coerceIn(-127, 127))
    }

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

    fun cancel(): PointerEvent.Button {
        remainingX = 0f
        remainingY = 0f
        remainingScroll = 0f
        return PointerEvent.Button(0, false)
    }
}
