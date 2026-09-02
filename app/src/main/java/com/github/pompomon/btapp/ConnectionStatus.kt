package com.github.pompomon.btapp

import com.github.pompomon.btapp.bluetooth.ConnectionState

internal enum class ConnectionStatusTone {
    Connected,
    Progress,
    Error,
    Idle
}

internal data class ConnectionStatus(
    val text: String,
    val tone: ConnectionStatusTone
)

internal fun connectionStatus(state: ConnectionState): ConnectionStatus = when (state) {
    ConnectionState.Unsupported -> ConnectionStatus(
        "Bluetooth is not supported on this device.",
        ConnectionStatusTone.Error
    )
    ConnectionState.BluetoothDisabled -> ConnectionStatus(
        "Turn Bluetooth on, then register the HID device.",
        ConnectionStatusTone.Error
    )
    ConnectionState.PermissionRequired -> ConnectionStatus(
        "Bluetooth permission is required.",
        ConnectionStatusTone.Error
    )
    ConnectionState.Ready -> ConnectionStatus(
        "Ready to register as a Bluetooth keyboard and mouse.",
        ConnectionStatusTone.Idle
    )
    ConnectionState.Registering -> ConnectionStatus(
        "Registering Bluetooth HID device…",
        ConnectionStatusTone.Progress
    )
    is ConnectionState.Registered -> ConnectionStatus(
        state.rememberedDeviceName?.let { "HID registered. Ready to reconnect to $it." }
            ?: "HID registered. Pair from the PC Bluetooth settings.",
        ConnectionStatusTone.Idle
    )
    is ConnectionState.CheckingConnection -> ConnectionStatus(
        "Checking connection to ${state.deviceName}…",
        ConnectionStatusTone.Progress
    )
    is ConnectionState.Reconnecting -> ConnectionStatus(
        "Reconnecting to ${state.deviceName}…",
        ConnectionStatusTone.Progress
    )
    is ConnectionState.Disconnecting -> ConnectionStatus(
        "Disconnecting from ${state.deviceName}…",
        ConnectionStatusTone.Progress
    )
    is ConnectionState.ReconnectFailed -> ConnectionStatus(
        state.message,
        ConnectionStatusTone.Error
    )
    is ConnectionState.Connected -> ConnectionStatus(
        state.errorMessage ?: "Connected to ${state.deviceName}.",
        if (state.errorMessage == null) ConnectionStatusTone.Connected else ConnectionStatusTone.Error
    )
    is ConnectionState.Error -> ConnectionStatus(state.message, ConnectionStatusTone.Error)
}

internal val ConnectionState.showsInputControls: Boolean
    get() = this is ConnectionState.CheckingConnection ||
        this is ConnectionState.Reconnecting ||
        this is ConnectionState.Disconnecting ||
        (this is ConnectionState.ReconnectFailed && retrying) ||
        this is ConnectionState.Connected

internal val ConnectionState.acceptsHidInput: Boolean
    get() = this is ConnectionState.Connected && errorMessage == null
