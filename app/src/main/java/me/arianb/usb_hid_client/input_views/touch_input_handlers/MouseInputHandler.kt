package me.arianb.usb_hid_client.input_views.touch_input_handlers

import android.os.Build
import android.view.MotionEvent
import me.arianb.usb_hid_client.report_senders.pointer_device_senders.MouseSender
import me.arianb.usb_hid_client.report_senders.pointer_device_senders.PointerDeviceSender

class MouseInputHandler(
    private val mouseSender: MouseSender
) : PointerDeviceInputHandler() {
    private data class Coordinates<T>(val x: T, val y: T)

    private var previousCoordinates: Coordinates<Short>? = null
    private var currentTouchpadButtonState: PointerDeviceSender.TouchpadButtonState =
        PointerDeviceSender.TouchpadButtonState(
            isLeftButtonPressed = false,
            isRightButtonPressed = false,
        )

    fun handleTouchEvent(motionEvent: MotionEvent): Boolean {
        val (pointerID, pointerX, pointerY) = motionEvent.let {
            val pointerIndex = it.actionIndex

            val pointerID = it.getPointerId(pointerIndex)

            val (rawPointerX, rawPointerY) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Pair(motionEvent.getRawX(pointerIndex), motionEvent.getRawY(pointerIndex))
            } else {
                Pair(motionEvent.getX(pointerIndex), motionEvent.getY(pointerIndex))
            }

            Triple<Int, Int, Int>(pointerID, rawPointerX.toInt(), rawPointerY.toInt())
        }

        // FIXME:
        //  this just ignores any pointer that was put down after the first one. The purpose of this is to allow
        //  a user to move the "mouse" while holding down a button. However, due to the currently basic logic, it only
        //  allows this if a user puts their finger down on the touchpad contact area before hitting the button.
        if (pointerID != 0) {
            return false
        }

        this.sendAbsoluteMouseMovement(pointerX.toShort(), pointerY.toShort(), currentTouchpadButtonState)

        return true
    }

    fun sendAbsoluteMouseMovement(
        absoluteX: Short,
        absoluteY: Short,
        touchpadButtonState: PointerDeviceSender.TouchpadButtonState? = null,
    ) {
        // This uses relative movements, so if the "previous" coordinates haven't been set (var is null), then we have
        // (relatively) moved 0 distance. So in that case, just use (0,0) as relative distance. Otherwise, calculate
        // the difference and use it.
        val difference: Coordinates<Short> = previousCoordinates?.let {
            Coordinates((absoluteX - it.x).toShort(), (absoluteY - it.y).toShort())
        } ?: Coordinates(0, 0)

        previousCoordinates = Coordinates(absoluteX, absoluteY)

        if (touchpadButtonState != null) {
            currentTouchpadButtonState = touchpadButtonState
        }

        return mouseSender.sendMouseReport(difference.x.toByte(), difference.y.toByte(), currentTouchpadButtonState)
    }

    fun sendRelativeMouseMovement(
        relativeX: Byte,
        relativeY: Byte,
        touchpadButtonState: PointerDeviceSender.TouchpadButtonState?
    ) {
        val updatedCoordinates: Coordinates<Short> = previousCoordinates?.let {
            val x: Short = (it.x + relativeX).toShort()
            val y: Short = (it.y + relativeY).toShort()

            Coordinates(x, y)
        } ?: Coordinates(0, 0)

        previousCoordinates = updatedCoordinates

        if (touchpadButtonState != null) {
            currentTouchpadButtonState = touchpadButtonState
        }

        return mouseSender.sendMouseReport(relativeX, relativeY, currentTouchpadButtonState)
    }

    // Send new button state with 0 relative mouse movement
    fun sendButtonStateUpdate(touchpadButtonState: PointerDeviceSender.TouchpadButtonState) =
        this.sendRelativeMouseMovement(0, 0, touchpadButtonState)
}