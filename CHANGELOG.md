# Changelog

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
