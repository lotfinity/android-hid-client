package me.arianb.usb_hid_client

import me.arianb.usb_hid_client.input_views.InputKeyRouter
import org.junit.Assert.*
import org.junit.Test

class InputKeyRouterTest {
    @Test fun selectedCtrlAppliesToImeLettersAndSpecialKeysAndCanBeCleared() {
        var selected = 1
        val sent = mutableListOf<Pair<Byte, Byte>>()
        val router = InputKeyRouter({ selected }) { modifier, key -> sent += modifier to key }
        assertTrue(router.sendText("ac"))
        router.sendKey(0, 0x4f)
        selected = 0
        assertTrue(router.sendText("a"))
        assertEquals(listOf(1.toByte() to 4.toByte(), 1.toByte() to 6.toByte(),
            1.toByte() to 0x4f.toByte(), 0.toByte() to 4.toByte()), sent)
    }

    @Test fun selectedModifiersCombineWithImeShiftWithoutLosingEither() {
        val sent = mutableListOf<Pair<Byte, Byte>>()
        val router = InputKeyRouter({ 1 or 4 }) { modifier, key -> sent += modifier to key }
        router.sendKey(2, 4)
        assertTrue(router.sendText("A"))
        assertEquals(listOf(7.toByte() to 4.toByte(), 7.toByte() to 4.toByte()), sent)
    }

    @Test fun unsupportedImeTextDoesNotSendAnIncompleteShortcut() {
        val sent = mutableListOf<Pair<Byte, Byte>>()
        val router = InputKeyRouter({ 1 }) { modifier, key -> sent += modifier to key }
        assertFalse(router.sendText("a😃"))
        assertTrue(sent.isEmpty())
    }
}
