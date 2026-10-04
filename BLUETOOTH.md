# Bluetooth keyboard and mouse

The C2Q companion supports classic Bluetooth HID on Android 9 and newer. USB continues to use the C2Q Magisk module; Bluetooth uses Android's public HID device API and needs no root access.

1. Tap **Use Bluetooth instead** on the activation dialog, or **BT** on the bottom bar.
2. Tap **Enable Bluetooth HID**, allow Nearby devices, and enable Bluetooth if requested.
3. Tap **Pair a new computer**, allow temporary visibility, and add the phone from the computer's Bluetooth settings. Keep the app open on the phone.
4. If not connected automatically, refresh paired devices and select the computer.
5. Tap **Done** to use the existing touchpad, mouse buttons, keyboard and shortcuts. **USB** switches back to the existing USB controls.

The host receives a standard keyboard and relative mouse with scrolling. This is classic HID, not BLE HOGP; a classic Bluetooth host can use it without BLE support. Consumer/media keys remain unsupported, as in the existing C2Q USB profile. Boot/BIOS protocol is not implemented; use the connection within the host operating system.

Android may unregister a HID app when it leaves the foreground. The app shows that state and offers Enable again. Closing the controller releases keys and mouse buttons, disconnects, unregisters, and closes the profile proxy. Only one HID device app can register at a time.

## Build

Normal build (same package, requires the existing signing key to update an installed copy):

```sh
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

For a separate test installation when the original signing key is unavailable:

```sh
./gradlew --no-daemon -PisolatedBluetoothTest=true assembleDebug testDebugUnitTest
```

The latter uses package `dev.lofa.c2q_hid_client.bluetooth_test`, label `c2q HID Client (Bluetooth test)`, and separate preferences. It does not replace the existing USB app.
