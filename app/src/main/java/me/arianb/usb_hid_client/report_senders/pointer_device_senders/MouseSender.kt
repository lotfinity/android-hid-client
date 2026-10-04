package me.arianb.usb_hid_client.report_senders.pointer_device_senders

import me.arianb.usb_hid_client.hid_utils.TouchpadDevicePath
class MouseSender(
    mouseDevicePath: TouchpadDevicePath,
    transport: ((ByteArray) -> Unit)? = null,
) : PointerDeviceSender(
    mouseDevicePath, transport
) {
    private data class Coordinates<T>(val x: T, val y: T)

    private var previousCoordinates: Coordinates<Short>? = null
    private var touchStartCoordinates: Coordinates<Short>? = null
    private var touchMoved = false
    private var touchContactCount: Byte = 1
    private var heldButtons: Byte = 0

    override fun send(contactID: Byte, tipSwitch: Boolean, x: Short, y: Short, scanTime: UShort, contactCount: Byte) {
        val currentCoordinates = Coordinates(x, y)

        if (tipSwitch && touchStartCoordinates == null) {
            touchStartCoordinates = currentCoordinates
            previousCoordinates = currentCoordinates
            touchMoved = false
            touchContactCount = contactCount
            return
        }

        // This uses relative movements, so if the "previous" coordinates haven't been set (var is null), then we have
        // (relatively) moved 0 distance. So in that case, just use (0,0) as relative distance. Otherwise, calculate
        // the difference and use it.
        val relativeCoordinates: Coordinates<Short> = previousCoordinates?.let {
            Coordinates((x - it.x).toShort(), (y - it.y).toShort())
        } ?: Coordinates(0, 0)

        if (tipSwitch) {
            previousCoordinates = currentCoordinates
            if (kotlin.math.abs(relativeCoordinates.x.toInt()) > TAP_MOVEMENT_THRESHOLD ||
                kotlin.math.abs(relativeCoordinates.y.toInt()) > TAP_MOVEMENT_THRESHOLD
            ) {
                touchMoved = true
            }
            if (relativeCoordinates.x.toInt() != 0 || relativeCoordinates.y.toInt() != 0) {
                moveBy(relativeCoordinates.x.toInt(), relativeCoordinates.y.toInt())
            }
            return
        }

        if (!touchMoved && touchStartCoordinates != null) {
            click(if (touchContactCount > 1) RIGHT_BUTTON else LEFT_BUTTON)
        }

        previousCoordinates = null
        touchStartCoordinates = null
        touchMoved = false
        touchContactCount = 1
    }

    fun moveBy(x: Int, y: Int) {
        addChunkedPointerReports(x, y, 0)
    }

    fun scroll(wheel: Int) {
        addChunkedPointerReports(0, 0, wheel)
    }

    fun leftDown() {
        setButton(LEFT_BUTTON, true)
    }

    fun leftUp() {
        setButton(LEFT_BUTTON, false)
    }

    fun rightDown() {
        setButton(RIGHT_BUTTON, true)
    }

    fun rightUp() {
        setButton(RIGHT_BUTTON, false)
    }

    fun middleDown() {
        setButton(MIDDLE_BUTTON, true)
    }

    fun middleUp() {
        setButton(MIDDLE_BUTTON, false)
    }

    fun clickLeft() {
        click(LEFT_BUTTON)
    }

    fun clickRight() {
        click(RIGHT_BUTTON)
    }

    fun clickMiddle() {
        click(MIDDLE_BUTTON)
    }

    private fun click(button: Byte) {
        super.addReportToChannel(mouseReport((heldButtons.toInt() or button.toInt()).toByte(), 0, 0))
        super.addReportToChannel(mouseReport(heldButtons, 0, 0))
    }

    private fun setButton(button: Byte, pressed: Boolean) {
        heldButtons = if (pressed) {
            (heldButtons.toInt() or button.toInt()).toByte()
        } else {
            (heldButtons.toInt() and button.toInt().inv()).toByte()
        }
        super.addReportToChannel(mouseReport(heldButtons, 0, 0))
    }

    private fun addChunkedPointerReports(x: Int, y: Int, wheel: Int) {
        var remainingX = x
        var remainingY = y
        var remainingWheel = wheel
        while (remainingX != 0 || remainingY != 0 || remainingWheel != 0) {
            val reportX = remainingX.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())
            val reportY = remainingY.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())
            val reportWheel = remainingWheel.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())
            super.addReportToChannel(mouseReport(heldButtons, reportX.toByte(), reportY.toByte(), reportWheel.toByte()))
            remainingX -= reportX
            remainingY -= reportY
            remainingWheel -= reportWheel
        }
    }

    companion object {
        private const val TAP_MOVEMENT_THRESHOLD = 8
        private const val LEFT_BUTTON: Byte = 0x01
        private const val RIGHT_BUTTON: Byte = 0x02
        private const val MIDDLE_BUTTON: Byte = 0x04

        internal fun mouseReport(buttons: Byte, x: Byte, y: Byte, wheel: Byte = 0): ByteArray =
            byteArrayOf(buttons, x, y, wheel)
    }
}
