package me.arianb.usb_hid_client.input_views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import me.arianb.usb_hid_client.MainViewModel
import me.arianb.usb_hid_client.ui.theme.PaddingNormal
import me.arianb.usb_hid_client.ui.theme.PaddingSmall

private const val MOD_LEFT_CTRL = 0x01
private const val MOD_LEFT_SHIFT = 0x02
private const val MOD_LEFT_ALT = 0x04
private const val MOD_LEFT_GUI = 0x08

private data class SpecialKey(
    val label: String,
    val keyCode: Byte,
)

private data class KeyCombo(
    val label: String,
    val modifier: Byte,
    val keyCode: Byte,
)

private val quickKeys = listOf(
    SpecialKey("Tab", 0x2b),
    SpecialKey("Esc", 0x29),
    SpecialKey("Enter", 0x28),
    SpecialKey("Bksp", 0x2a),
    SpecialKey("↑", 0x52),
    SpecialKey("↓", 0x51),
    SpecialKey("←", 0x50),
    SpecialKey("→", 0x4f),
)

private val navigationKeys = listOf(
    SpecialKey("Delete", 0x4c),
    SpecialKey("Home", 0x4a),
    SpecialKey("End", 0x4d),
    SpecialKey("PgUp", 0x4b),
    SpecialKey("PgDn", 0x4e),
)

private val functionKeys = (1..12).map { index ->
    SpecialKey("F$index", (0x39 + index).toByte())
}

private val commonCombos = listOf(
    KeyCombo("Ctrl+C", MOD_LEFT_CTRL.toByte(), 0x06),
    KeyCombo("Ctrl+V", MOD_LEFT_CTRL.toByte(), 0x19),
    KeyCombo("Ctrl+X", MOD_LEFT_CTRL.toByte(), 0x1b),
    KeyCombo("Ctrl+Z", MOD_LEFT_CTRL.toByte(), 0x1d),
    KeyCombo("Ctrl+L", MOD_LEFT_CTRL.toByte(), 0x0f),
    KeyCombo("Alt+Tab", MOD_LEFT_ALT.toByte(), 0x2b),
    KeyCombo("Shift+Tab", MOD_LEFT_SHIFT.toByte(), 0x2b),
    KeyCombo("Ctrl+Alt+Del", (MOD_LEFT_CTRL or MOD_LEFT_ALT).toByte(), 0x4c),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HidControlDeck(
    mainViewModel: MainViewModel = viewModel(),
) {
    HidShortcutPanel(mainViewModel)

    // Keep the raw keyboard-capture view before the touchpad so the keyboard icon can still focus it.
    DirectInput()
    Touchpad()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HidShortcutPanel(
    mainViewModel: MainViewModel = viewModel(),
) {
    var activeModifier by remember { mutableIntStateOf(0) }
    var showAdvancedKeys by rememberSaveable { mutableStateOf(false) }
    var showCombos by rememberSaveable { mutableStateOf(false) }
    var showMacro by rememberSaveable { mutableStateOf(false) }
    var macroText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PaddingSmall),
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PaddingNormal),
                verticalArrangement = Arrangement.spacedBy(PaddingSmall),
            ) {
                Text(
                    text = "Quick HID",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = modifierSummary(activeModifier),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PaddingSmall),
                    verticalArrangement = Arrangement.spacedBy(PaddingSmall),
                ) {
                    ModifierChip("Ctrl", MOD_LEFT_CTRL, activeModifier) { activeModifier = it }
                    ModifierChip("Shift", MOD_LEFT_SHIFT, activeModifier) { activeModifier = it }
                    ModifierChip("Alt", MOD_LEFT_ALT, activeModifier) { activeModifier = it }
                    ModifierChip("GUI", MOD_LEFT_GUI, activeModifier) { activeModifier = it }
                    TextButton(onClick = { activeModifier = 0 }) {
                        Text("Clear")
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PaddingSmall),
                    verticalArrangement = Arrangement.spacedBy(PaddingSmall),
                ) {
                    for (key in quickKeys) {
                        Button(
                            onClick = { mainViewModel.addStandardKey(activeModifier.toByte(), key.keyCode) },
                        ) {
                            Text(key.label)
                        }
                    }
                }
            }
        }

        CollapsibleCard(
            title = "Advanced keys",
            subtitle = "Function keys, Delete/Home/End/Page keys",
            expanded = showAdvancedKeys,
            onExpandedChanged = { showAdvancedKeys = it },
        ) {
            KeyChipGroup(navigationKeys, activeModifier, mainViewModel)
            Spacer(Modifier.height(PaddingSmall))
            KeyChipGroup(functionKeys, activeModifier, mainViewModel)
        }

        CollapsibleCard(
            title = "Combos",
            subtitle = "Copy/paste, window switching, Ctrl+Alt+Del",
            expanded = showCombos,
            onExpandedChanged = { showCombos = it },
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PaddingSmall),
                verticalArrangement = Arrangement.spacedBy(PaddingSmall),
            ) {
                for (combo in commonCombos) {
                    AssistChip(
                        onClick = { mainViewModel.addStandardKey(combo.modifier, combo.keyCode) },
                        label = { Text(combo.label) },
                    )
                }
            }
        }

        CollapsibleCard(
            title = "AI / Macro input",
            subtitle = "Paste text and type it into the host",
            expanded = showMacro,
            onExpandedChanged = { showMacro = it },
        ) {
            Text(
                text = "This sends plain text as keystrokes. Camera OCR and LLM wiring are next; they are not active yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PaddingNormal * 5),
                value = macroText,
                onValueChange = { macroText = it },
                label = { Text("Text to type on host") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PaddingSmall),
            ) {
                Button(
                    enabled = macroText.isNotEmpty(),
                    onClick = { sendInput(macroText, mainViewModel) },
                ) {
                    Text("Send")
                }
                OutlinedButton(
                    enabled = false,
                    onClick = {},
                ) {
                    Text("OCR")
                }
                OutlinedButton(
                    enabled = false,
                    onClick = {},
                ) {
                    Text("LLM")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyChipGroup(
    keys: List<SpecialKey>,
    activeModifier: Int,
    mainViewModel: MainViewModel,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(PaddingSmall),
        verticalArrangement = Arrangement.spacedBy(PaddingSmall),
    ) {
        for (key in keys) {
            AssistChip(
                onClick = { mainViewModel.addStandardKey(activeModifier.toByte(), key.keyCode) },
                label = { Text(key.label) },
            )
        }
    }
}

@Composable
private fun CollapsibleCard(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(PaddingNormal),
            verticalArrangement = Arrangement.spacedBy(PaddingSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onExpandedChanged(!expanded) }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            if (expanded) {
                content()
            }
        }
    }
}

@Composable
private fun ModifierChip(
    label: String,
    bit: Int,
    activeModifier: Int,
    onModifierChanged: (Int) -> Unit,
) {
    val selected = activeModifier and bit != 0
    FilterChip(
        selected = selected,
        onClick = {
            onModifierChanged(
                if (selected) {
                    activeModifier and bit.inv()
                } else {
                    activeModifier or bit
                },
            )
        },
        label = { Text(label) },
    )
}

private fun modifierSummary(activeModifier: Int): String {
    val active = buildList {
        if (activeModifier and MOD_LEFT_CTRL != 0) add("Ctrl")
        if (activeModifier and MOD_LEFT_SHIFT != 0) add("Shift")
        if (activeModifier and MOD_LEFT_ALT != 0) add("Alt")
        if (activeModifier and MOD_LEFT_GUI != 0) add("GUI")
    }

    return if (active.isEmpty()) {
        "Tap a key, or hold modifiers first."
    } else {
        "Next key sends with ${active.joinToString(" + ")}."
    }
}
