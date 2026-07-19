package me.arianb.usb_hid_client.hid_utils

import android.app.Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import me.arianb.usb_hid_client.shell_utils.RootStateHolder
import timber.log.Timber
import java.io.File

class CharacterDeviceManager private constructor(private val application: Application) {
    private val rootStateHolder = RootStateHolder.getInstance()

    suspend fun createCharacterDevices(): C2qUsbResult {
        val activation = C2qUsbController.activateKeyboardMouse()
        if (!activation.success) return activation

        return withContext(Dispatchers.IO) {
            if (!fixSelinuxPermissions()) {
                C2qUsbController.restore()
                return@withContext C2qUsbResult(
                    false,
                    "HID activated, but the app-specific SELinux rule failed; Samsung USB was restored",
                )
            }

            val devicesReady = waitForRequiredDevices()
            if (!devicesReady) {
                C2qUsbController.restore()
                return@withContext C2qUsbResult(
                    false,
                    "HID nodes did not appear; Samsung USB was restored",
                )
            }

            val permissionsReady = DevicePaths.all.all { fixCharacterDevicePermissions(it.path) }
            if (!permissionsReady) {
                C2qUsbController.restore()
                return@withContext C2qUsbResult(
                    false,
                    "Could not grant access to the HID nodes; Samsung USB was restored",
                )
            }

            activation
        }
    }

    suspend fun deleteCharacterDevices(): C2qUsbResult = C2qUsbController.restore()

    private suspend fun waitForRequiredDevices(): Boolean {
        repeat(20) {
            if (DevicePaths.all.all { it.exists() }) return true
            delay(200)
        }
        return false
    }

    private fun fixSelinuxPermissions(): Boolean {
        val policyCommand = rootStateHolder.sepolicyCommand ?: return false
        val appDomain = getAppProcessSelinuxDomain() ?: return false
        val policy = "allow $appDomain device chr_file { getattr open read write ioctl }"
        return Shell.cmd("$policyCommand '$policy'").exec().code == 0
    }

    fun fixCharacterDevicePermissions(device: DevicePath): Boolean =
        fixCharacterDevicePermissions(device.path)

    fun fixCharacterDevicePermissions(device: String): Boolean {
        if (device !in ALLOWED_HID_PATHS) {
            Timber.e("Refusing unexpected HID device path: %s", device)
            return false
        }

        val appUID = application.applicationInfo.uid
        val categories = getSelinuxCategories() ?: return false
        val commands = arrayOf(
            "chown $appUID:$appUID $device",
            "chmod 600 $device",
            "chcon u:object_r:device:s0:$categories $device",
        )
        return commands.all { Shell.cmd(it).exec().code == 0 }
    }

    private fun getAppSelinuxContext(): String? {
        val result = Shell.cmd("stat -c %C ${application.applicationInfo.dataDir}").exec()
        if (result.code != 0) return null
        return result.out.joinToString("\n").trim().takeIf { it.isNotBlank() }
    }

    private fun getAppProcessSelinuxDomain(): String? {
        val packageName = application.packageName
        val result = Shell.cmd(
            "PID=\$(pidof $packageName 2>/dev/null | awk '{print \$1}'); " +
                "[ -n \"\$PID\" ] && cat /proc/\$PID/attr/current",
        ).exec()
        if (result.code != 0) return null
        val processContext = result.out.joinToString("\n").trim().takeIf { it.isNotBlank() } ?: return null
        val domain = processContext.split(':').getOrNull(2) ?: return null
        return domain.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
    }

    private fun getSelinuxCategories(): String? {
        val context = getAppSelinuxContext() ?: return null
        val categories = context.substringAfterLast(':')
        return categories.takeIf { it.matches(Regex("c[0-9]+(?:,c[0-9]+)*")) }
    }

    @ModifiesStateDirectly
    fun characterDeviceMissing(charDevicePath: DevicePath): Boolean =
        charDevicePath !in DevicePaths.all || !charDevicePath.exists()

    @ModifiesStateDirectly
    fun anyCharacterDeviceMissing(): Boolean = DevicePaths.all.any { !it.exists() }

    companion object {
        private val ALLOWED_HID_PATHS = setOf("/dev/hidg0", "/dev/hidg1")

        object DevicePaths {
            val DEFAULT_KEYBOARD_DEVICE_PATH = KeyboardDevicePath("/dev/hidg0")
            val DEFAULT_TOUCHPAD_DEVICE_PATH = TouchpadDevicePath("/dev/hidg1")

            private val _keyboard = MutableStateFlow(DEFAULT_KEYBOARD_DEVICE_PATH)
            private val _touchpad = MutableStateFlow(DEFAULT_TOUCHPAD_DEVICE_PATH)

            val keyboard: StateFlow<KeyboardDevicePath> = _keyboard
            val touchpad: StateFlow<TouchpadDevicePath> = _touchpad

            val all: List<DevicePath>
                get() = listOf(keyboard.value, touchpad.value)
        }

        @Volatile
        private var INSTANCE: CharacterDeviceManager? = null

        fun getInstance(application: Application): CharacterDeviceManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: CharacterDeviceManager(application).also { INSTANCE = it }
            }
    }
}

@Retention(value = AnnotationRetention.BINARY)
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Ensure UI state is updated when calling this method",
)
annotation class ModifiesStateDirectly

interface DevicePath {
    val path: String
    fun exists(): Boolean = File(path).exists()
}

@JvmInline
value class KeyboardDevicePath(override val path: String) : DevicePath

@JvmInline
value class TouchpadDevicePath(override val path: String) : DevicePath
