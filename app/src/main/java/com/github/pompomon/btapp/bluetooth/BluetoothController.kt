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
    data object Registered : ConnectionState
    data class Connected(val deviceName: String, val errorMessage: String? = null) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

class BluetoothController(
    private val context: Context,
    private val onStateChanged: (ConnectionState) -> Unit
) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var connectedDeviceName = "Bluetooth host"
    private var pendingRegistration = false
    private var closed = false
    private val executor: Executor = context.mainExecutor

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (closed) return
            host = pluggedDevice
            if (registered) {
                pendingRegistration = false
                onStateChanged(ConnectionState.Registered)
            } else if (pendingRegistration) {
                pendingRegistration = false
                onStateChanged(ConnectionState.Error("Could not register the Bluetooth HID device."))
            } else {
                onStateChanged(initialState())
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (closed) return
            when (state) {
                BluetoothHidDevice.STATE_CONNECTED -> {
                    host = device
                    connectedDeviceName = try {
                        device.name ?: "Bluetooth host"
                    } catch (exception: SecurityException) {
                        Log.w(TAG, "Bluetooth host name unavailable", exception)
                        "Bluetooth host"
                    }
                    onStateChanged(ConnectionState.Connected(connectedDeviceName))
                }
                BluetoothHidDevice.STATE_DISCONNECTED -> {
                    host = null
                    connectedDeviceName = "Bluetooth host"
                    onStateChanged(ConnectionState.Registered)
                }
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

    fun register() {
        when (val state = initialState()) {
            ConnectionState.Ready -> {
                if (closed) return
                onStateChanged(ConnectionState.Registering)
                host?.let {
                    onStateChanged(ConnectionState.Connected(connectedDeviceName))
                    return
                }
                hidDevice?.let {
                    registerApp(it)
                    return
                }
                val requested = try {
                    adapter!!.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
                } catch (exception: SecurityException) {
                    Log.w(TAG, "Bluetooth HID profile access rejected", exception)
                    onStateChanged(ConnectionState.PermissionRequired)
                    return
                }
                if (!requested) {
                    onStateChanged(ConnectionState.Error("Bluetooth HID profile is unavailable on this device."))
                }
            }
            else -> onStateChanged(state)
        }
    }

    private val profileListener = object : android.bluetooth.BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
            if (closed) {
                closeProfileProxy(proxy)
                return
            }
            val device = proxy as? BluetoothHidDevice ?: run {
                closeProfileProxy(proxy)
                onStateChanged(ConnectionState.Error("Bluetooth HID profile is unavailable on this device."))
                return
            }
            hidDevice = device
            registerApp(device)
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            host = null
            if (!closed) onStateChanged(initialState())
        }
    }

    private fun registerApp(device: BluetoothHidDevice) {
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
            Log.w(TAG, "Bluetooth HID registration rejected", exception)
            onStateChanged(ConnectionState.PermissionRequired)
            return
        }
        if (!registered) {
            pendingRegistration = false
            onStateChanged(ConnectionState.Error("Could not register the Bluetooth HID device."))
        }
    }

    fun sendKeyboard(report: ByteArray) = send(HidReportEncoder.KEYBOARD_REPORT_ID, report)
    fun sendMouse(report: ByteArray) = send(HidReportEncoder.MOUSE_REPORT_ID, report)

    private fun send(reportId: Int, report: ByteArray): Boolean {
        require(
            (reportId == HidReportEncoder.KEYBOARD_REPORT_ID && report.size == 9) ||
                (reportId == HidReportEncoder.MOUSE_REPORT_ID && report.size == 5)
        ) { "HID report ID and payload length do not match." }
        val target = host ?: run {
            onStateChanged(if (hidDevice == null) initialState() else ConnectionState.Registered)
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
        val target = host ?: return
        val device = hidDevice ?: run {
            showConnectionError("Bluetooth HID profile is unavailable.")
            return
        }
        sendKeyboard(HidReportEncoder.keyboard(0, emptyList()))
        sendMouse(HidReportEncoder.mouse(0, 0, 0))
        val requested = try {
            device.disconnect(target)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth disconnect rejected", exception)
            onStateChanged(ConnectionState.PermissionRequired)
            return
        }
        if (!requested) showConnectionError("Could not disconnect from the Bluetooth host.")
    }

    fun close() {
        closed = true
        pendingRegistration = false
        val device = hidDevice
        val target = host
        try {
            if (device != null && target != null) {
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
        hidDevice = null
        host = null
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

    private companion object {
        const val TAG = "BluetoothController"
    }
}
