package me.arianb.usb_hid_client.hid_utils

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class C2qUsbResult(
    val success: Boolean,
    val message: String,
    val output: String = "",
)

/**
 * Root bridge to the installed c2q-nethunter-companion Magisk module.
 *
 * This app deliberately performs no ConfigFS, UDC, property or Samsung USB
 * operations itself. The module remains the single owner of snapshots,
 * transition locking, watchdog handling and restoration.
 */
object C2qUsbController {
    const val MODULE_ID = "c2q_nethunter_companion"
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    const val USB_COMMAND = "$MODULE_DIR/bin/c2q-usb"
    const val USB_COMMAND_SH = "sh $USB_COMMAND"
    const val ACTIVE_MARKER = "$MODULE_DIR/state/temporary-profile.active"
    const val DEFAULT_SESSION_TIMEOUT_SECONDS = 0

    private fun runRoot(command: String): Shell.Result = Shell.cmd(command).exec()

    private fun Shell.Result.asResult(successMessage: String): C2qUsbResult {
        val stdout = out.joinToString("\n").trim()
        val stderr = err.joinToString("\n").trim()
        val combined = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
        return C2qUsbResult(
            success = code == 0,
            message = if (code == 0) successMessage else combined.ifBlank { "Command failed with exit code $code" },
            output = combined,
        )
    }

    suspend fun activateKeyboardMouse(
        timeoutSeconds: Int = DEFAULT_SESSION_TIMEOUT_SECONDS,
    ): C2qUsbResult = withContext(Dispatchers.IO) {
        if (timeoutSeconds != 0 && timeoutSeconds !in 15..3600) {
            return@withContext C2qUsbResult(false, "Watchdog timeout must be between 15 and 3600 seconds")
        }

        val prerequisite = runRoot("test -x $USB_COMMAND")
        if (prerequisite.code != 0) {
            return@withContext C2qUsbResult(
                false,
                "Required Magisk module command is missing: $USB_COMMAND",
            )
        }

        val command = if (timeoutSeconds == 0) {
            "$USB_COMMAND_SH hid-keyboard-mouse"
        } else {
            "C2Q_USB_TIMEOUT=$timeoutSeconds $USB_COMMAND_SH hid-keyboard-mouse"
        }
        val successMessage = if (timeoutSeconds == 0) {
            "Keyboard + mouse active; restore manually when finished"
        } else {
            "Keyboard + mouse active; watchdog timeout is ${timeoutSeconds}s"
        }

        runRoot(command).asResult(successMessage)
    }

    suspend fun restore(): C2qUsbResult = withContext(Dispatchers.IO) {
        val prerequisite = runRoot("test -x $USB_COMMAND")
        if (prerequisite.code != 0) {
            return@withContext C2qUsbResult(
                false,
                "Required Magisk module command is missing: $USB_COMMAND",
            )
        }

        runRoot("$USB_COMMAND_SH restore")
            .asResult("Samsung MTP/ADB USB restored")
    }

    suspend fun status(): C2qUsbResult = withContext(Dispatchers.IO) {
        val result = runRoot("$USB_COMMAND_SH status")
        result.asResult(if (isTemporaryProfileActiveBlocking()) "HID profile active" else "Samsung USB active")
    }

    suspend fun isTemporaryProfileActive(): Boolean = withContext(Dispatchers.IO) {
        isTemporaryProfileActiveBlocking()
    }

    suspend fun restoreIfActive(): C2qUsbResult = withContext(Dispatchers.IO) {
        if (isTemporaryProfileActiveBlocking()) {
            restore()
        } else {
            C2qUsbResult(true, "No temporary USB profile is active")
        }
    }

    private fun isTemporaryProfileActiveBlocking(): Boolean =
        runRoot("test -f $ACTIVE_MARKER").code == 0
}
