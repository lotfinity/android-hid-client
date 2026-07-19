package me.arianb.usb_hid_client.report_senders

import me.arianb.usb_hid_client.hid_utils.KeyboardDevicePath
import timber.log.Timber

class KeySender(
    keyboardDevicePath: KeyboardDevicePath
) : ReportSender(
    keyboardDevicePath
) {
    fun addStandardKey(modifier: Byte, key: Byte) {
        super.addReportToChannel(bootKeyboardReport(modifier, key))
    }

    fun addMediaKey(key: Byte) {
        Timber.w(
            "Ignoring consumer key 0x%02x: the safe c2q keyboard+mouse profile has no consumer HID endpoint",
            key,
        )
    }

    // Every time we send a report, we only send the "key-down" event. This method will automatically send the "key-up"
    // event right afterward
    override fun sendReport(report: ByteArray) {
        // Send "key-down" report
        writeBytes(report)

        // Send the eight-byte boot-keyboard release report.
        val releaseReport = ByteArray(report.size)
        writeBytes(releaseReport)
    }

    companion object {
        internal fun bootKeyboardReport(modifier: Byte, key: Byte): ByteArray =
            byteArrayOf(modifier, 0, key, 0, 0, 0, 0, 0)
    }
}
