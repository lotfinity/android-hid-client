package me.arianb.usb_hid_client

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.arianb.usb_hid_client.hid_utils.KeyboardDevicePath
import me.arianb.usb_hid_client.hid_utils.TouchpadDevicePath
import me.arianb.usb_hid_client.report_senders.KeySender
import me.arianb.usb_hid_client.report_senders.pointer_device_senders.MouseSender
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothTransportTest {
    @Test fun keyboardTransportSendsModifiedKeyAndReleaseWithoutOpeningUsbNode() {
        val reports = mutableListOf<ByteArray>()
        val sender = KeySender(KeyboardDevicePath("/does-not-exist")) { reports.add(it.clone()) }
        sender.sendReport(byteArrayOf(1, 0, 6, 0, 0, 0, 0, 0))
        assertEquals(2, reports.size)
        assertArrayEquals(byteArrayOf(1, 0, 6, 0, 0, 0, 0, 0), reports[0])
        assertArrayEquals(ByteArray(8), reports[1])
    }

    @Test fun mouseTransportPreservesDragMovementAndScrollAcrossByteBoundaries() = runBlocking {
        val reports = Channel<ByteArray>(Channel.UNLIMITED)
        val sender = MouseSender(TouchpadDevicePath("/does-not-exist")) { reports.trySend(it.clone()) }
        val job = launch { sender.start({}, { throw it }) }
        try {
            sender.leftDown()
            sender.moveBy(130, -130)
            sender.scroll(256)
            sender.leftUp()
            val received = withTimeout(5000) { List(7) { reports.receive() } }
            assertEquals(130, received.sumOf { it[1].toInt() })
            assertEquals(-130, received.sumOf { it[2].toInt() })
            assertEquals(256, received.sumOf { it[3].toInt() })
            received.dropLast(1).forEach { assertEquals(1, it[0].toInt()) }
            assertArrayEquals(ByteArray(4), received.last())
        } finally { job.cancelAndJoin() }
    }
}
