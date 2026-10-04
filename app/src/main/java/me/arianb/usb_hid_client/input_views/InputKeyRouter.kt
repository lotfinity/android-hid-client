package me.arianb.usb_hid_client.input_views

import me.arianb.usb_hid_client.hid_utils.KeyCodeTranslation

/** The IME and extra-key strip share sticky modifier selection, independent of saved deck actions. */
class InputKeyRouter(
    private val selectedModifiers: () -> Int,
    private val send: (Byte, Byte) -> Unit,
) {
    fun sendKey(modifier: Byte, key: Byte) {
        send((modifier.toInt() or selectedModifiers()).toByte(), key)
    }

    fun sendText(text: CharSequence): Boolean {
        // Validate before enqueueing any characters so unsupported text cannot partially execute a shortcut.
        val keys = text.map { KeyCodeTranslation.keyCharToScanCodes(it) ?: return false }
        keys.forEach { (modifier, key) -> sendKey(modifier, key) }
        return true
    }
}
