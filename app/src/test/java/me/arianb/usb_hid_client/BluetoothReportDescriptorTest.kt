package me.arianb.usb_hid_client

import me.arianb.usb_hid_client.hid_utils.BluetoothHidController
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothReportDescriptorTest {
    @Test fun descriptorMatchesExistingKeyboardAndMousePayloads() {
        // Parse HID short items to verify the actual wire sizes, independently of the sender code.
        val descriptor = BluetoothHidController.DESCRIPTOR
        val inputBits = mutableMapOf<Int, Int>()
        val outputBits = mutableMapOf<Int, Int>()
        var reportId = 0
        var size = 0
        var count = 0
        var offset = 0
        var collectionDepth = 0
        while (offset < descriptor.size) {
            val prefix = descriptor[offset++].toInt() and 255
            val length = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            var value = 0
            repeat(length) { index -> value = value or ((descriptor[offset++].toInt() and 255) shl (8 * index)) }
            when (prefix and 252) {
                0x74 -> size = value
                0x94 -> count = value
                0x84 -> reportId = value
                0x80 -> inputBits[reportId] = inputBits.getOrDefault(reportId, 0) + size * count
                0x90 -> outputBits[reportId] = outputBits.getOrDefault(reportId, 0) + size * count
                0xA0 -> collectionDepth++
                0xC0 -> collectionDepth--
            }
        }
        assertEquals(0, collectionDepth)
        assertEquals(mapOf(1 to 64, 2 to 32), inputBits)
        assertEquals(mapOf(1 to 8), outputBits)
    }
}
