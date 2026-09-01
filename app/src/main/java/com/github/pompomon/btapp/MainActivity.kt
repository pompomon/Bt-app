package com.github.pompomon.btapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.pompomon.btapp.bluetooth.ConnectionState
import com.github.pompomon.btapp.input.TouchpadGestureDetector
import kotlinx.coroutines.flow.collect

class MainActivity : AppCompatActivity() {
    private val viewModel by viewModels<ConnectionViewModel>()
    private val themePreferences by lazy { ThemePreferences(this) }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPrerequisitesChanged()
    }
    private val discoverableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onDiscoverabilityResult(it.resultCode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(themePreferences.load().toNightMode())
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val currentTheme = remember { mutableStateOf(themePreferences.load()) }
            val darkTheme = when (currentTheme.value) {
                ThemePreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemePreferences.ThemeMode.LIGHT -> false
                ThemePreferences.ThemeMode.DARK -> true
            }
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    BtApp(
                        state = state,
                        viewModel = viewModel,
                        requestPermissions = ::requestBluetoothPermissions,
                        requestDiscoverability = ::requestDiscoverability,
                        themeMode = currentTheme.value,
                        onThemeModeChanged = { mode ->
                            currentTheme.value = mode
                            themePreferences.save(mode)
                            AppCompatDelegate.setDefaultNightMode(mode.toNightMode())
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    private fun requestBluetoothPermissions() {
        viewModel.prepareForPermissionRequest()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
        } else {
            viewModel.onPrerequisitesChanged()
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
            viewModel.onDiscoverabilityResult(0)
            viewModel.onPrerequisitesChanged()
        }
    }
}

@Composable
private fun BtApp(
    state: ConnectionState,
    viewModel: ConnectionViewModel,
    requestPermissions: () -> Unit,
    requestDiscoverability: () -> Unit,
    themeMode: ThemePreferences.ThemeMode,
    onThemeModeChanged: (ThemePreferences.ThemeMode) -> Unit
) {
    var keyboard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ConnectionEvent.RequestDiscoverability -> requestDiscoverability()
            }
        }
    }

    if (state is ConnectionState.Connected) {
        ConnectedScreen(
            state = state,
            keyboard = keyboard,
            selectTouchpad = { keyboard = false },
            selectKeyboard = { keyboard = true },
            viewModel = viewModel
        )
    } else {
        SetupScreen(
            state = state,
            viewModel = viewModel,
            requestPermissions = requestPermissions,
            themeMode = themeMode,
            onThemeModeChanged = onThemeModeChanged
        )
    }
}

@Composable
private fun SetupScreen(
    state: ConnectionState,
    viewModel: ConnectionViewModel,
    requestPermissions: () -> Unit,
    themeMode: ThemePreferences.ThemeMode,
    onThemeModeChanged: (ThemePreferences.ThemeMode) -> Unit
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Bt-app", style = MaterialTheme.typography.headlineMedium)
        Text(statusText(state))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeSelectionButton(
                label = "System",
                selected = themeMode == ThemePreferences.ThemeMode.SYSTEM,
                onClick = { onThemeModeChanged(ThemePreferences.ThemeMode.SYSTEM) }
            )
            ThemeSelectionButton(
                label = "Light",
                selected = themeMode == ThemePreferences.ThemeMode.LIGHT,
                onClick = { onThemeModeChanged(ThemePreferences.ThemeMode.LIGHT) }
            )
            ThemeSelectionButton(
                label = "Dark",
                selected = themeMode == ThemePreferences.ThemeMode.DARK,
                onClick = { onThemeModeChanged(ThemePreferences.ThemeMode.DARK) }
            )
        }
        when (state) {
            ConnectionState.PermissionRequired -> Button(onClick = requestPermissions) { Text("Grant Bluetooth permission") }
            ConnectionState.Ready, is ConnectionState.Error ->
                Button(onClick = viewModel::pairNewDevice) { Text("Pair a device") }
            ConnectionState.BluetoothDisabled ->
                OutlinedButton(onClick = viewModel::onPrerequisitesChanged) { Text("Check Bluetooth status") }
            is ConnectionState.Registered -> {
                if (state.rememberedDeviceName == null) {
                    Button(onClick = viewModel::pairNewDevice) { Text("Pair a device") }
                } else {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = viewModel::reconnect) { Text("Reconnect") }
                        OutlinedButton(onClick = viewModel::pairNewDevice) { Text("Pair another device") }
                        OutlinedButton(onClick = viewModel::forgetRememberedHost) { Text("Forget device") }
                    }
                }
            }
            is ConnectionState.ReconnectFailed -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = viewModel::reconnect) { Text("Retry") }
                    OutlinedButton(onClick = viewModel::pairNewDevice) { Text("Pair another device") }
                    OutlinedButton(onClick = viewModel::forgetRememberedHost) { Text("Forget device") }
                }
            }
            is ConnectionState.Reconnecting ->
                OutlinedButton(onClick = viewModel::disconnect) { Text("Cancel") }
            ConnectionState.Unsupported -> Unit
            else -> Unit
        }
        Text("Pair from your PC's Bluetooth settings. The last connected computer reconnects automatically while this app is open.")
    }
}

@Composable
private fun ConnectedScreen(
    state: ConnectionState.Connected,
    keyboard: Boolean,
    selectTouchpad: () -> Unit,
    selectKeyboard: () -> Unit,
    viewModel: ConnectionViewModel
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusText(state),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            ModeButton("Touchpad", selected = !keyboard, onClick = selectTouchpad)
            ModeButton("Keyboard", selected = keyboard, onClick = selectKeyboard)
            OutlinedButton(
                onClick = viewModel::disconnect,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("Disconnect", maxLines = 1, softWrap = false)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (keyboard) Keyboard(viewModel) else Touchpad(viewModel)
        }
    }
}

@Composable
private fun ThemeSelectionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val modifier = Modifier.semantics { this.selected = selected }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) { Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) { Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.semantics { this.selected = selected }
    val content: @Composable () -> Unit = {
        Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
    }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            content = { content() }
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            content = { content() }
        )
    }
}

@Composable
private fun Touchpad(viewModel: ConnectionViewModel) {
    val detector = remember { TouchpadGestureDetector() }
    val click: (Int) -> Unit = { fingerCount ->
        detector.tap(fingerCount)?.let(viewModel::pointer)
        viewModel.pointer(detector.cancel())
    }

    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.weight(2f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant)
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
        )
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "One-finger drag/tap: move/left click\nTwo-finger drag/tap: scroll/right click",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TouchpadActionButton("Left click", { click(1) }, Modifier.weight(1f))
                    TouchpadActionButton("Right click", { click(2) }, Modifier.weight(1f))
                }
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TouchpadActionButton(
                        "Scroll up",
                        { viewModel.pointer(detector.scroll(1f)) },
                        Modifier.weight(1f)
                    )
                    TouchpadActionButton(
                        "Scroll down",
                        { viewModel.pointer(detector.scroll(-1f)) },
                        Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TouchpadActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun Keyboard(viewModel: ConnectionViewModel) {
    var modifiers by remember { mutableStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 700.dp || maxHeight < 300.dp
        val spacing = if (compact) 2.dp else 4.dp
        val rowHeight = 48.dp
        val keyboardWidth = maxWidth

        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            KeyboardLayout.rows.forEachIndexed { rowIndex, row ->
                val availableKeyWidth = keyboardWidth - spacing * 2 - spacing * (row.size - 1)
                val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
                val minRowWidth = row.fold(0.dp) { width, key ->
                    width + maxOf(48.dp, availableKeyWidth * key.weight / totalWeight)
                } + spacing * (row.size - 1)
                val rowScrollState = key(rowIndex) { rememberScrollState() }
                Row(
                    Modifier.widthIn(min = minRowWidth).horizontalScroll(rowScrollState),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    row.forEach { key ->
                        val selected = key.modifier?.let { modifiers and it != 0 } ?: false
                        val keyWidth = maxOf(48.dp, availableKeyWidth * key.weight / totalWeight)
                        KeyboardButton(
                            key = key,
                            selected = selected,
                            compact = compact,
                            modifier = Modifier.width(keyWidth).height(rowHeight),
                            onClick = {
                                key.modifier?.let { modifiers = modifiers xor it }
                                    ?: key.command?.let { viewModel.key(it, modifiers) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardButton(
    key: KeyboardKey,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val buttonModifier = modifier.semantics {
        contentDescription = key.contentDescription
        if (key.modifier != null) this.selected = selected
    }
    val content: @Composable () -> Unit = {
        Text(
            key.label,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
    if (selected) {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            content = { content() }
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
            content = { content() }
        )
    }
}

private fun statusText(state: ConnectionState): String = when (state) {
    ConnectionState.Unsupported -> "Bluetooth is not supported on this device."
    ConnectionState.BluetoothDisabled -> "Turn Bluetooth on, then register the HID device."
    ConnectionState.PermissionRequired -> "Bluetooth permission is required."
    ConnectionState.Ready -> "Ready to register as a Bluetooth keyboard and mouse."
    ConnectionState.Registering -> "Registering Bluetooth HID device…"
    is ConnectionState.Registered -> state.rememberedDeviceName?.let {
        "HID registered. Ready to reconnect to $it."
    } ?: "HID registered. Pair from the PC Bluetooth settings."
    is ConnectionState.Reconnecting -> "Reconnecting to ${state.deviceName}…"
    is ConnectionState.Disconnecting -> "Disconnecting from ${state.deviceName}…"
    is ConnectionState.ReconnectFailed -> state.message
    is ConnectionState.Connected -> state.errorMessage ?: "Connected to ${state.deviceName}."
    is ConnectionState.Error -> state.message
}
