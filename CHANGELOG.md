# Changelog

## 2026-10-04 - Bluetooth HID

- Added classic Bluetooth HID keyboard and relative mouse support through Android's HID device API.
- Reused the existing keyboard, shortcuts, mouse buttons, touchpad gestures, and scrolling through selectable report transports.
- Added Nearby devices permission handling, temporary discoverability, paired host selection, connection status, and USB/Bluetooth switching.
- Remembered the selected transport and released Bluetooth keys/buttons when pausing or closing the connection.
- Added a separate debug installation option for testing without the original app signing key.

Validation:

- `./gradlew --no-daemon assembleDebug` passed.
- `./gradlew --no-daemon --max-workers=2 -PisolatedBluetoothTest=true assembleDebug testDebugUnitTest` passed; 5 tests, no failures.
- Installed the Bluetooth test app alongside the original companion on the user's Note20 Ultra.
- User confirmed Bluetooth input worked; Windows enumerated the phone, Bluetooth HID device, keyboard, and mouse with healthy device status.

## 2026-07-19 - C2Q Companion Integration

- Reworked the C2Q fork to use the `c2q-nethunter-companion` Magisk module for USB HID activation and restore instead of editing USB ConfigFS directly from the app.
- Added a C2Q control surface for HID activation, restore, status refresh, and shortcut controls.
- Updated keyboard and mouse report handling for the C2Q keyboard plus relative mouse profile exposed by the companion module.
- Added touchpad gestures for relative pointer movement, two-finger scroll, pinch zoom shortcuts, two-finger right click, three-finger middle click, and long-press drag.
- Added persistent mouse controls with `L`, `M`, `R`, and `Hold L` / `Release L` buttons so drag and swipe actions can be performed like KDE Connect.
- Removed precision-touchpad and direct USB gadget settings that do not apply to the C2Q module-backed profile.
- Built and installed debug APK `c2q-hid-client-v3.0.1-c2q5-mouse-buttons-debug.apk` for device testing.

Validation:

- `./gradlew --no-daemon assembleDebug`
- `adb -s 192.168.1.107:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
