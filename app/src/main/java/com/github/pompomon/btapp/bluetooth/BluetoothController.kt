package com.github.pompomon.btapp.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.github.pompomon.btapp.hid.HidDescriptor
import com.github.pompomon.btapp.hid.HidReportEncoder
import java.util.concurrent.Executor

sealed interface ConnectionState {
    data object Unsupported : ConnectionState
    data object BluetoothDisabled : ConnectionState
    data object PermissionRequired : ConnectionState
    data object Ready : ConnectionState
    data object Registering : ConnectionState
    data class Registered(val rememberedDeviceName: String? = null) : ConnectionState
    data class Reconnecting(val deviceName: String) : ConnectionState
    data class Disconnecting(val deviceName: String) : ConnectionState
    data class ReconnectFailed(val deviceName: String, val message: String) : ConnectionState
    data class Connected(val deviceName: String, val errorMessage: String? = null) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

class BluetoothController(
    private val context: Context,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onDiscoverabilityRequested: () -> Unit
) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var connectionTarget: BluetoothDevice? = null
    private var connectedDeviceName = DEFAULT_HOST_NAME
    private var profileRequestPending = false
    private var pendingRegistration = false
    private var appRegistered = false
    private var closed = false
    private val executor: Executor = context.mainExecutor
    private val coordinator = ReconnectCoordinator(
        RememberedHostPreferences(context),
        HandlerReconnectScheduler(),
        ::executeActions
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (closed) return
            val registrationWasPending = pendingRegistration
            pendingRegistration = false
            appRegistered = registered
            if (registered) {
                if (pluggedDevice != null) {
                    handleConnected(pluggedDevice)
                    return
                }
                val bondedHosts = bondedHosts()
                val actions = coordinator.onRegistrationSucceeded(bondedHosts)
                if (bondedHosts != null && actions.isEmpty()) showStableState()
                executeActions(actions)
            } else {
                host = null
                connectionTarget = null
                connectedDeviceName = DEFAULT_HOST_NAME
                coordinator.onRegistrationLost()
                if (registrationWasPending) {
                    onStateChanged(ConnectionState.Error("Could not register the Bluetooth HID device."))
                } else {
                    when (coordinator.onConnectionLost()) {
                        ReconnectDisposition.RetryScheduled ->
                            showReconnectFailure("Bluetooth HID registration was interrupted. Retrying…")
                        ReconnectDisposition.Exhausted ->
                            showReconnectFailure("Bluetooth HID registration was interrupted. Tap Retry to try again.")
                        ReconnectDisposition.Idle -> onStateChanged(initialState())
                    }
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (closed) return
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    connectionTarget = device
                    coordinator.onConnectionRequested()
                    onStateChanged(ConnectionState.Reconnecting(deviceName(device)))
                }
                BluetoothProfile.STATE_CONNECTED -> handleConnected(device)
                BluetoothProfile.STATE_DISCONNECTING -> {
                    onStateChanged(ConnectionState.Disconnecting(deviceName(device)))
                }
                BluetoothProfile.STATE_DISCONNECTED -> handleDisconnected(device)
            }
        }
    }

    fun initialState(): ConnectionState {
        val bluetoothAdapter = adapter ?: return ConnectionState.Unsupported
        if (!hasBluetoothPermission()) return ConnectionState.PermissionRequired
        val enabled = try {
            bluetoothAdapter.isEnabled
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth status access rejected", exception)
            return ConnectionState.PermissionRequired
        }
        return if (enabled) ConnectionState.Ready else ConnectionState.BluetoothDisabled
    }

    fun onForeground() {
        val prerequisiteState = initialState()
        if (prerequisiteState != ConnectionState.Ready) {
            coordinator.onForeground(false, null)
            onStateChanged(prerequisiteState)
            return
        }
        val bondedHosts = bondedHosts()
        val actions = coordinator.onForeground(bondedHosts != null, bondedHosts)
        if (bondedHosts != null && actions.isEmpty()) showStableState()
        executeActions(actions)
    }

    fun onBackground() {
        coordinator.onBackground()
    }

    fun prepareForPermissionRequest() {
        if (coordinator.rememberedHost() == null) {
            coordinator.onPairRequested(false)
        } else {
            coordinator.onManualReconnect(false, null)
        }
    }

    fun onPrerequisitesChanged() {
        val prerequisiteState = initialState()
        if (prerequisiteState != ConnectionState.Ready) {
            onStateChanged(prerequisiteState)
            return
        }
        val bondedHosts = bondedHosts()
        val actions = coordinator.onPrerequisitesAvailable(bondedHosts)
        if (bondedHosts != null && actions.isEmpty()) showStableState()
        executeActions(actions)
    }

    fun pairNewDevice() {
        val prerequisiteState = initialState()
        val available = prerequisiteState == ConnectionState.Ready
        val actions = coordinator.onPairRequested(available)
        if (!available) {
            onStateChanged(prerequisiteState)
            return
        }
        executeActions(actions)
    }

    fun reconnect() {
        val prerequisiteState = initialState()
        val available = prerequisiteState == ConnectionState.Ready
        val bondedHosts = if (available) bondedHosts() else null
        val actions = coordinator.onManualReconnect(available && bondedHosts != null, bondedHosts)
        if (!available) {
            onStateChanged(prerequisiteState)
            return
        }
        executeActions(actions)
    }

    fun forgetRememberedHost() {
        coordinator.forgetRememberedHost()
        showStableState()
    }

    private fun ensureRegistration() {
        if (closed) return
        when (val state = initialState()) {
            ConnectionState.Ready -> Unit
            else -> {
                coordinator.onRegistrationFailed()
                onStateChanged(state)
                return
            }
        }
        host?.let {
            handleConnected(it)
            return
        }
        if (appRegistered) {
            val bondedHosts = bondedHosts()
            val actions = coordinator.onRegistrationSucceeded(bondedHosts)
            if (bondedHosts != null && actions.isEmpty()) showStableState()
            executeActions(actions)
            return
        }
        if (pendingRegistration || profileRequestPending) return

        onStateChanged(ConnectionState.Registering)
        hidDevice?.let {
            registerApp(it)
            return
        }
        profileRequestPending = true
        val requested = try {
            adapter!!.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (exception: SecurityException) {
            profileRequestPending = false
            coordinator.onRegistrationFailed()
            Log.w(TAG, "Bluetooth HID profile access rejected", exception)
            onStateChanged(ConnectionState.PermissionRequired)
            return
        }
        if (!requested) {
            profileRequestPending = false
            coordinator.onRegistrationFailed()
            onStateChanged(ConnectionState.Error("Bluetooth HID profile is unavailable on this device."))
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            profileRequestPending = false
            if (closed) {
                closeProfileProxy(proxy)
                return
            }
            val device = proxy as? BluetoothHidDevice ?: run {
                closeProfileProxy(proxy)
                coordinator.onRegistrationFailed()
                onStateChanged(ConnectionState.Error("Bluetooth HID profile is unavailable on this device."))
                return
            }
            hidDevice = device
            registerApp(device)
        }

        override fun onServiceDisconnected(profile: Int) {
            profileRequestPending = false
            pendingRegistration = false
            appRegistered = false
            hidDevice = null
            host = null
            connectionTarget = null
            coordinator.onRegistrationLost()
            if (!closed) {
                val disposition = coordinator.onConnectionLost()
                if (disposition == ReconnectDisposition.RetryScheduled) {
                    showReconnectFailure("Bluetooth HID service was interrupted. Retrying…")
                } else {
                    onStateChanged(initialState())
                }
            }
        }
    }

    private fun registerApp(device: BluetoothHidDevice) {
        if (closed || pendingRegistration || appRegistered) return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Bt-app keyboard and mouse",
            "Standard Bluetooth HID keyboard and relative mouse",
            "pompomon",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            HidDescriptor.bytes
        )
        pendingRegistration = true
        val registered = try {
            device.registerApp(sdp, null, null, executor, callback)
        } catch (exception: SecurityException) {
            pendingRegistration = false
            coordinator.onRegistrationFailed()
            Log.w(TAG, "Bluetooth HID registration rejected", exception)
            onStateChanged(ConnectionState.PermissionRequired)
            return
        }
        if (!registered) {
            pendingRegistration = false
            coordinator.onRegistrationFailed()
            onStateChanged(ConnectionState.Error("Could not register the Bluetooth HID device."))
        }
    }

    private fun requestConnection(rememberedHost: RememberedHost) {
        val device = when (val result = findBondedDevice(rememberedHost.address)) {
            is BondedDeviceLookup.Found -> result.device
            BondedDeviceLookup.Missing -> {
                coordinator.forgetRememberedHost()
                onStateChanged(
                    ConnectionState.Error("${rememberedHost.name} is no longer paired. Pair it again.")
                )
                return
            }
            BondedDeviceLookup.PermissionRequired -> {
                coordinator.onConnectionRequestFailed()
                onStateChanged(ConnectionState.PermissionRequired)
                return
            }
        }
        val hid = hidDevice
        if (!appRegistered || hid == null) {
            coordinator.onRegistrationLost()
            executeActions(coordinator.onPrerequisitesAvailable(bondedHosts()))
            return
        }

        connectionTarget = device
        val name = deviceName(device, rememberedHost.name)
        connectedDeviceName = name
        onStateChanged(ConnectionState.Reconnecting(name))
        val requested = try {
            hid.connect(device)
        } catch (exception: SecurityException) {
            connectionTarget = null
            coordinator.onConnectionRequestFailed()
            Log.w(TAG, "Bluetooth reconnect rejected", exception)
            onStateChanged(ConnectionState.PermissionRequired)
            return
        }
        if (!requested) {
            connectionTarget = null
            val disposition = coordinator.onConnectionRequestFailed()
            showReconnectFailure(reconnectFailureMessage(disposition))
        }
    }

    private fun handleConnected(device: BluetoothDevice) {
        val identity = deviceIdentity(device) ?: return
        when (coordinator.onConnected(identity)) {
            ConnectionDecision.Accept -> {
                host = device
                connectionTarget = null
                connectedDeviceName = identity.name
                onStateChanged(ConnectionState.Connected(connectedDeviceName))
            }
            ConnectionDecision.Disconnect -> {
                host = null
                connectionTarget = device
                disconnectDevice(device, releaseReports = false)
            }
        }
    }

    private fun handleDisconnected(device: BluetoothDevice) {
        val disconnectedAddress = deviceAddress(device) ?: return
        val activeAddress = host?.let(::deviceAddress)
        val targetAddress = connectionTarget?.let(::deviceAddress)
        if (activeAddress != disconnectedAddress && targetAddress != disconnectedAddress) return

        val name = deviceName(device, connectedDeviceName)
        host = null
        connectionTarget = null
        connectedDeviceName = DEFAULT_HOST_NAME
        when (coordinator.onConnectionLost()) {
            ReconnectDisposition.RetryScheduled ->
                onStateChanged(ConnectionState.ReconnectFailed(name, "Connection lost. Retrying…"))
            ReconnectDisposition.Exhausted ->
                onStateChanged(ConnectionState.ReconnectFailed(name, "Could not reconnect. Tap Retry to try again."))
            ReconnectDisposition.Idle -> showStableState()
        }
    }

    private fun executeActions(actions: List<ReconnectAction>) {
        if (closed) return
        actions.forEach { action ->
            when (action) {
                ReconnectAction.RegisterHid -> ensureRegistration()
                is ReconnectAction.Connect -> requestConnection(action.host)
                ReconnectAction.RequestDiscoverability -> {
                    showStableState()
                    onDiscoverabilityRequested()
                }
                is ReconnectAction.RememberedHostUnavailable -> {
                    onStateChanged(
                        ConnectionState.Error("${action.deviceName} is no longer paired. Pair it again.")
                    )
                }
                ReconnectAction.Retry -> retryReconnect()
            }
        }
    }

    private fun retryReconnect() {
        val prerequisiteState = initialState()
        if (prerequisiteState != ConnectionState.Ready) {
            onStateChanged(prerequisiteState)
            return
        }
        val bondedHosts = bondedHosts()
        executeActions(coordinator.onRetry(bondedHosts != null, bondedHosts))
    }

    fun sendKeyboard(report: ByteArray) = send(HidReportEncoder.KEYBOARD_REPORT_ID, report)
    fun sendMouse(report: ByteArray) = send(HidReportEncoder.MOUSE_REPORT_ID, report)

    private fun send(reportId: Int, report: ByteArray): Boolean {
        require(
            (reportId == HidReportEncoder.KEYBOARD_REPORT_ID && report.size == 9) ||
                (reportId == HidReportEncoder.MOUSE_REPORT_ID && report.size == 5)
        ) { "HID report ID and payload length do not match." }
        val target = host ?: run {
            showStableState()
            return false
        }
        val sent = try {
            hidDevice?.sendReport(target, reportId, report.copyOfRange(1, report.size)) == true
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth report rejected", exception)
            onStateChanged(ConnectionState.PermissionRequired)
            return false
        }
        if (sent) {
            onStateChanged(ConnectionState.Connected(connectedDeviceName))
        } else {
            showConnectionError("Could not send the HID report.")
        }
        return sent
    }

    fun disconnect() {
        coordinator.onManualDisconnect()
        val target = host ?: connectionTarget
        if (target == null) {
            showStableState()
            return
        }
        disconnectDevice(target, releaseReports = host != null)
    }

    private fun disconnectDevice(target: BluetoothDevice, releaseReports: Boolean) {
        val device = hidDevice ?: run {
            if (host != null) showConnectionError("Bluetooth HID profile is unavailable.") else showStableState()
            return
        }
        val name = deviceName(target, connectedDeviceName)
        val requested = try {
            if (releaseReports) releaseInputs(device, target)
            device.disconnect(target)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth disconnect rejected", exception)
            onStateChanged(ConnectionState.PermissionRequired)
            return
        }
        if (requested) {
            onStateChanged(ConnectionState.Disconnecting(name))
        } else if (host != null) {
            showConnectionError("Could not disconnect from the Bluetooth host.")
        } else {
            connectionTarget = null
            showStableState()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        coordinator.onBackground()
        coordinator.onManualDisconnect()
        profileRequestPending = false
        pendingRegistration = false
        val device = hidDevice
        val target = host
        try {
            if (device != null && target != null) {
                releaseInputs(device, target)
                device.disconnect(target)
            }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth HID disconnect cleanup rejected", exception)
        }
        try {
            device?.unregisterApp()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth HID unregister rejected", exception)
        }
        device?.let(::closeProfileProxy)
        appRegistered = false
        hidDevice = null
        host = null
        connectionTarget = null
    }

    private fun releaseInputs(device: BluetoothHidDevice, target: BluetoothDevice) {
        device.sendReport(
            target,
            HidReportEncoder.KEYBOARD_REPORT_ID,
            HidReportEncoder.keyboard(0, emptyList()).copyOfRange(1, 9)
        )
        device.sendReport(
            target,
            HidReportEncoder.MOUSE_REPORT_ID,
            HidReportEncoder.mouse(0, 0, 0).copyOfRange(1, 5)
        )
    }

    private fun bondedHosts(): List<RememberedHost>? {
        val devices = bondedDevices() ?: return null
        val hosts = mutableListOf<RememberedHost>()
        for (device in devices) {
            val identity = deviceIdentity(device) ?: return null
            hosts += identity
        }
        return hosts
    }

    private fun findBondedDevice(address: String): BondedDeviceLookup {
        val normalizedAddress = normalizeBluetoothAddress(address) ?: return BondedDeviceLookup.Missing
        val devices = bondedDevices() ?: return BondedDeviceLookup.PermissionRequired
        val device = devices.firstOrNull {
            deviceAddress(it) == normalizedAddress
        }
        return device?.let(BondedDeviceLookup::Found) ?: BondedDeviceLookup.Missing
    }

    private fun bondedDevices(): Set<BluetoothDevice>? =
        try {
            adapter?.bondedDevices
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth paired devices access rejected", exception)
            onStateChanged(ConnectionState.PermissionRequired)
            null
        }

    private fun deviceIdentity(device: BluetoothDevice): RememberedHost? {
        val address = deviceAddress(device) ?: return null
        return RememberedHost(address, deviceName(device))
    }

    private fun deviceAddress(device: BluetoothDevice): String? =
        try {
            normalizeBluetoothAddress(device.address)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth host address unavailable", exception)
            onStateChanged(ConnectionState.PermissionRequired)
            null
        }

    private fun deviceName(device: BluetoothDevice, fallback: String = DEFAULT_HOST_NAME): String =
        try {
            safeHostName(device.name ?: fallback)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth host name unavailable", exception)
            safeHostName(fallback)
        }

    private fun showStableState() {
        when {
            host != null -> onStateChanged(ConnectionState.Connected(connectedDeviceName))
            connectionTarget != null -> onStateChanged(ConnectionState.Reconnecting(connectedDeviceName))
            pendingRegistration || profileRequestPending -> onStateChanged(ConnectionState.Registering)
            appRegistered -> onStateChanged(
                ConnectionState.Registered(coordinator.rememberedHost()?.name)
            )
            else -> onStateChanged(initialState())
        }
    }

    private fun showReconnectFailure(message: String) {
        val remembered = coordinator.rememberedHost()
        onStateChanged(
            ConnectionState.ReconnectFailed(
                remembered?.name ?: connectedDeviceName,
                message
            )
        )
    }

    private fun reconnectFailureMessage(disposition: ReconnectDisposition): String =
        if (disposition == ReconnectDisposition.RetryScheduled) {
            "Could not reconnect. Retrying…"
        } else {
            "Could not reconnect. Tap Retry to try again."
        }

    private fun showConnectionError(message: String) {
        onStateChanged(ConnectionState.Connected(connectedDeviceName, message))
    }

    private fun closeProfileProxy(proxy: BluetoothProfile) {
        try {
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth HID profile cleanup rejected", exception)
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                )

    private sealed interface BondedDeviceLookup {
        data class Found(val device: BluetoothDevice) : BondedDeviceLookup
        data object Missing : BondedDeviceLookup
        data object PermissionRequired : BondedDeviceLookup
    }

    private companion object {
        const val TAG = "BluetoothController"
    }
}
