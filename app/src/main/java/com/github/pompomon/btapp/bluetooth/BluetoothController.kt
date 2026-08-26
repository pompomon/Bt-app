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
    data class Connected(val deviceName: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

class BluetoothController(
    private val context: Context,
    private val onStateChanged: (ConnectionState) -> Unit
) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private val executor: Executor = context.mainExecutor

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            host = pluggedDevice
            onStateChanged(if (registered) ConnectionState.Registered else ConnectionState.Ready)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothHidDevice.STATE_CONNECTED -> {
                    host = device
                    val name = try {
                        device.name ?: "Bluetooth host"
                    } catch (exception: SecurityException) {
                        Log.w(TAG, "Bluetooth host name unavailable", exception)
                        "Bluetooth host"
                    }
                    onStateChanged(ConnectionState.Connected(name))
                }
                BluetoothHidDevice.STATE_DISCONNECTED -> {
                    host = null
                    onStateChanged(ConnectionState.Registered)
                }
            }
        }
    }

    fun initialState(): ConnectionState = when {
        adapter == null -> ConnectionState.Unsupported
        !hasBluetoothPermission() -> ConnectionState.PermissionRequired
        !adapter.isEnabled -> ConnectionState.BluetoothDisabled
        else -> ConnectionState.Ready
    }

    fun register() {
        when (val state = initialState()) {
            ConnectionState.Ready -> {
                onStateChanged(ConnectionState.Registering)
                if (!adapter!!.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)) {
                    onStateChanged(ConnectionState.Error("Bluetooth HID profile is unavailable on this device."))
                }
            }
            else -> onStateChanged(state)
        }
    }

    private val profileListener = object : android.bluetooth.BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
            hidDevice = proxy as? BluetoothHidDevice
            val device = hidDevice ?: run {
                onStateChanged(ConnectionState.Error("Bluetooth HID profile is unavailable on this device."))
                return
            }
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "Bt-app keyboard and mouse",
                "Standard Bluetooth HID keyboard and relative mouse",
                "pompomon",
                BluetoothHidDevice.SUBCLASS1_COMBO,
                HidDescriptor.bytes
            )
            if (!device.registerApp(sdp, null, null, executor, callback)) {
                onStateChanged(ConnectionState.Error("Could not register the Bluetooth HID device."))
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            host = null
            onStateChanged(ConnectionState.Ready)
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
            onStateChanged(ConnectionState.Error("No Bluetooth host is connected."))
            return false
        }
        val sent = try {
            hidDevice?.sendReport(target, reportId, report.copyOfRange(1, report.size)) == true
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth report rejected", exception)
            false
        }
        if (!sent) onStateChanged(ConnectionState.Error("Could not send the HID report."))
        return sent
    }

    fun disconnect() {
        sendKeyboard(HidReportEncoder.keyboard(0, emptyList()))
        sendMouse(HidReportEncoder.mouse(0, 0, 0))
        try {
            host?.let { hidDevice?.disconnect(it) }
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth disconnect rejected", exception)
        }
        host = null
    }

    fun close() {
        disconnect()
        try {
            hidDevice?.unregisterApp()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Bluetooth HID unregister rejected", exception)
        }
        hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hidDevice = null
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "BluetoothController"
    }
}
