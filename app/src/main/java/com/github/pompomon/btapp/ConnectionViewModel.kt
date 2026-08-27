package com.github.pompomon.btapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.github.pompomon.btapp.bluetooth.BluetoothController
import com.github.pompomon.btapp.bluetooth.ConnectionState
import com.github.pompomon.btapp.hid.HidReportEncoder
import com.github.pompomon.btapp.input.KeyboardInputMapper
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
        HidReportEncoder.splitMovement(x, y).ifEmpty { listOf(0 to 0) }.forEach { (dx, dy) ->
            controller.sendMouse(HidReportEncoder.mouse(buttons, dx, dy, wheel.coerceIn(-127, 127)))
        }
    }

    override fun onCleared() {
        controller.close()
    }
}
