package me.arianb.usb_hid_client

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.arianb.usb_hid_client.hid_utils.CharacterDeviceManager
import me.arianb.usb_hid_client.hid_utils.BluetoothHidController
import me.arianb.usb_hid_client.hid_utils.C2qUsbController
import me.arianb.usb_hid_client.hid_utils.DevicePath
import me.arianb.usb_hid_client.hid_utils.ModifiesStateDirectly
import me.arianb.usb_hid_client.hid_utils.TouchpadDevicePath
import me.arianb.usb_hid_client.hid_utils.UHID
import me.arianb.usb_hid_client.report_senders.KeySender
import me.arianb.usb_hid_client.report_senders.pointer_device_senders.LoopbackTouchpadSender
import me.arianb.usb_hid_client.report_senders.pointer_device_senders.MouseSender
import me.arianb.usb_hid_client.report_senders.pointer_device_senders.PointerDeviceSender
import me.arianb.usb_hid_client.settings.UserPreferencesRepository
import me.arianb.usb_hid_client.shell_utils.RootStateHolder
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import java.io.FileOutputStream

/**
 * Data class that represents the UI state
 */
data class MyUiState(
    // Character Device Stuff
    val missingCharacterDevice: Boolean = false,
    val isCharacterDevicePermissionsBroken: String? = null,

    // Other Stuff
    val isDeviceUnplugged: Boolean = false,

    // c2q companion module state
    val usbOperationInProgress: Boolean = false,
    val usbProfileActive: Boolean = false,
    val usbStatusMessage: String = "Checking c2q USB state…",
    val bluetoothMode: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionPreferences = application.getSharedPreferences("hid_connection", Context.MODE_PRIVATE)
    private val initialBluetoothMode = connectionPreferences.getBoolean("bluetooth", application.packageName.endsWith(".bluetooth_test"))
    private val _uiState = MutableStateFlow(MyUiState(bluetoothMode = initialBluetoothMode))
    val uiState: StateFlow<MyUiState> = _uiState
    val bluetooth = BluetoothHidController(application)
    private val bluetoothMode = MutableStateFlow(initialBluetoothMode)

    private val characterDeviceManager = CharacterDeviceManager.getInstance(application)
    private val rootStateHolder = RootStateHolder.getInstance()
    private val userPreferencesStateFlow = UserPreferencesRepository.getInstance(application).userPreferencesFlow
    private val bluetoothConnected = bluetooth.state.map { it.connected }.distinctUntilChanged()
    private val senderPreferences = combine(userPreferencesStateFlow, bluetoothMode, bluetoothConnected) { prefs, bt, connected -> Triple(prefs, bt, connected) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Triple(userPreferencesStateFlow.value, initialBluetoothMode, false))

    val keySender: StateFlow<KeySender> = senderPreferences
        .mapState { (prefs, bt) ->
            KeySender(prefs.keyboardCharacterDevicePath, transport(bt, 1, prefs.keyboardCharacterDevicePath))
        }

    val touchpadSender: StateFlow<PointerDeviceSender> = senderPreferences
        .mapState { (prefs, bt) ->
            // TODO:
            //  maybe make it clear to the user that the Loopback Mode always uses precision touchpad, and that these
            //  settings are therefore mutually exclusive.
            if (prefs.isLoopbackModeEnabled && !bt) {
                fixCharacterDevicePermissions(UHID.PATH)
                LoopbackTouchpadSender(TouchpadDevicePath(UHID.PATH))
            } else {
                MouseSender(prefs.touchpadCharacterDevicePath, transport(bt, 2, prefs.touchpadCharacterDevicePath))
            }
        }

    private val senderFlowList = listOf(keySender, touchpadSender)

    init {
        if (!initialBluetoothMode) refreshUsbStatus()

        senderFlowList.forEach { senderFlow ->
            viewModelScope.launch {
                senderFlow.collectLatest { sender ->
                    sender.start(
                        onSuccess = {
                            // This is called when no exception was thrown, meaning everything is good :)
                            // so let's set the UI state back to default (no errors)
                            _uiState.update {
                                it.copy(
                                    missingCharacterDevice = false,
                                    isCharacterDevicePermissionsBroken = null,
                                    isDeviceUnplugged = false,
                                )
                            }
                        },
                        onException = { e ->
                            if (bluetoothMode.value) {
                                bluetooth.updateMessage(e.message ?: "Bluetooth report failed")
                                return@start
                            }
                            val characterDevicePath = sender.characterDevicePath
                            if (e is FileNotFoundException && characterDeviceMissing(characterDevicePath)) {
                                Timber.i("Character device '$characterDevicePath' doesn't exist. The user probably skipped the character device creation prompt.")
                            } else {
                                handleException(e, sender.characterDevicePath)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun transport(bt: Boolean, reportId: Int, path: DevicePath): (ByteArray) -> Unit = { report ->
        if (bluetoothMode.value != bt) throw IOException("Input connection changed")
        if (bt) bluetooth.send(reportId, report)
        else FileOutputStream(path.path).use { it.write(report) }
    }

    fun selectBluetooth() {
        connectionPreferences.edit().putBoolean("bluetooth", true).apply()
        bluetoothMode.value = true
        _uiState.update { it.copy(bluetoothMode = true, isDeviceUnplugged = false) }
    }

    fun selectUsb() {
        connectionPreferences.edit().putBoolean("bluetooth", false).apply()
        bluetooth.stop()
        bluetoothMode.value = false
        _uiState.update { it.copy(bluetoothMode = false) }
        refreshUsbStatus()
    }

    override fun onCleared() {
        bluetooth.stop()
        super.onCleared()
    }

    private fun handleException(e: IOException, devicePath: DevicePath) {
        val exceptionString = e.message ?: Log.getStackTraceString(e)
        val lowercaseExceptionString = exceptionString.lowercase()

        if (lowercaseExceptionString.contains("errno 108")) {
            Timber.i("device might be unplugged")
            _uiState.update { it.copy(isDeviceUnplugged = true) }
        } else if (lowercaseExceptionString.contains("permission denied")) {
            Timber.i("char dev perms are wrong")
            _uiState.update { it.copy(isCharacterDevicePermissionsBroken = devicePath.path) }
        } else if (lowercaseExceptionString.contains("enxio")) {
            Timber.i("somehow the HID gadget is disabled but the character devices are still present")
        } else {
            Timber.e(e)
            Timber.e("unknown error has occurred while trying to write to character device")
//            showSnackbar("ERROR: Failed to send mouse report.", Snackbar.LENGTH_SHORT)
        }

        Timber.d("in MainViewModel, new state is: %s", uiState.value.toString())
    }

    // Character Device Manager
    @OptIn(ModifiesStateDirectly::class)
    fun createCharacterDevices() {
        if (!rootStateHolder.hasRootPermissions()) {
            Timber.w("Can't create character devices, missing root permissions")
            return
        }

        _uiState.update { it.copy(usbOperationInProgress = true, usbStatusMessage = "Activating keyboard + mouse…") }
        viewModelScope.launch {
            val result = characterDeviceManager.createCharacterDevices()
            val missing = characterDeviceManager.anyCharacterDeviceMissing()
            val active = C2qUsbController.isTemporaryProfileActive()
            _uiState.update {
                it.copy(
                    usbOperationInProgress = false,
                    usbProfileActive = active,
                    usbStatusMessage = result.message,
                    missingCharacterDevice = missing,
                )
            }
        }
    }

    fun deleteCharacterDevices() {
        if (!rootStateHolder.hasRootPermissions()) {
            Timber.w("Can't delete character devices, missing root permissions")
            return
        }

        _uiState.update { it.copy(usbOperationInProgress = true, usbStatusMessage = "Restoring Samsung USB…") }
        viewModelScope.launch {
            val result = characterDeviceManager.deleteCharacterDevices()
            val active = C2qUsbController.isTemporaryProfileActive()
            _uiState.update {
                it.copy(
                    usbOperationInProgress = false,
                    usbProfileActive = active,
                    usbStatusMessage = result.message,
                    missingCharacterDevice = true,
                )
            }
        }
    }

    fun refreshUsbStatus() {
        viewModelScope.launch {
            val result = C2qUsbController.status()
            val active = C2qUsbController.isTemporaryProfileActive()
            _uiState.update {
                it.copy(
                    usbProfileActive = active,
                    usbStatusMessage = result.message,
                    missingCharacterDevice = !active,
                )
            }
        }
    }

    @OptIn(ModifiesStateDirectly::class)
    fun fixCharacterDevicePermissions(device: String) {
        if (!rootStateHolder.hasRootPermissions()) {
            Timber.w("Can't fix character device permissions, missing root permissions")
            return
        }

        _uiState.update { it.copy(usbOperationInProgress = true, usbStatusMessage = "Repairing HID node access…") }
        viewModelScope.launch {
            val permissionsReady = characterDeviceManager.fixCharacterDevicePermissions(device)
            val missing = characterDeviceManager.anyCharacterDeviceMissing()
            val active = C2qUsbController.isTemporaryProfileActive()
            _uiState.update {
                it.copy(
                    usbOperationInProgress = false,
                    usbProfileActive = active,
                    usbStatusMessage = if (permissionsReady) {
                        "HID access repaired"
                    } else {
                        "Could not repair HID access"
                    },
                    missingCharacterDevice = missing,
                    isCharacterDevicePermissionsBroken = if (permissionsReady) null else device,
                )
            }
        }
    }

    @OptIn(ModifiesStateDirectly::class)
    fun characterDeviceMissing(charDevicePath: DevicePath): Boolean {
        val result = characterDeviceManager.characterDeviceMissing(charDevicePath)

        _uiState.update { it.copy(missingCharacterDevice = result) }

        return result
    }

    @OptIn(ModifiesStateDirectly::class)
    fun anyCharacterDeviceMissing(): Boolean {
        val result = characterDeviceManager.anyCharacterDeviceMissing()

        _uiState.update { it.copy(missingCharacterDevice = result) }

        return result
    }

    // Keyboard
    fun addStandardKey(modifier: Byte, key: Byte) =
        keySender.value.addStandardKey(modifier, key)

    fun addMediaKey(key: Byte) =
        keySender.value.addMediaKey(key)

    private inline fun <T, R> StateFlow<T>.mapState(
        crossinline transform: (value: T) -> R
    ) = mapState(viewModelScope, transform)
}
