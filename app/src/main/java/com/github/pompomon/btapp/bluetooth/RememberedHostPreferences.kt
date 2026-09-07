package com.github.pompomon.btapp.bluetooth

import android.content.Context

internal class RememberedHostPreferences(context: Context) : RememberedHostStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): RememberedHost? {
        val hosts = loadAll()
        if (hosts.isEmpty()) return null
        val selectedAddress = normalizeBluetoothAddress(preferences.getString(KEY_SELECTED_ADDRESS, null))
        val selected = hosts.firstOrNull { it.address == selectedAddress } ?: hosts.first()
        if (selected.address != selectedAddress) {
            preferences.edit().putString(KEY_SELECTED_ADDRESS, selected.address).apply()
        }
        return selected
    }

    override fun loadAll(): List<RememberedHost> {
        migrateLegacyHost()
        val entries = preferences.getStringSet(KEY_HOSTS, emptySet()).orEmpty().toSet()
        val hosts = entries.mapNotNull(::decodeHost)
            .distinctBy(RememberedHost::address)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RememberedHost::name).thenBy(RememberedHost::address))
        if (hosts.size != entries.size) saveHosts(hosts)
        return hosts
    }

    override fun save(host: RememberedHost) {
        val address = normalizeBluetoothAddress(host.address) ?: return
        val normalized = RememberedHost(address, safeHostName(host.name))
        val hosts = (loadAll().filterNot { it.address == address } + normalized)
            .takeLast(MAX_REMEMBERED_HOSTS)
        saveHosts(hosts, address)
    }

    override fun select(address: String): RememberedHost? {
        val normalized = normalizeBluetoothAddress(address) ?: return null
        val host = loadAll().firstOrNull { it.address == normalized } ?: return null
        preferences.edit().putString(KEY_SELECTED_ADDRESS, normalized).apply()
        return host
    }

    override fun remove(address: String) {
        val normalized = normalizeBluetoothAddress(address) ?: return
        val remaining = loadAll().filterNot { it.address == normalized }
        val selectedAddress = normalizeBluetoothAddress(preferences.getString(KEY_SELECTED_ADDRESS, null))
        saveHosts(
            remaining,
            if (selectedAddress == normalized) remaining.firstOrNull()?.address else selectedAddress
        )
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private fun migrateLegacyHost() {
        if (preferences.contains(KEY_HOSTS)) return
        val address = normalizeBluetoothAddress(preferences.getString(KEY_ADDRESS, null))
        val name = preferences.getString(KEY_NAME, null)
        val editor = preferences.edit().remove(KEY_ADDRESS).remove(KEY_NAME)
        if (address == null) {
            editor.putStringSet(KEY_HOSTS, emptySet()).remove(KEY_SELECTED_ADDRESS).apply()
            return
        }
        val host = RememberedHost(address, safeHostName(name))
        editor
            .putStringSet(KEY_HOSTS, setOf(encodeHost(host)))
            .putString(KEY_SELECTED_ADDRESS, address)
            .apply()
    }

    private fun saveHosts(hosts: List<RememberedHost>, selectedAddress: String? = null) {
        val normalizedHosts = hosts.mapNotNull { host ->
            normalizeBluetoothAddress(host.address)?.let {
                RememberedHost(it, safeHostName(host.name))
            }
        }.distinctBy(RememberedHost::address)
        val selected = normalizeBluetoothAddress(selectedAddress)
            ?.takeIf { address -> normalizedHosts.any { it.address == address } }
        preferences.edit()
            .putStringSet(KEY_HOSTS, normalizedHosts.map(::encodeHost).toSet())
            .let { editor ->
                if (selected == null) editor.remove(KEY_SELECTED_ADDRESS)
                else editor.putString(KEY_SELECTED_ADDRESS, selected)
            }
            .remove(KEY_ADDRESS)
            .remove(KEY_NAME)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "remembered_hid_host"
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val KEY_HOSTS = "hosts"
        const val KEY_SELECTED_ADDRESS = "selected_address"
        const val HOST_SEPARATOR = '|'
        const val BLUETOOTH_ADDRESS_LENGTH = 17
        const val MAX_REMEMBERED_HOSTS = 10

        fun encodeHost(host: RememberedHost): String = "${host.address}$HOST_SEPARATOR${host.name}"

        fun decodeHost(value: String): RememberedHost? {
            if (value.length <= BLUETOOTH_ADDRESS_LENGTH || value[BLUETOOTH_ADDRESS_LENGTH] != HOST_SEPARATOR) {
                return null
            }
            val address = normalizeBluetoothAddress(value.take(BLUETOOTH_ADDRESS_LENGTH)) ?: return null
            return RememberedHost(
                address,
                safeHostName(value.substring(BLUETOOTH_ADDRESS_LENGTH + 1))
            )
        }
    }
}
