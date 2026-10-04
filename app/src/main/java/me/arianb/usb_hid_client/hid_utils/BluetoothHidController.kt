package me.arianb.usb_hid_client.hid_utils

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.concurrent.Executor

data class BluetoothHost(val address: String, val name: String)
data class BluetoothHidState(
    val registered: Boolean = false,
    val connected: Boolean = false,
    val message: String = "Bluetooth HID is off",
    val hosts: List<BluetoothHost> = emptyList(),
)

/** Classic Bluetooth HID device, using the same boot keyboard and relative mouse payloads as USB. */
@SuppressLint("MissingPermission") // Each public entry point checks CONNECT; callback errors are handled too.
class BluetoothHidController(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("bluetooth_hosts", Context.MODE_PRIVATE)
    fun lastHost(): BluetoothHost? {
        val address = preferences.getString("address", null) ?: return null
        return state.value.hosts.firstOrNull { it.address == address }
    }
    fun reconnectLastHost() {
        if (!state.value.registered) start() else lastHost()?.let { connect(it.address) }
    }
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val mutableState = MutableStateFlow(BluetoothHidState())
    val state = mutableState.asStateFlow()
    @Volatile private var service: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private var requested = false
    private var closed = false
    private val reports = mutableMapOf(1 to ByteArray(8), 2 to ByteArray(4))
    @Volatile private var reportProtocol = true
    private val timeout = Runnable {
        if (requested && !state.value.registered) {
            stop()
            updateMessage("Bluetooth HID registration timed out. Try enabling it again.")
        }
    }

    fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isEnabled() = hasPermission() && adapter?.isEnabled == true

    fun start() {
        if (Build.VERSION.SDK_INT < 28) {
            updateMessage("Bluetooth HID requires Android 9 or newer")
            return
        }
        if (!hasPermission()) { updateMessage("Allow Nearby devices to enable Bluetooth HID"); return }
        if (!isEnabled()) { updateMessage("Turn on Bluetooth first"); return }
        if (requested) { refreshHosts(); return }
        requested = true
        closed = false
        updateMessage("Registering Bluetooth keyboard and mouse…")
        try {
            if (adapter?.getProfileProxy(app, listener, BluetoothProfile.HID_DEVICE) != true) {
                requested = false
                updateMessage("This Android build does not expose Bluetooth HID device support")
            } else {
                handler.postDelayed(timeout, 10_000)
            }
        } catch (e: Exception) {
            requested = false
            updateMessage("Bluetooth HID: ${e.message}")
        }
    }

    private val listener by lazy { object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (closed || !requested) { adapter?.closeProfileProxy(profile, proxy); return }
            val hid = proxy as BluetoothHidDevice
            service = hid
            try {
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "C2Q Keyboard and Mouse", "C2Q HID Companion", "C2Q",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR,
                )
                if (!hid.registerApp(sdp, null, null, executor, callback)) {
                    stop()
                    updateMessage("Could not register Bluetooth HID. Close other Bluetooth HID apps and retry.")
                }
            } catch (e: Exception) {
                stop()
                updateMessage("Bluetooth HID registration failed: ${e.message}")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (closed) return
            service = null
            host = null
            requested = false
            handler.removeCallbacks(timeout)
            mutableState.update { it.copy(registered = false, connected = false, message = "Bluetooth HID service disconnected") }
        }
    } }

    private val callback by lazy { object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (closed) return
            handler.removeCallbacks(timeout)
            if (!registered) {
                stop()
                updateMessage("Bluetooth HID stopped. Keep this app in the foreground and enable it again.")
                return
            }
            mutableState.update { it.copy(registered = registered, connected = if (registered) it.connected else false,
                message = if (registered) "Ready. Pair this phone from your computer, then select the computer below."
                else "Bluetooth HID stopped. Keep this app in the foreground and enable it again.") }
            if (!registered) { requested = false; host = null }
            refreshHosts()
            if (registered && !state.value.connected) {
                val previous = lastHost()
                if (previous != null) {
                    val device = adapter?.getRemoteDevice(previous.address)
                    if (device != null && service?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                        onConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTED)
                    } else connect(previous.address)
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, connectionState: Int) {
            if (closed) return
            when (connectionState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    preferences.edit().putString("address", device.address).apply()
                    reportProtocol = true
                    synchronized(reports) { reports[1] = ByteArray(8); reports[2] = ByteArray(4) }
                    mutableState.update { it.copy(connected = true, message = "Connected to ${device.name ?: device.address}") }
                    // Clear any stale host key/button state after a reconnect.
                    releaseAll()
                }
                BluetoothProfile.STATE_CONNECTING -> updateMessage("Connecting to ${device.name ?: device.address}…")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device || host == null) {
                        host = null
                        mutableState.update { it.copy(connected = false, message = "Disconnected. Select a paired computer to reconnect.") }
                    }
                }
            }
            refreshHosts()
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            if (closed) return
            val payload = synchronized(reports) { reports[id.toInt()]?.clone() }
            try {
                if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && payload != null) {
                    service?.replyReport(device, type, id, if (bufferSize > 0) payload.take(bufferSize).toByteArray() else payload)
                } else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id.toInt() == 1) {
                    service?.replyReport(device, type, id, byteArrayOf(0))
                } else service?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            } catch (e: Exception) { updateMessage("Bluetooth HID: ${e.message}") }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            if (closed) return
            try {
                // Keyboard LED output is accepted; the phone has no physical lock LEDs.
                service?.reportError(device, if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id.toInt() == 1 && data.size == 1)
                    BluetoothHidDevice.ERROR_RSP_SUCCESS else BluetoothHidDevice.ERROR_RSP_INVALID_PARAM)
            } catch (e: Exception) { updateMessage("Bluetooth HID: ${e.message}") }
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            reportProtocol = protocol == BluetoothHidDevice.PROTOCOL_REPORT_MODE
            if (!reportProtocol) updateMessage("Host requested boot protocol; use this connection in the operating system.")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            host = null
            mutableState.update { it.copy(connected = false, message = "Computer removed this HID connection. Pair again.") }
        }
    } }

    fun refreshHosts() {
        if (!hasPermission()) return
        try {
            val hosts = adapter?.bondedDevices.orEmpty().map { BluetoothHost(it.address, it.name ?: it.address) }.sortedBy { it.name }
            mutableState.update { it.copy(hosts = hosts) }
        } catch (e: Exception) { updateMessage("Could not read paired devices: ${e.message}") }
    }

    fun connect(address: String) {
        if (!hasPermission() || !state.value.registered) return
        try {
            if (host != null && host?.address != address) {
                updateMessage("Disconnect the current computer first")
                return
            }
            val device = adapter?.getRemoteDevice(address) ?: return
            updateMessage("Connecting to ${device.name ?: address}…")
            if (service?.connect(device) != true) updateMessage("Connection was rejected. Pair from the computer and retry.")
        } catch (e: Exception) { updateMessage("Connection failed: ${e.message}") }
    }

    @Synchronized fun send(id: Int, report: ByteArray) {
        val device = host ?: throw IOException("Bluetooth computer is not connected")
        if (!hasPermission()) throw IOException("Nearby devices permission is missing")
        if (!reportProtocol) throw IOException("Bluetooth host is not in report protocol mode")
        try {
            if (service?.sendReport(device, id, report) != true) throw IOException("Bluetooth HID report was rejected")
            synchronized(reports) {
                // Relative movement is an event, not persistent state. GET_REPORT must not replay it.
                reports[id] = if (id == 2) byteArrayOf(report[0], 0, 0, 0) else report.clone()
            }
        } catch (e: SecurityException) { throw IOException("Bluetooth permission denied", e) }
    }

    fun releaseAll() {
        if (host == null) return
        try { send(1, ByteArray(8)); send(2, ByteArray(4)) } catch (_: IOException) { }
    }

    fun stop() {
        closed = true
        requested = false
        handler.removeCallbacks(timeout)
        releaseAll()
        try {
            if (hasPermission()) {
                host?.let { service?.disconnect(it) }
                service?.unregisterApp()
            }
            service?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (_: Exception) { }
        host = null
        service = null
        mutableState.value = BluetoothHidState()
    }

    fun updateMessage(message: String) { mutableState.update { it.copy(message = message) } }

    companion object {
        // Report 1: eight-byte keyboard + LED output. Report 2: buttons, signed X/Y, signed wheel.
        internal val DESCRIPTOR = intArrayOf(
            0x05,0x01,0x09,0x06,0xA1,0x01,0x85,0x01,
            0x05,0x07,0x19,0xE0,0x29,0xE7,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,
            0x95,0x01,0x75,0x08,0x81,0x01,
            0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,0x91,0x02,
            0x95,0x01,0x75,0x03,0x91,0x01,
            0x95,0x06,0x75,0x08,0x15,0x00,0x26,0xFF,0x00,0x05,0x07,0x19,0x00,0x2A,0xFF,0x00,0x81,0x00,0xC0,
            0x05,0x01,0x09,0x02,0xA1,0x01,0x85,0x02,0x09,0x01,0xA1,0x00,
            0x05,0x09,0x19,0x01,0x29,0x03,0x15,0x00,0x25,0x01,0x95,0x03,0x75,0x01,0x81,0x02,
            0x95,0x01,0x75,0x05,0x81,0x01,
            0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x38,0x15,0x80,0x25,0x7F,0x75,0x08,0x95,0x03,0x81,0x06,0xC0,0xC0,
        ).map { it.toByte() }.toByteArray()
    }
}
