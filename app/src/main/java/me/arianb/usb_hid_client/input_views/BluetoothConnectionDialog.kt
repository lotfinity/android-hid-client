package me.arianb.usb_hid_client.input_views

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import me.arianb.usb_hid_client.MainViewModel
import me.arianb.usb_hid_client.ui.theme.PaddingSmall

@Composable
fun BluetoothConnectionDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val bt = vm.bluetooth
    val state by bt.state.collectAsState()
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (bt.isEnabled()) bt.start() else bt.updateMessage("Bluetooth was not enabled")
    }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        bt.start()
        bt.refreshHosts()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            if (bt.isEnabled()) bt.start() else enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else bt.updateMessage("Nearby devices permission is required for Bluetooth HID")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bluetooth keyboard and mouse") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PaddingSmall),
            ) {
                Text(state.message)
                Text("Keep this app open while using Bluetooth controls. Your computer receives a standard keyboard and mouse.")
                if (!state.registered) {
                    Button(onClick = {
                        vm.selectBluetooth()
                        if (Build.VERSION.SDK_INT >= 31) {
                            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                        } else if (bt.isEnabled()) bt.start()
                        else enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }) { Text("Enable Bluetooth HID") }
                } else {
                    OutlinedButton(onClick = {
                        try {
                            discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                        } catch (_: SecurityException) {
                            bt.updateMessage("Allow Nearby devices, then enable Bluetooth HID again")
                        }
                    }) { Text("Pair a new computer") }
                    Text("On Windows: Settings → Bluetooth & devices → Add device → Bluetooth → select this phone.")
                    TextButton(onClick = { bt.refreshHosts() }) { Text("Refresh paired devices") }
                    if (!state.connected) state.hosts.forEach { host ->
                        OutlinedButton(onClick = { bt.connect(host.address) }) { Text("Connect to ${host.name}") }
                    }
                    TextButton(onClick = { bt.stop() }) { Text("Stop Bluetooth HID") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { vm.selectUsb(); onDismiss() }) { Text("Use USB") } },
    )
}
