package com.github.pompomon.btapp.bluetooth

import android.content.Context

internal class RememberedHostPreferences(context: Context) : RememberedHostStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): RememberedHost? {
        val address = normalizeBluetoothAddress(preferences.getString(KEY_ADDRESS, null))
        if (address == null) {
            if (preferences.contains(KEY_ADDRESS) || preferences.contains(KEY_NAME)) clear()
            return null
        }
        return RememberedHost(address, safeHostName(preferences.getString(KEY_NAME, null)))
    }

    override fun save(host: RememberedHost) {
        val address = normalizeBluetoothAddress(host.address) ?: return
        preferences.edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, safeHostName(host.name))
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "remembered_hid_host"
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
    }
}
