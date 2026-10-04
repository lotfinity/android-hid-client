package me.arianb.usb_hid_client.report_senders.pointer_device_senders

import me.arianb.usb_hid_client.hid_utils.TouchpadDevicePath
import me.arianb.usb_hid_client.report_senders.ReportSender

sealed class PointerDeviceSender(
    touchpadDevicePath: TouchpadDevicePath,
    transport: ((ByteArray) -> Unit)? = null,
) : ReportSender(
    touchpadDevicePath, transport
) {
    abstract fun send(contactID: Byte, tipSwitch: Boolean, x: Short, y: Short, scanTime: UShort, contactCount: Byte)
}
