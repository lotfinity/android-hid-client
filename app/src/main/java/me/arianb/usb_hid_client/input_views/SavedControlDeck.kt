package me.arianb.usb_hid_client.input_views

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.arianb.usb_hid_client.settings.SettingsViewModel
import me.arianb.usb_hid_client.MainViewModel
import me.arianb.usb_hid_client.hid_utils.KeyCodeTranslation
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class DeckButton(val id: String, val label: String, val text: String = "", val shortcut: Int = -1)
private data class DeckShortcut(val label: String, val modifier: Byte, val key: Byte)
private val deckShortcuts = listOf(
    DeckShortcut("Copy", 1, 0x06), DeckShortcut("Paste", 1, 0x19),
    DeckShortcut("Cut", 1, 0x1b), DeckShortcut("Undo", 1, 0x1d),
    DeckShortcut("Switch window", 4, 0x2b), DeckShortcut("Address bar", 1, 0x0f),
    DeckShortcut("Save", 1, 0x16), DeckShortcut("Select all", 1, 0x04),
    DeckShortcut("Next slide", 0, 0x4e), DeckShortcut("Previous slide", 0, 0x4b),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedControlDeck(vm: MainViewModel) {
    val settings: SettingsViewModel = viewModel()
    val userPreferences by settings.userPreferencesFlow.collectAsState()
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("control_deck", Context.MODE_PRIVATE) }
    var buttons by remember {
        mutableStateOf(runCatching {
            val array = JSONArray(preferences.getString("buttons", "[]"))
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                DeckButton(item.getString("id"), item.getString("label"), item.optString("text"), item.optInt("shortcut", -1))
            }
        }.getOrDefault(emptyList()))
    }
    fun save(updated: List<DeckButton>) {
        buttons = updated
        val array = JSONArray()
        updated.forEach { button -> array.put(JSONObject().put("id", button.id).put("label", button.label)
            .put("text", button.text).put("shortcut", button.shortcut)) }
        preferences.edit().putString("buttons", array.toString()).apply()
    }
    var editing by remember { mutableStateOf<DeckButton?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var shortcut by remember { mutableIntStateOf(-1) }
    var picker by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    fun edit(button: DeckButton?) {
        editing = button; label = button?.label ?: ""; text = button?.text ?: ""
        shortcut = button?.shortcut ?: -1; showEditor = true
    }
    fun supported(value: String) = value.all { KeyCodeTranslation.keyCharToScanCodes(it) != null }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Shortcuts", style = MaterialTheme.typography.titleMedium)
        Text("These shortcuts use Windows / Linux key combinations. Saved text uses the host's keyboard layout.",
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            deckShortcuts.forEach { action ->
                Button(onClick = { vm.addStandardKey(action.modifier, action.key) }) { Text(action.label) }
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("My buttons", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { edit(null) }) { Text("Add button") }
        }
        if (buttons.isEmpty()) Text("Save a text snippet or shortcut here for one-tap access.")
        buttons.forEach { button ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Button(modifier = Modifier.weight(1f), enabled = button.shortcut in deckShortcuts.indices || (button.text.isNotEmpty() && supported(button.text)), onClick = {
                        if (button.shortcut in deckShortcuts.indices) {
                            val action = deckShortcuts[button.shortcut]; vm.addStandardKey(action.modifier, action.key)
                        } else sendInput(button.text, vm)
                    }) { Text(button.label) }
                    TextButton(onClick = { edit(button) }) { Text("Edit") }
                }
            }
        }
        Text("Send text", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = draft, onValueChange = { draft = it.take(2000) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Text to type on your computer") }, minLines = 2,
            isError = !supported(draft), supportingText = { Text(if (!supported(draft)) "Some characters cannot be sent with this keyboard layout." else "Sent as keystrokes; no Enter is added.") })
        Button(enabled = draft.isNotEmpty() && supported(draft), onClick = { sendInput(draft, vm); if (userPreferences.clearManualInput) draft = "" }) { Text("Send text") }
    }
    if (showEditor) AlertDialog(
        onDismissRequest = { showEditor = false }, title = { Text(if (editing == null) "Add button" else "Edit button") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, label = { Text("Button name") }, singleLine = true)
                Box {
                    TextButton(onClick = { picker = true }) { Text(if (shortcut in deckShortcuts.indices) deckShortcuts[shortcut].label else "Text snippet") }
                    DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
                        DropdownMenuItem(text = { Text("Text snippet") }, onClick = { shortcut = -1; picker = false })
                        deckShortcuts.forEachIndexed { index, action ->
                            DropdownMenuItem(text = { Text(action.label) }, onClick = { shortcut = index; picker = false })
                        }
                    }
                }
                if (shortcut == -1) OutlinedTextField(value = text, onValueChange = { text = it.take(2000) },
                    label = { Text("Text to type") }, minLines = 2, isError = !supported(text),
                    supportingText = { if (!supported(text)) Text("Contains unsupported keyboard characters.") })
                if (editing != null) TextButton(onClick = { save(buttons.filterNot { it.id == editing?.id }); showEditor = false }) { Text("Delete button") }
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank() && (shortcut in deckShortcuts.indices || (text.isNotEmpty() && supported(text))), onClick = {
                val button = DeckButton(editing?.id ?: UUID.randomUUID().toString(), label.trim(), if (shortcut == -1) text else "", shortcut)
                save(if (editing == null) buttons + button else buttons.map { if (it.id == button.id) button else it })
                showEditor = false
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } },
    )
}
