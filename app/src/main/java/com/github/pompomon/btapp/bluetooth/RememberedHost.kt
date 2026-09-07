package com.github.pompomon.btapp.bluetooth

import java.util.Locale

internal const val DEFAULT_HOST_NAME = "Bluetooth host"

data class RememberedHost(
    val address: String,
    val name: String
)

data class HostSelection(
    val hosts: List<RememberedHost> = emptyList(),
    val selectedAddress: String? = null
) {
    val selectedHost: RememberedHost?
        get() = hosts.firstOrNull { it.address == selectedAddress }
}

internal interface RememberedHostStore {
    fun load(): RememberedHost?
    fun loadAll(): List<RememberedHost>
    fun save(host: RememberedHost)
    fun select(address: String): RememberedHost?
    fun remove(address: String)
    fun clear()
}

internal fun normalizeBluetoothAddress(address: String?): String? {
    val value = address?.trim() ?: return null
    return if (BLUETOOTH_ADDRESS.matches(value)) value.uppercase(Locale.ROOT) else null
}

internal fun safeHostName(name: String?): String {
    val value = name
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(MAX_HOST_NAME_LENGTH)
        .orEmpty()
    return value.ifEmpty { DEFAULT_HOST_NAME }
}

private val BLUETOOTH_ADDRESS = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
private const val MAX_HOST_NAME_LENGTH = 100
