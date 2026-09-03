package app.slimboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.slimboard.R
import app.slimboard.SlimBoardService
import app.slimboard.theme.KeyboardTheme

/**
 * Launcher + settings screen. The only Compose code in the app; it is never loaded while just typing.
 * Every control writes straight to Prefs; the IME service picks changes up live.
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private val imeEnabled = mutableStateOf(false)
    private val imeActive = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        setContent {
            SlimBoardTheme(prefs) {
                SettingsScreen(
                    prefs = prefs,
                    imeEnabled = imeEnabled.value,
                    imeActive = imeActive.value,
                    onOpenImeSettings = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onPickIme = { imm().showInputMethodPicker() },
                    versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "",
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val imeId = ComponentName(this, SlimBoardService::class.java).flattenToShortString()
        imeEnabled.value = imm().enabledInputMethodList.any { it.id == imeId }
        imeActive.value = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == imeId
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}

@Composable
private fun SlimBoardTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (prefs.themeMode) {
        KeyboardTheme.MODE_LIGHT -> false
        KeyboardTheme.MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.dynamicColor ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    prefs: Prefs,
    imeEnabled: Boolean,
    imeActive: Boolean,
    onOpenImeSettings: () -> Unit,
    onPickIme: () -> Unit,
    versionName: String,
) {
    // Theme changes must recompose the whole screen, so they live at this level.
    var themeMode by remember { mutableStateOf(prefs.themeMode) }
    var dynamicColor by remember { mutableStateOf(prefs.dynamicColor) }

    Scaffold(topBar = { TopAppBar(title = { Text("SlimBoard") }) }) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            SetupCard(imeEnabled, imeActive, onOpenImeSettings, onPickIme)

            SectionHeader("Appearance")
            Text("Theme", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                for ((mode, label) in listOf(
                    KeyboardTheme.MODE_SYSTEM to "System",
                    KeyboardTheme.MODE_LIGHT to "Light",
                    KeyboardTheme.MODE_DARK to "Dark",
                )) {
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { themeMode = mode; prefs.themeMode = mode },
                        label = { Text(label) },
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow("Material You colours", "Follow the wallpaper palette", dynamicColor) {
                    dynamicColor = it; prefs.dynamicColor = it
                }
            }
            PrefSwitch("Key borders", "Outline every key", prefs.keyBorders) { prefs.keyBorders = it }
            PrefSwitch("Toolbar", "Clipboard, emoji, editing and settings buttons above the keys", prefs.toolbar) { prefs.toolbar = it }
            PrefSwitch("One-handed mode", "Narrow keyboard you can park on either side", prefs.oneHanded) { prefs.oneHanded = it }
            PrefSlider("Keyboard height", prefs.heightScale, 80f..130f, 9, { "$it%" }) { prefs.heightScale = it }
            PrefSlider("Bottom padding", prefs.bottomPadding, 0f..40f, 7, { "$it dp" }) { prefs.bottomPadding = it }

            SectionHeader("Layout")
            PrefSwitch("Number row", "Digits above the letters", prefs.numberRow) { prefs.numberRow = it }

            SectionHeader("Typing")
            PrefSwitch("Auto-capitalisation", "Shift at the start of sentences", prefs.autoCap) { prefs.autoCap = it }
            PrefSwitch("Double-space period", "Two spaces insert \". \"", prefs.doubleSpacePeriod) { prefs.doubleSpacePeriod = it }
            PrefSwitch("Swipe space to move cursor", null, prefs.spaceCursor) { prefs.spaceCursor = it }
            PrefSwitch("Swipe backspace to delete words", null, prefs.backspaceSwipe) { prefs.backspaceSwipe = it }
            PrefSlider("Long-press delay", prefs.longPressMs, 200f..500f, 5, { "$it ms" }) { prefs.longPressMs = it }
            PrefSwitch("Incognito", "Never learn from typing (has effect once suggestions exist)", prefs.incognito) { prefs.incognito = it }

            SectionHeader("Suggestions")
            PrefSwitch("Show suggestions", "Word completions and corrections above the keys", prefs.suggestions) { prefs.suggestions = it }
            PrefSwitch("Auto-correct", "Fix the word when you press space. Backspace once to undo.", prefs.autocorrect) { prefs.autocorrect = it }
            PrefSwitch("Learn new words", "Words you type twice are remembered on this device only", prefs.learnWords) { prefs.learnWords = it }
            LearnedWords()

            SectionHeader("Text shortcuts")
            Text("Type the shortcut and press space to insert the full text.", style = MaterialTheme.typography.bodySmall)
            Shortcuts(prefs)

            SectionHeader("Apps")
            Text("Turn off learning in apps where you type things you'd rather not have remembered.", style = MaterialTheme.typography.bodySmall)
            NoLearnApps(prefs)

            SectionHeader("Backup")
            BackupRestore()

            SectionHeader("Clipboard")
            var clipboardEnabled by remember { mutableStateOf(prefs.clipboardEnabled) }
            SwitchRow("Clipboard history", "Keep what you copy, in the keyboard", clipboardEnabled) {
                clipboardEnabled = it; prefs.clipboardEnabled = it
            }
            if (clipboardEnabled) {
                PrefSwitch("Save images", "Copied images too, up to ${prefs.clipboardMaxImageMb} MB each", prefs.clipboardImages) { prefs.clipboardImages = it }
                Text("Auto-clear unpinned items after", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                var expiry by remember { mutableStateOf(prefs.clipboardExpiryHours) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    for ((hours, label) in listOf(1 to "1 h", 6 to "6 h", 24 to "1 day", 168 to "7 days", 0 to "Never")) {
                        FilterChip(
                            selected = expiry == hours,
                            onClick = { expiry = hours; prefs.clipboardExpiryHours = hours },
                            label = { Text(label) },
                        )
                    }
                }
                Text("Pinned items are never auto-cleared.", style = MaterialTheme.typography.bodySmall)
                val context = LocalContext.current
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { app.slimboard.clipboard.ClipboardStore.get(context).clearUnpinned() }) { Text("Clear unpinned") }
                    Button(onClick = { app.slimboard.clipboard.ClipboardStore.get(context).clearAll() }) { Text("Delete all") }
                }
            }

            SectionHeader("Feedback")
            PrefSwitch("Key preview", "Show the character above the key while pressed", prefs.keyPreview) { prefs.keyPreview = it }
            PrefSwitch("Vibrate on key press", null, prefs.haptics) { prefs.haptics = it }
            PrefSwitch("Sound on key press", "Uses the system keyboard sound", prefs.sound) { prefs.sound = it }

            SectionHeader("Try it")
            var text by remember { mutableStateOf("") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Text field") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.height(8.dp))
            var number by remember { mutableStateOf("") }
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Number field") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(8.dp))
            var email by remember { mutableStateOf("") }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email field") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )

            SectionHeader("About")
            Text("SlimBoard $versionName", style = MaterialTheme.typography.bodyMedium)
            Text(
                "No permissions. No network. Nothing you type or copy leaves this device.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun SetupCard(imeEnabled: Boolean, imeActive: Boolean, onOpenImeSettings: () -> Unit, onPickIme: () -> Unit) {
    if (imeEnabled && imeActive) return
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(androidx.compose.ui.res.stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(androidx.compose.ui.res.stringResource(R.string.setup_step_enable), style = MaterialTheme.typography.bodyMedium)
            Text(
                androidx.compose.ui.res.stringResource(if (imeEnabled) R.string.setup_status_enabled else R.string.setup_status_not_enabled),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!imeEnabled) Button(onClick = onOpenImeSettings, modifier = Modifier.padding(top = 4.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.setup_enable_button))
            }
            Spacer(Modifier.height(12.dp))
            Text(androidx.compose.ui.res.stringResource(R.string.setup_step_select), style = MaterialTheme.typography.bodyMedium)
            Text(
                androidx.compose.ui.res.stringResource(if (imeActive) R.string.setup_status_active else R.string.setup_status_not_active),
                style = MaterialTheme.typography.bodySmall,
            )
            if (!imeActive) Button(onClick = onPickIme, modifier = Modifier.padding(top = 4.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.setup_select_button))
            }
        }
    }
}

/** Expandable list of words the keyboard has learned, each removable. */
@Composable
private fun LearnedWords() {
    val context = LocalContext.current
    val dictionary = remember { app.slimboard.text.PersonalDictionary.get(context) }
    val words = remember { androidx.compose.runtime.mutableStateListOf<String>().also { l -> l.addAll(dictionary.all().map { it.first }) } }
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Learned words", style = MaterialTheme.typography.bodyLarge)
            Text("${words.size} words", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
    }
    if (expanded) {
        if (words.isEmpty()) Text("Nothing learned yet.", style = MaterialTheme.typography.bodySmall)
        for (w in words.toList()) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(w, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "Remove",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable { dictionary.forget(w); words.remove(w) },
                )
            }
        }
        if (words.isNotEmpty()) {
            Button(onClick = { dictionary.clear(); words.clear() }, modifier = Modifier.padding(top = 8.dp)) { Text("Forget all") }
        }
    }
}

@Composable
private fun Shortcuts(prefs: Prefs) {
    val items = remember { androidx.compose.runtime.mutableStateListOf<Pair<String, String>>().also { l -> l.addAll(prefs.shortcuts.toList()) } }
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    fun save() { prefs.shortcuts = items.associate { it } }
    for ((k, v) in items.toList()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(k, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(96.dp))
            Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
            Text(
                "Remove",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 12.dp).clickable { items.remove(k to v); save() },
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim().lowercase().filter { c -> c.isLetterOrDigit() } },
            label = { Text("Shortcut") },
            singleLine = true,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Expands to") },
            modifier = Modifier.weight(1f),
        )
    }
    Button(
        onClick = {
            if (key.isNotEmpty() && value.isNotBlank()) {
                items.removeAll { it.first == key }
                items.add(key to value.trim())
                save()
                key = ""; value = ""
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text("Add shortcut") }
}

@Composable
private fun NoLearnApps(prefs: Prefs) {
    val apps = remember { prefs.appsSeen.toList().sortedBy { it.second.lowercase() } }
    val blocked = remember { androidx.compose.runtime.mutableStateListOf<String>().also { it.addAll(prefs.noLearnApps) } }
    if (apps.isEmpty()) {
        Text("Apps appear here after you type in them.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        return
    }
    for ((pkg, label) in apps) {
        SwitchRow(label, if (label != pkg) pkg else null, pkg in blocked) { on ->
            if (on) blocked.add(pkg) else blocked.remove(pkg)
            prefs.noLearnApps = blocked.toSet()
        }
    }
}

@Composable
private fun BackupRestore() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            status = try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(Backup.export(context).toByteArray()) }
                "Backup saved"
            } catch (e: Exception) { "Backup failed: ${e.message}" }
        }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            status = try {
                val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                Backup.import(context, json)
            } catch (e: Exception) { "Restore failed: ${e.message}" }
        }
    }
    Text("Settings, shortcuts, learned words and text clips. Saved as a file you choose; nothing leaves the device.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Button(onClick = { exporter.launch("slimboard-backup.json") }) { Text("Export") }
        Button(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
    }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SectionHeader(title: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A switch whose state is seeded from Prefs and written back on change. */
@Composable
private fun PrefSwitch(title: String, subtitle: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    SwitchRow(title, subtitle, checked) { checked = it; onChange(it) }
}

@Composable
private fun PrefSlider(
    title: String,
    initial: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(initial.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(value.toInt()), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(value.toInt()) },
            valueRange = range,
            steps = steps,
        )
    }
}
