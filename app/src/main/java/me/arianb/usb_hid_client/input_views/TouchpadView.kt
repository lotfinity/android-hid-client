package me.arianb.usb_hid_client.input_views

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import me.arianb.usb_hid_client.MainViewModel
import me.arianb.usb_hid_client.R
import me.arianb.usb_hid_client.report_senders.pointer_device_senders.MouseSender
import me.arianb.usb_hid_client.report_senders.pointer_device_senders.PointerDeviceSender
import me.arianb.usb_hid_client.ui.utils.getColorByTheme
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

// LEGACY: migrate this to Compose

// From my understanding of this lint warning, I don't think it applies here.
@SuppressLint("ClickableViewAccessibility")
class TouchpadView : AppCompatTextView {
    private var currentScanTime: UShort = getScanTime()
    private var mouseSender: MouseSender? = null
    private var onZoom: ((zoomIn: Boolean) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastCentroidY = 0f
    private var lastPinchDistance = 0f
    private var scrollRemainder = 0f
    private var pinchRemainder = 0f
    private var maxPointerCount = 0
    private var moved = false
    private var dragLocked = false
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    fun setTouchListeners(
        touchpadSender: PointerDeviceSender,
        onZoom: (zoomIn: Boolean) -> Unit = {},
    ) {
        if (touchpadSender is MouseSender) {
            setBasicMouseTouchListeners(touchpadSender, onZoom)
            return
        }

        setOnTouchListener { _: View?, motionEvent: MotionEvent ->
            val (pointerID, pointerX, pointerY) = getPointerTriple(motionEvent, pointerIndex = motionEvent.actionIndex)

            // Scan time is reset when pointer 0 is sent
            if (pointerID == 0) {
                currentScanTime = getScanTime()
            }

            val pointerCount = motionEvent.pointerCount
            when (val action = motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Timber.v("Action Down")
                    touchpadSender.send(pointerID, true, pointerX, pointerY, currentScanTime, pointerCount)
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    Timber.v("Action Pointer Down")
                    touchpadSender.send(pointerID, true, pointerX, pointerY, currentScanTime, pointerCount)
                }

                MotionEvent.ACTION_MOVE -> {
                    Timber.v("Action Move")
                    for (index in 0..<pointerCount) {
                        val (thisID, thisX, thisY) = getPointerTriple(motionEvent, index)

                        touchpadSender.send(thisID, true, thisX, thisY, currentScanTime, pointerCount)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    Timber.v("Action Up")
                    touchpadSender.send(pointerID, false, pointerX, pointerY, currentScanTime, pointerCount)
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    Timber.v("Action Pointer Up")
                    touchpadSender.send(pointerID, false, pointerX, pointerY, currentScanTime, pointerCount)
                }

                MotionEvent.ACTION_CANCEL -> {
                    Timber.v("Action Cancel")
                    touchpadSender.send(pointerID, false, pointerX, pointerY, currentScanTime, pointerCount)
                }

                else -> {
                    Timber.w("UNHANDLED ACTION CONSTANT: %s", action)
                }
            }
            true
        }
    }

    private fun setBasicMouseTouchListeners(
        touchpadSender: MouseSender,
        onZoom: (zoomIn: Boolean) -> Unit,
    ) {
        mouseSender = touchpadSender
        this.onZoom = onZoom
        setOnTouchListener { _: View?, motionEvent: MotionEvent ->
            handleBasicMouseEvent(motionEvent)
            true
        }
    }

    private fun handleBasicMouseEvent(event: MotionEvent) {
        val sender = mouseSender ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPendingLongPress()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                longPressTriggered = false
                maxPointerCount = 1
                if (!dragLocked) {
                    scheduleLongPress(sender)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPendingLongPress()
                maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
                if (event.pointerCount >= 2) {
                    lastCentroidY = centroidY(event)
                    lastPinchDistance = pinchDistance(event)
                    scrollRemainder = 0f
                    pinchRemainder = 0f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
                if (event.pointerCount >= 2) {
                    handleMultiTouchMove(event, sender)
                } else {
                    handleSingleTouchMove(event, sender)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                if (maxPointerCount >= 3 && !moved) {
                    sender.clickMiddle()
                } else if (maxPointerCount == 2 && !moved) {
                    sender.clickRight()
                } else if (maxPointerCount == 1 && !moved && !longPressTriggered) {
                    if (dragLocked) {
                        sender.leftUp()
                        dragLocked = false
                    } else {
                        sender.clickLeft()
                    }
                }
                maxPointerCount = 0
                longPressTriggered = false
                moved = false
            }

            MotionEvent.ACTION_POINTER_UP -> {
                maxPointerCount = maxOf(maxPointerCount, event.pointerCount)
                if (event.pointerCount - 1 < 2) {
                    lastX = event.getX(0)
                    lastY = event.getY(0)
                }
            }

            else -> Timber.w("UNHANDLED ACTION CONSTANT: %s", event.actionMasked)
        }
    }

    private fun handleSingleTouchMove(event: MotionEvent, sender: MouseSender) {
        val dx = event.x - lastX
        val dy = event.y - lastY
        lastX = event.x
        lastY = event.y
        if (abs(event.x - downX) > TAP_SLOP || abs(event.y - downY) > TAP_SLOP) {
            moved = true
            cancelPendingLongPress()
        }
        val scaledX = (dx * POINTER_SENSITIVITY).roundToInt()
        val scaledY = (dy * POINTER_SENSITIVITY).roundToInt()
        if (scaledX != 0 || scaledY != 0) {
            sender.moveBy(scaledX, scaledY)
        }
    }

    private fun handleMultiTouchMove(event: MotionEvent, sender: MouseSender) {
        val currentCentroidY = centroidY(event)
        val currentPinchDistance = pinchDistance(event)
        val dy = currentCentroidY - lastCentroidY
        val distanceDelta = currentPinchDistance - lastPinchDistance
        lastCentroidY = currentCentroidY
        lastPinchDistance = currentPinchDistance

        if (abs(dy) > MULTI_TOUCH_MOVE_SLOP || abs(distanceDelta) > PINCH_MOVE_SLOP) {
            moved = true
        }

        if (abs(distanceDelta) > PINCH_MOVE_SLOP && abs(distanceDelta) > abs(dy) * 1.4f) {
            pinchRemainder += distanceDelta / PINCH_STEP_PIXELS
            val zoomSteps = pinchRemainder.toInt()
            if (zoomSteps != 0) {
                repeat(abs(zoomSteps)) {
                    onZoom?.invoke(zoomSteps > 0)
                }
                pinchRemainder -= zoomSteps
            }
            return
        }

        scrollRemainder += dy / SCROLL_STEP_PIXELS
        val wheelSteps = scrollRemainder.toInt()
        if (wheelSteps != 0) {
            sender.scroll(-wheelSteps)
            scrollRemainder -= wheelSteps
        }
    }

    private fun scheduleLongPress(sender: MouseSender) {
        val runnable = Runnable {
            if (!moved && maxPointerCount == 1 && !dragLocked) {
                sender.leftDown()
                dragLocked = true
                longPressTriggered = true
            }
        }
        longPressRunnable = runnable
        postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelPendingLongPress() {
        longPressRunnable?.let { removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun centroidY(event: MotionEvent): Float {
        var total = 0f
        for (index in 0..<event.pointerCount) {
            total += event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun pinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    companion object {
        private const val POINTER_SENSITIVITY = 1.2f
        private const val TAP_SLOP = 18f
        private const val MULTI_TOUCH_MOVE_SLOP = 10f
        private const val PINCH_MOVE_SLOP = 14f
        private const val SCROLL_STEP_PIXELS = 36f
        private const val PINCH_STEP_PIXELS = 70f
    }
}

private fun getPointerTriple(motionEvent: MotionEvent, pointerIndex: Int): Triple<Int, Int, Int> {
    val pointerID = motionEvent.getPointerId(pointerIndex)

    val (rawPointerX, rawPointerY) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Pair(motionEvent.getRawX(pointerIndex), motionEvent.getRawY(pointerIndex))
    } else {
        Pair(motionEvent.getX(pointerIndex), motionEvent.getY(pointerIndex))
    }

    // NOTE: MotionEvent has a bunch of properties (and properties of those properties) which are platform types.
    //       I'm writing this note to be explicit about the fact that the following code should be treated cautiously so
    //       as to not cause NPEs.
    // --- start of unsafe code ---
    val device: InputDevice? = motionEvent.device

    // If null, just use some hardcoded safe-ish values
    val xMax: Float = device?.getMotionRange(MotionEvent.AXIS_X)?.max ?: 1500f
    val yMax: Float = device?.getMotionRange(MotionEvent.AXIS_Y)?.max ?: 3000f
    // --- end of unsafe code ---

    // Get device rotation because if the device is suddenly a wide rectangle instead of a tall rectangle, then the
    // math changes.
    val isRotated = motionEvent.orientation != 0f

    val (pointerX, pointerY) = adjustRange(
        point = Pair(rawPointerX.toInt(), rawPointerY.toInt()),
        max = Pair(xMax, yMax),
        isRotated
    )

    return Triple(pointerID, pointerX, pointerY)
}

// "Stretches" the values of the points to use up the entire logical range.
private fun adjustRange(point: Pair<Int, Int>, max: Pair<Float, Float>, isRotated: Boolean): Pair<Int, Int> {
    Timber.d("DEVICE COORDINATE MAX = (%f, %f)", max.first, max.second)

    val (logicalMaxX, logicalMaxY) = if (isRotated) {
        // This works, but I'm not sure if it's okay to just be sending values higher than the logical maximum
        Pair(5000, 2500)
    } else {
        Pair(2500, 5000)
    }

    val (pointerMaxX, pointerMaxY) = if (isRotated) {
        Pair(max.second, max.first)
    } else {
        max
    }

    val xRatio: Float = logicalMaxX / pointerMaxX
    val yRatio: Float = logicalMaxY / pointerMaxY

    val adjustedX = (point.first * xRatio).toInt()
    val adjustedY = (point.second * yRatio).toInt()

    // This will probably never actually be necessary, but might as well do it just in case.
    val finalX = adjustedX.coerceIn(0, logicalMaxX)
    val finalY = adjustedY.coerceIn(0, logicalMaxY)

    return Pair(finalX, finalY)
}

fun getScanTime(): UShort {
    // Convert nanoseconds to microseconds
    val microTime = System.nanoTime() / 1000

    // Convert microseconds to 100s of microseconds
    val hundredMicroTime = microTime / 100

    return hundredMicroTime.toUShort()
}

/**
 * Helper function to convert between types.
 */
fun PointerDeviceSender.send(
    pointerID: Int,
    tipSwitch: Boolean,
    x: Int,
    y: Int,
    currentScanTime: UShort,
    pointerCount: Int
) = send(
    pointerID.toByte(),
    tipSwitch,
    x.toShort(),
    y.toShort(),
    currentScanTime,
    pointerCount.toByte()
)

@Composable
fun Touchpad(
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 220.dp, max = 360.dp),
    mainViewModel: MainViewModel = viewModel(),
    showBorder: Boolean = true,
) {
    val touchpadText = stringResource(R.string.touchpad_label)
    val touchpadSender by mainViewModel.touchpadSender.collectAsState()

    val textColor = getColorByTheme()

    Surface(
        modifier = modifier,
//            .pointerInput(Unit) {
//
//            }
        color = MaterialTheme.colorScheme.background,
        border = if (showBorder) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        // For when I want to Compose-ify this
//        Text(
//            text = stringResource(R.string.touchpad_label),
//            modifier = Modifier.wrapContentHeight(Alignment.CenterVertically),
//            textAlign = TextAlign.Center
//        )
        AndroidView(
            factory = { context ->
                TouchpadView(context).apply {
                    text = touchpadText
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTouchListeners(
                        touchpadSender,
                        onZoom = { zoomIn ->
                            mainViewModel.addStandardKey(
                                0x01.toByte(),
                                (if (zoomIn) 0x2e else 0x2d).toByte(),
                            )
                        },
                    )
                }
            },
            update = {
                if (textColor != null) {
                    it.setTextColor(textColor)
                }
            }
        )
    }
}

@Composable
fun MouseButtonBar(
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel = viewModel(),
) {
    val touchpadSender by mainViewModel.touchpadSender.collectAsState()
    val mouseSender = touchpadSender as? MouseSender
    var leftLocked by remember { mutableStateOf(false) }
    DisposableEffect(mouseSender) {
        onDispose { mouseSender?.leftUp(); mouseSender?.middleUp(); mouseSender?.rightUp() }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MouseHoldButton(
                label = "Left",
                modifier = Modifier.weight(1f),
                enabled = mouseSender != null && !leftLocked,
                onDown = { mouseSender?.leftDown() },
                onUp = { mouseSender?.leftUp() },
            )
            OutlinedButton(
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .weight(1.25f)
                    .sizeIn(minHeight = 56.dp),
                enabled = mouseSender != null,
                onClick = {
                    mouseSender ?: return@OutlinedButton
                    if (leftLocked) {
                        mouseSender.leftUp()
                    } else {
                        mouseSender.leftDown()
                    }
                    leftLocked = !leftLocked
                },
            ) {
                Text(if (leftLocked) "Release" else "Drag")
            }
            MouseHoldButton(
                label = "Wheel",
                modifier = Modifier.weight(1f),
                enabled = mouseSender != null,
                onDown = { mouseSender?.middleDown() },
                onUp = { mouseSender?.middleUp() },
            )
            MouseHoldButton(
                label = "Right",
                modifier = Modifier.weight(1f),
                enabled = mouseSender != null,
                onDown = { mouseSender?.rightDown() },
                onUp = { mouseSender?.rightUp() },
            )
        }
    }
}

@Composable
private fun MouseHoldButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }

    Button(
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = modifier
            .sizeIn(minHeight = 56.dp)
            .pointerInteropFilter { event ->
                if (!enabled) return@pointerInteropFilter false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressed = true
                        onDown()
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (pressed) {
                            pressed = false
                            onUp()
                        }
                        true
                    }

                    else -> true
                }
            },
        enabled = enabled,
        onClick = {},
    ) {
        Text(label, maxLines = 1)
    }
}
