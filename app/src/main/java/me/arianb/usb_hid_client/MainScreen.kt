package me.arianb.usb_hid_client

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
import androidx.compose.runtime.Composable
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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import me.arianb.usb_hid_client.input_views.DirectInput
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

    val snackbarHostState = remember { SnackbarHostState() }
    var showShortcutPanel by remember { mutableStateOf(false) }

    val hidUiUnlocked = uiState.usbProfileActive &&
        !uiState.missingCharacterDevice &&
        uiState.isCharacterDevicePermissionsBroken == null &&
        !rootState.missingRootPrivileges

    USBHIDClientTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = { MainTopBar() },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    if (hidUiUnlocked) {
                        Touchpad(
                            modifier = Modifier.fillMaxSize(),
                            mainViewModel = mainViewModel,
                            showBorder = false,
                        )
                        DirectInput(
                            mainViewModel = mainViewModel,
                            settingsViewModel = settingsViewModel,
                        )
                        MouseButtonBar(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 64.dp),
                            mainViewModel = mainViewModel,
                        )
                    }

                    if (showShortcutPanel && hidUiUnlocked) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(
                                    start = PaddingNormal,
                                    end = PaddingNormal,
                                    bottom = 144.dp,
                                )
                                .heightIn(max = 380.dp),
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 6.dp,
                            shadowElevation = 6.dp,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(PaddingNormal),
                            ) {
                                HidShortcutPanel(mainViewModel = mainViewModel)
                            }
                        }
                    }

                    MainBottomBar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        hidUiUnlocked = hidUiUnlocked,
                        controlsExpanded = showShortcutPanel,
                        usbOperationInProgress = uiState.usbOperationInProgress,
                        onControlsClicked = { showShortcutPanel = !showShortcutPanel },
                        onRestoreClicked = {
                            showShortcutPanel = false
                            mainViewModel.deleteCharacterDevices()
                        },
                        onRefreshClicked = { mainViewModel.refreshUsbStatus() },
                    )

                    if (!hidUiUnlocked || uiState.usbOperationInProgress) {
                        HidActivationDialog(
                            uiState = uiState,
                            missingRoot = rootState.missingRootPrivileges,
                            mainViewModel = mainViewModel,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(uiState) {
        Timber.d("LAUNCHED EFFECT RUNNING WITH UI STATE = %s", uiState.toString())
        if (uiState.isDeviceUnplugged) {
            snackbarHostState.showSnackbar(
                message = "ERROR: Your device seems to be disconnected. If not, try reseating the USB cable",
                duration = SnackbarDuration.Long
            )
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
private fun MainBottomBar(
    modifier: Modifier = Modifier,
    hidUiUnlocked: Boolean,
    controlsExpanded: Boolean,
    usbOperationInProgress: Boolean,
    onControlsClicked: () -> Unit,
    onRestoreClicked: () -> Unit,
    onRefreshClicked: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = PaddingNormal / 2),
            horizontalArrangement = Arrangement.spacedBy(PaddingNormal / 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DirectInputIconButton(enabled = hidUiUnlocked)
            TextButton(
                enabled = hidUiUnlocked,
                onClick = onControlsClicked,
            ) {
                Text(if (controlsExpanded) "Hide" else "Keys")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                enabled = !usbOperationInProgress,
                onClick = onRestoreClicked,
            ) {
                Text("Restore")
            }
            TextButton(
                enabled = !usbOperationInProgress,
                onClick = onRefreshClicked,
            ) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun HidActivationDialog(
    uiState: MyUiState,
    missingRoot: Boolean,
    mainViewModel: MainViewModel,
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
