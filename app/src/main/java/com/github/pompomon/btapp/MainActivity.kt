package com.github.pompomon.btapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.pompomon.btapp.bluetooth.ConnectionState
import com.github.pompomon.btapp.input.HidModifier
import com.github.pompomon.btapp.input.TouchpadGestureDetector

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<ConnectionViewModel>()
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.register()
    }
    private val discoverableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BtApp(state, viewModel, ::requestBluetoothPermissions, ::requestDiscoverability)
            }
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        }
    }

    private fun requestDiscoverability() {
        try {
            discoverableLauncher.launch(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            )
        } catch (exception: SecurityException) {
            Log.w("MainActivity", "Bluetooth discoverability request rejected", exception)
            viewModel.register()
        }
    }
}

@Composable
private fun BtApp(
    state: ConnectionState,
    viewModel: ConnectionViewModel,
    requestPermissions: () -> Unit,
    requestDiscoverability: () -> Unit
) {
    var keyboard by remember { mutableStateOf(false) }
    var registrationPending by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is ConnectionState.Registering) registrationPending = true
        if (state is ConnectionState.Registered && registrationPending) {
            registrationPending = false
            requestDiscoverability()
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Bt-app", style = MaterialTheme.typography.headlineMedium)
        Text(statusText(state))
        when (state) {
            ConnectionState.PermissionRequired -> Button(onClick = requestPermissions) { Text("Grant Bluetooth permission") }
            ConnectionState.Ready, is ConnectionState.Error ->
                Button(onClick = viewModel::register) { Text("Register HID device") }
            ConnectionState.BluetoothDisabled ->
                OutlinedButton(onClick = viewModel::register) { Text("Check Bluetooth status") }
            ConnectionState.Registered ->
                Button(onClick = requestDiscoverability) { Text("Make discoverable") }
            ConnectionState.Unsupported -> Unit
            else -> Unit
        }
        if (state is ConnectionState.Connected) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (keyboard) {
                    OutlinedButton(onClick = { keyboard = false }) { Text("Touchpad") }
                    Button(onClick = { keyboard = true }) { Text("Keyboard") }
                } else {
                    Button(onClick = { keyboard = false }) { Text("Touchpad") }
                    OutlinedButton(onClick = { keyboard = true }) { Text("Keyboard") }
                }
                OutlinedButton(onClick = viewModel::disconnect) { Text("Disconnect") }
            }
            if (keyboard) Keyboard(viewModel) else Touchpad(viewModel)
        }
        Text("After registering, allow discoverability and pair this phone from your PC's Bluetooth settings. No PC companion app is required.")
    }
}

@Composable
private fun Touchpad(viewModel: ConnectionViewModel) {
    val detector = remember { TouchpadGestureDetector() }
    val click: (Int) -> Unit = { fingerCount ->
        detector.tap(fingerCount)?.let(viewModel::pointer)
        viewModel.pointer(detector.cancel())
    }
    Text("Touchpad: one-finger tap/drag to click/move; two-finger tap/drag to right-click/scroll.")
    Column(
        Modifier.fillMaxWidth().height(360.dp).background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = "Touchpad"
                onClick("Left click") {
                    click(1)
                    true
                }
            }
            .pointerInput(detector, viewModel) {
                awaitEachGesture {
                    var accumulatedDelta = Offset.Zero
                    var dragStarted = false
                    var maxFingerCount = 0
                    var previousFingerCount = 0
                    var pointersDown: Boolean
                    try {
                        do {
                            val pointerEvent = awaitPointerEvent()
                            val fingerCount = pointerEvent.changes.count { it.pressed }
                            pointersDown = fingerCount > 0
                            maxFingerCount = maxOf(maxFingerCount, fingerCount)
                            if (fingerCount != previousFingerCount) {
                                accumulatedDelta = Offset.Zero
                                previousFingerCount = fingerCount
                            }
                            if (pointerEvent.changes.any { it.isConsumed }) {
                                dragStarted = true
                                continue
                            }
                            if (maxFingerCount > fingerCount && fingerCount > 0) {
                                pointerEvent.changes.filter { it.pressed }.forEach { it.consume() }
                                continue
                            }
                            val changes = pointerEvent.changes.filter { it.pressed && it.previousPressed }
                            if (fingerCount !in 1..2 || changes.isEmpty()) continue
                            val delta = Offset(
                                changes.sumOf { it.positionChange().x.toDouble() }.toFloat() / changes.size,
                                changes.sumOf { it.positionChange().y.toDouble() }.toFloat() / changes.size
                            )
                            accumulatedDelta += delta
                            if (!dragStarted && accumulatedDelta.getDistance() <= viewConfiguration.touchSlop) continue
                            val dragDelta = if (dragStarted) delta else accumulatedDelta
                            dragStarted = true
                            accumulatedDelta = Offset.Zero
                            detector.drag(fingerCount, dragDelta.x, dragDelta.y)?.let(viewModel::pointer)
                            changes.forEach { it.consume() }
                        } while (pointersDown)
                        if (!dragStarted) detector.tap(maxFingerCount)?.let(viewModel::pointer)
                    } finally {
                        viewModel.pointer(detector.cancel())
                    }
                }
            }
    ) {}
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = { click(1) }) { Text("Left click") }
        OutlinedButton(onClick = { click(2) }) { Text("Right click") }
        OutlinedButton(onClick = { viewModel.pointer(detector.scroll(1f)) }) { Text("Scroll up") }
        OutlinedButton(onClick = { viewModel.pointer(detector.scroll(-1f)) }) { Text("Scroll down") }
    }
}

@Composable
private fun Keyboard(viewModel: ConnectionViewModel) {
    var modifiers by remember { mutableStateOf(0) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("CTRL" to HidModifier.CTRL, "SHIFT" to HidModifier.SHIFT, "ALT" to HidModifier.ALT, "META" to HidModifier.META).forEach { (label, modifier) ->
            OutlinedButton(onClick = { modifiers = modifiers xor modifier }) { Text(if (modifiers and modifier != 0) "✓ $label" else label) }
        }
    }
    listOf(
        "1234567890".map(Char::toString),
        "F1 F2 F3 F4 F5 F6 F7 F8 F9 F10 F11 F12".split(" ")
    ).forEach { row ->
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEach { key ->
                OutlinedButton(onClick = { viewModel.key(key, modifiers) }) { Text(key) }
            }
        }
    }
    listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM").forEach { row ->
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEach { key -> OutlinedButton(onClick = { viewModel.key(key.toString(), modifiers) }) { Text(key.toString()) } }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("ESC", "TAB", "SPACE", "ENTER", "BACKSPACE", "LEFT", "UP", "DOWN", "RIGHT").forEach { key ->
            OutlinedButton(onClick = { viewModel.key(key, modifiers) }) { Text(key) }
        }
    }
}

private fun statusText(state: ConnectionState): String = when (state) {
    ConnectionState.Unsupported -> "Bluetooth is not supported on this device."
    ConnectionState.BluetoothDisabled -> "Turn Bluetooth on, then register the HID device."
    ConnectionState.PermissionRequired -> "Bluetooth permission is required."
    ConnectionState.Ready -> "Ready to register as a Bluetooth keyboard and mouse."
    ConnectionState.Registering -> "Registering Bluetooth HID device…"
    ConnectionState.Registered -> "HID registered. Pair from the PC Bluetooth settings."
    is ConnectionState.Connected -> state.errorMessage ?: "Connected to ${state.deviceName}."
    is ConnectionState.Error -> state.message
}
