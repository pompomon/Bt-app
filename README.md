# Bt-app

Bt-app turns an Android 9 (API 28) or newer phone into a standard Bluetooth HID
keyboard, relative mouse, and media remote. It uses Android's native
`BluetoothHidDevice` API; the paired computer needs no companion application,
server, Wi-Fi connection, or Web Bluetooth support.

## Status and limitations

This is an MVP for devices whose manufacturers expose the Android HID Device
profile. It provides individual HID key events, common navigation keys, F1–F12,
standard media controls, and a relative touchpad. Keyboard layout and text
composition are interpreted by the host operating system, so typing is
deliberately implemented as physical key events rather than Android text input.
Holding a keyboard key leaves its HID report pressed so the host can apply its
normal key-repeat behavior; modifiers apply to the next key. HID availability
varies by Android device and vendor. Hardware pairing and reconnection are not
exercised in CI.

The touchpad supports one-finger tap-to-left-click and drag movement, plus
two-finger tap-to-right-click and vertical scrolling. Movement is split into
signed 8-bit HID reports. All active reports are released on disconnect, and
gesture cancellation releases held keyboard and pointer inputs.

## Build and test

Install Android SDK Platform 35 and JDK 17, then run:

```bash
./gradlew --no-daemon --no-build-cache --no-configuration-cache test
./gradlew --no-daemon --no-build-cache --no-configuration-cache lint assembleDebug
```

CI runs two Linux jobs: a validation job that executes the Gradle test and lint
checks, and a build job that assembles the debug APK and uploads it as the
`bt-app-debug` artifact. Gradle and GitHub Actions caching are intentionally
disabled because this repository has a strict Actions storage budget.

## Pairing and use

1. Install and open the app, grant the Android 12+ Nearby devices/Bluetooth
   permission, and turn Bluetooth on.
2. Tap **Pair a device**.
3. Allow the system discoverability prompt. On Windows, open **Settings →
   Bluetooth & devices**; on Linux use the system Bluetooth settings; on macOS
   use **System Settings → Bluetooth**. Select the phone's model or configured
   Bluetooth device name; pairing screens may not show the app's HID service
   name (“Bt-app keyboard, mouse, and media controls”).
4. Return to the app after the host connects, then use Touchpad, Keyboard, or
   Media.

The app stays in landscape in either direction while it is open. The connected
screen keeps the connection controls in a compact header. The touchpad expands
to the available space with click and scroll controls beside it, while the
keyboard arranges every key in six rows and scrolls on smaller viewports to
preserve accessible key targets. The Media screen provides play/pause, stop,
previous/next track, mute, and volume controls.

The header shows the current connection state with text and a color-independent
marker: a steady marker means connected or idle, a spinner means the app is
checking or changing the connection, and a red marker reports a failure. After
the app returns from the background, it checks Android's HID connection state
before enabling keyboard, touchpad, or media input. The selected input remains
visible but dimmed while that check or an automatic reconnect is in progress.

The app remembers up to ten computers that successfully established an HID
connection. The selected computer reconnects automatically on later launches,
with up to three delayed retries after an unexpected disconnect. When multiple
computers are remembered, use the device selector to switch; the current host is
disconnected before the selected host is contacted. Automatic reconnect runs
only while the app is open in the foreground; there is no background service or
persistent notification.

Tap **Disconnect** to stop reconnecting for the current app session without
forgetting the computer. Use **Reconnect** to try it again, **Pair another
device** to make the phone discoverable for a different computer, or **Forget
device** to remove the selected computer from the app. Removing it does not
unpair it in Android settings. If a saved computer is no longer bonded, the app
removes only that stale entry.

After upgrading from a version without media controls, an existing computer may
cache the old HID descriptor. Remove the phone from that computer's Bluetooth
settings and pair it again if the Media controls do not work.

The indicator reflects the Bluetooth HID state reported by Android. The HID
protocol does not provide this app with confirmation that the computer processed
an individual key or pointer report, so a connected indicator cannot guarantee
delivery of each input event.

If it cannot register, verify Bluetooth is enabled, re-grant Nearby devices
permission, and check whether the phone supports the HID Device profile. Remove
the pairing at either end before attempting a fresh pairing. The app does not
request location because it does not perform Bluetooth discovery.

## Architecture

```text
Android UI
   |
ViewModel / state
   |
Input mappers and gesture detector
   |
Bluetooth HID controller
   |
Bluetooth HID host on PC
```

## Privacy and security

The app collects no telemetry and contacts no network service. Keyboard,
pointer, and media reports are transmitted only to the connected Bluetooth host.
Pair only with trusted computers: typed key events are received by that host.
Logs contain Bluetooth operational failures but never typed text or input
contents.

`BLUETOOTH_CONNECT` is used to register and communicate with the HID profile,
and `BLUETOOTH_ADVERTISE` is used for the system discoverability prompt. The app
does not request scan or location permission. No desktop service, background
service, or PC-side component is used. Remembered Bluetooth addresses are stored
only in app-private preferences and are excluded from Android backup and device
transfer.