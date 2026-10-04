package me.arianb.usb_hid_client

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import me.arianb.usb_hid_client.input_views.InputKeyStrip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import me.arianb.usb_hid_client.input_views.SavedControlDeck
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import me.arianb.usb_hid_client.input_views.DirectInput
import me.arianb.usb_hid_client.input_views.BluetoothConnectionDialog
import me.arianb.usb_hid_client.input_views.DirectInputIconButton
import me.arianb.usb_hid_client.input_views.HidShortcutPanel
import me.arianb.usb_hid_client.input_views.MouseButtonBar
import me.arianb.usb_hid_client.input_views.Touchpad
import me.arianb.usb_hid_client.settings.SettingsScreen
import me.arianb.usb_hid_client.settings.SettingsViewModel
import me.arianb.usb_hid_client.shell_utils.RootStateHolder
import me.arianb.usb_hid_client.troubleshooting.TroubleshootingScreen
import me.arianb.usb_hid_client.ui.standalone_screens.HelpScreen
import me.arianb.usb_hid_client.ui.standalone_screens.InfoScreen
import me.arianb.usb_hid_client.ui.theme.PaddingNormal
import me.arianb.usb_hid_client.ui.utils.BasicTopBar
import me.arianb.usb_hid_client.ui.utils.DarkLightModePreviews
import me.arianb.usb_hid_client.ui.theme.USBHIDClientTheme
import timber.log.Timber

class MainScreen : Screen {
    @Composable
    override fun Content() {
        MainPage()
    }
}

@Composable
fun MainPage(
    mainViewModel: MainViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val rootStateHolder = RootStateHolder.getInstance()
    val rootState by rootStateHolder.uiState.collectAsState()

    val uiState by mainViewModel.uiState.collectAsState()
    Timber.d("in MainScreen, uiState is: %s", uiState.toString())

    val inputModifiers by mainViewModel.inputModifiers.collectAsState()
    val modifierLabel = listOf(1 to "Ctrl", 2 to "Shift", 4 to "Alt", 8 to "Win / Cmd").filter { inputModifiers and it.first != 0 }.joinToString("+") { it.second }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var keyboardVisible by remember { mutableStateOf(false) }
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = Rect()
            hostView.getWindowVisibleDisplayFrame(visible)
            val metrics = if (Build.VERSION.SDK_INT >= 30) (hostView.context as? Activity)?.windowManager?.currentWindowMetrics?.bounds else null
            val windowBottom = metrics?.bottom ?: hostView.resources.displayMetrics.heightPixels
            val windowHeight = metrics?.height() ?: hostView.resources.displayMetrics.heightPixels
            keyboardVisible = ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) == true ||
                windowBottom - visible.bottom > windowHeight * 0.15f
        }
        hostView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { hostView.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    var page by rememberSaveable { mutableStateOf(0) }
    var showBluetoothDialog by remember { mutableStateOf(false) }
    val bluetoothState by mainViewModel.bluetooth.state.collectAsState()
    LaunchedEffect(Unit) {
        if (uiState.bluetoothMode && mainViewModel.bluetooth.hasPermission() && mainViewModel.bluetooth.isEnabled()) mainViewModel.bluetooth.start()
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mainViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { mainViewModel.bluetooth.releaseAll(); mainViewModel.clearInputModifiers() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val ready = if (uiState.bluetoothMode) bluetoothState.connected else uiState.usbProfileActive &&
        !uiState.missingCharacterDevice && uiState.isCharacterDevicePermissionsBroken == null &&
        !rootState.missingRootPrivileges

    DisposableEffect(ready, hostView) {
        val previous = hostView.keepScreenOn
        hostView.keepScreenOn = ready
        onDispose { hostView.keepScreenOn = previous }
    }
    USBHIDClientTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                topBar = { MainTopBar() },
                bottomBar = {
                    Surface(tonalElevation = 8.dp) {
                        TabRow(selectedTabIndex = page.coerceIn(0, 1), modifier = Modifier.navigationBarsPadding()) {
                            listOf("Input", "Control Deck").forEachIndexed { index, label ->
                                Tab(selected = page.coerceIn(0, 1) == index, onClick = {
                                    mainViewModel.bluetooth.releaseAll()
                                    mainViewModel.clearInputModifiers()
                                    keyboard?.hide()
                                    page = index
                                }, text = { Text(label) })
                            }
                        }
                    }
                },
                modifier = Modifier.imePadding(),
            ) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(horizontal = PaddingNormal),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(if (uiState.bluetoothMode) "Bluetooth" else "USB", style = MaterialTheme.typography.labelMedium)
                                Text(if (uiState.bluetoothMode) bluetoothState.message else if (ready) "USB controls ready" else "USB setup required",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { keyboard?.hide(); showBluetoothDialog = true }) { Text("Connection") }
                        }
                    }
                    if (ready) {
                        when (page.coerceIn(0, 1)) {
                            0 -> Column(modifier = Modifier.weight(1f)) {
                                DirectInput(mainViewModel, settingsViewModel)
                                Box(modifier = Modifier.weight(1f)) {
                                    Touchpad(modifier = Modifier.fillMaxSize(), mainViewModel = mainViewModel, showBorder = false)
                                    if (!keyboardVisible) Text("Tap to click · Two fingers to scroll", modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                MouseButtonBar(mainViewModel = mainViewModel)
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    DirectInputIconButton(keyboardVisible = keyboardVisible)
                                    Text(if (keyboardVisible) "Hide keyboard" else "Show keyboard", style = MaterialTheme.typography.labelMedium)
                                    Spacer(modifier = Modifier.weight(1f))
                                    TextButton(onClick = { showKeys = !showKeys }) { Text(if (showKeys) "Hide keys" else if (modifierLabel.isNotEmpty()) modifierLabel else "Special keys") }
                                }
                                if (showKeys) InputKeyStrip(mainViewModel)
                            }
                            1 -> Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PaddingNormal)) {
                                SavedControlDeck(mainViewModel)
                            }
                        }
                    } else {
                        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(PaddingNormal),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (uiState.bluetoothMode) "Connect a computer to start" else "USB requires root and the C2Q module")
                            Button(onClick = { showBluetoothDialog = true }) { Text("Manage connection") }
                        }
                    }
                }
                if (showBluetoothDialog) BluetoothConnectionDialog(mainViewModel) { showBluetoothDialog = false }
                if (!uiState.bluetoothMode && !ready && !showBluetoothDialog) {
                    HidActivationDialog(uiState, rootState.missingRootPrivileges, mainViewModel) { showBluetoothDialog = true }
                }
            }
        }
    }
}

private typealias MenuItem = Pair<Screen, String>

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar() {
    val navigator = LocalNavigator.currentOrThrow
    var showDropdownMenu by remember { mutableStateOf(false) }

    BasicTopBar(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = { showDropdownMenu = true }) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Overflow Menu",
                )
                DropdownMenu(
                    expanded = showDropdownMenu,
                    onDismissRequest = { showDropdownMenu = false }
                ) {
                    val menuItems = arrayOf(
                        MenuItem(SettingsScreen(), stringResource(R.string.settings)),
                        MenuItem(TroubleshootingScreen(), stringResource(R.string.troubleshooting_title)),
                        MenuItem(HelpScreen(), stringResource(R.string.help)),
                        MenuItem(InfoScreen(), stringResource(R.string.info))
                    )
                    for (item in menuItems) {
                        DropdownMenuItem(
                            text = { Text(item.second) },
                            onClick = {
                                // Navigate to screen (safely)
                                //
                                // NOTE:
                                //  Extra code here is necessary because the user can spam click the DropdownMenuItem
                                //  before the navigation has completed. This would lead to it trying to navigate to the
                                //  same screen twice. As of right now, Voyager will crash if this happens without you
                                //  setting unique keys in every Screen. However, even after fixing that, being able
                                //  to navigate to the same screen multiple times is undesirable. For this reason, I have
                                //  added extra code that makes sure the given subclass of Screen isn't already present
                                //  in the navigation stack before we navigate.

                                val thisScreen = item.first

                                // Ensure that the Screen we're about to push isn't already in the navigation stack.
                                // Iterates in reverse because it's more likely for the duplicate item to be at the end.
                                for (screen in navigator.items.reversed()) {
                                    if (screen::class == thisScreen::class) {
                                        return@DropdownMenuItem
                                    }
                                }

                                // Navigate to screen
                                navigator.push(thisScreen)
                                showDropdownMenu = false
                            }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun HidActivationDialog(
    uiState: MyUiState,
    missingRoot: Boolean,
    mainViewModel: MainViewModel,
    onBluetoothClicked: () -> Unit,
) {
    val permissionPath = uiState.isCharacterDevicePermissionsBroken
    val title = when {
        missingRoot -> "Root access required"
        uiState.usbOperationInProgress -> "Preparing HID profile"
        permissionPath != null -> "Repair HID access"
        else -> "Activate HID profile"
    }
    val body = when {
        missingRoot -> "Grant root in Magisk before using keyboard or mouse controls."
        uiState.usbOperationInProgress -> uiState.usbStatusMessage
        permissionPath != null -> "The profile is active, but the app cannot write to $permissionPath yet."
        else -> uiState.usbStatusMessage
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(PaddingNormal)) {
                Text(body)
                TextButton(onClick = onBluetoothClicked) { Text("Use Bluetooth instead") }
                if (uiState.usbOperationInProgress) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(PaddingNormal / 2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Activating module, waiting for HID nodes, and applying access rules.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !uiState.usbOperationInProgress && !missingRoot,
                onClick = {
                    if (permissionPath != null) {
                        mainViewModel.fixCharacterDevicePermissions(permissionPath)
                    } else {
                        mainViewModel.createCharacterDevices()
                    }
                },
            ) {
                Text(if (permissionPath != null) "Repair" else "Activate")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !uiState.usbOperationInProgress,
                onClick = { mainViewModel.refreshUsbStatus() },
            ) {
                Text("Refresh")
            }
        },
    )
}

@DarkLightModePreviews
@Composable
private fun MainScreenPreview() {
    Navigator(MainScreen())
}
