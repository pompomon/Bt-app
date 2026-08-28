package com.github.pompomon.btapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.github.pompomon.btapp.bluetooth.BluetoothController
import com.github.pompomon.btapp.bluetooth.ConnectionState
import com.github.pompomon.btapp.hid.HidReportEncoder
import com.github.pompomon.btapp.input.KeyboardInputMapper
import com.github.pompomon.btapp.input.PointerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Ready)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()
    private val controller = BluetoothController(application) { _state.value = it }
    private val mapper = KeyboardInputMapper()

    init {
        _state.value = controller.initialState()
    }

    fun register() = controller.register()
    fun disconnect() = controller.disconnect()

    fun key(key: String, modifiers: Int = 0) {
        val stroke = if (modifiers == 0) mapper.map(key) else mapper.shortcut(key, modifiers)
        if (stroke == null) return
        controller.sendKeyboard(HidReportEncoder.keyboard(stroke.modifiers, listOf(stroke.usage)))
        controller.sendKeyboard(HidReportEncoder.keyboard(0, emptyList()))
    }

    fun mouse(buttons: Int, x: Int, y: Int, wheel: Int = 0) {
        HidReportEncoder.mouseSequence(buttons, x, y, wheel).forEach(controller::sendMouse)
    }

    fun pointer(event: PointerEvent) {
        when (event) {
            is PointerEvent.Move -> mouse(0, event.x, event.y)
            is PointerEvent.Scroll -> mouse(0, 0, 0, event.amount)
            is PointerEvent.Button -> mouse(if (event.pressed) event.buttonMask else 0, 0, 0)
        }
    }

    override fun onCleared() {
        controller.close()
    }
}
