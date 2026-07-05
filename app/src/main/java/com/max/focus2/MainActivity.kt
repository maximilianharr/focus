package com.max.focus2

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

val Dark = Color(0xFF23262B)
val Bright = Color(0xFFF4F5F7)
val MidGray = Color(0xFFE2E4E8)
val DimDark = Color(0xFF6A6F78)

class MainActivity : ComponentActivity() {
    companion object {
        @Volatile var visible = false
        val openPerms = mutableStateOf(false)
    }

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startService(Intent(this, DnsVpnService::class.java))
        }
    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // status bar sits on the Dark top bar -> use light icons
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        if (intent?.getBooleanExtra("perm", false) == true) openPerms.value = true
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Dark, onPrimary = Bright,
                    background = Bright, onBackground = Dark,
                    surface = Bright, onSurface = Dark,
                    secondaryContainer = MidGray, onSecondaryContainer = Dark,
                )
            ) { Main(this) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("perm", false)) openPerms.value = true
    }

    override fun onResume() {
        super.onResume()
        visible = true
        lifecycleScope.launch {
            App.prefs.edit {
                it[Prefs.SHUTDOWN] = false
                // edit mode idle timeout: expired while away -> revoke,
                // otherwise reopening resets the timer
                val idle = it[Prefs.IDLE] ?: 0L
                val timeoutMs = ((it[Prefs.EDIT_TIMEOUT] ?: 5f) * 60_000).toLong()
                if (idle > 0 && System.currentTimeMillis() > idle + timeoutMs)
                    it[Prefs.EDIT] = false
                it[Prefs.IDLE] = 0L
            }
        }
        try {
            startForegroundService(Intent(this, WatchdogService::class.java))
        } catch (_: Exception) {
        }
        if (VpnService.prepare(this) == null) startService(Intent(this, DnsVpnService::class.java))
    }

    override fun onPause() {
        visible = false
        lifecycleScope.launch {
            App.prefs.edit { if (it[Prefs.EDIT] == true) it[Prefs.IDLE] = System.currentTimeMillis() }
        }
        super.onPause()
    }

    fun requestVpn() {
        val i = VpnService.prepare(this)
        if (i != null) vpnConsent.launch(i) else startService(Intent(this, DnsVpnService::class.java))
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }
}

private fun fmt(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun fmtTime(min: Int) = "%02d:%02d".format(min / 60, min % 60)

@Composable
fun Main(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val items by App.dao.items().collectAsState(emptyList())
    val windows by App.dao.windows().collectAsState(emptyList())
    val prefs by App.prefs.flow.collectAsState(null)
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var showPerms by MainActivity.openPerms
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    val editOn = prefs?.get(Prefs.EDIT) ?: false
    val shutdown = prefs?.get(Prefs.SHUTDOWN) ?: false
    val focusOn = remember(now, shutdown) {
        BlockerService.running && !shutdown && !Engine.cheatActive()
    }

    Scaffold(
        containerColor = Bright,
        topBar = {
            Column(Modifier.background(Dark).statusBarsPadding()) {
                StatusRow("Focus", focusOn)
                StatusRow("Edit", editOn)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MidGray) {
                NavigationBarItem(tab == 0, { tab = 0; showPerms = false },
                    { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Block List") })
                NavigationBarItem(tab == 1, { tab = 1; showPerms = false },
                    { Icon(Icons.Default.DateRange, null) }, label = { Text("Schedule") })
                NavigationBarItem(tab == 2, { tab = 2; showPerms = false },
                    { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        },
        floatingActionButton = {
            if (!showPerms && tab < 2) {
                val active = tab == 0 || editOn
                FloatingActionButton(
                    containerColor = if (active) Dark else MidGray,
                    contentColor = if (active) Bright else DimDark,
                    onClick = {
                        if (tab == 0) showAdd = true
                        else if (editOn) scope.launch(Dispatchers.IO) {
                            val today = 1 shl (LocalDate.now().dayOfWeek.value - 1)
                            App.dao.insertWindow(CheatWindow(0, 12 * 60, 13 * 60, today))
                        }
                    }
                ) { Icon(Icons.Default.Add, "Add") }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                showPerms -> PermissionsScreen(activity, now) { showPerms = false }
                tab == 0 -> BlockListTab(items, editOn, scope, now)
                tab == 1 -> ScheduleTab(windows, editOn, scope)
                else -> SettingsTab(activity, prefs, now, scope)
            }
        }
    }

    if (showAdd) AddDialog(editOn, scope) { showAdd = false }
}

@Composable
fun StatusRow(label: String, on: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(if (on) Dark else MidGray)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            "$label: ${if (on) "ON" else "OFF"}",
            color = if (on) Bright else DimDark,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun BlockListTab(items: List<BlockItem>, editOn: Boolean, scope: CoroutineScope, now: Long) {
    val apps = items.filter { it.type == TYPE_APP }
    val sites = items.filter { it.type == TYPE_SITE }
    var editing by remember { mutableStateOf<BlockItem?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader("Apps") }
        if (apps.isEmpty()) item { EmptyHint("No blocked apps") }
        items(apps, key = { it.value }) { BlockRow(it, editOn, scope, now) { editing = it } }
        item { HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DimDark) }
        item { SectionHeader("Websites") }
        if (sites.isEmpty()) item { EmptyHint("No blocked websites") }
        items(sites, key = { it.value }) { BlockRow(it, editOn, scope, now) { editing = it } }
        item { Spacer(Modifier.height(80.dp)) }
    }
    editing?.let { AllowanceDialog(it, editOn, scope) { editing = null } }
}

@Composable
fun AllowanceDialog(item: BlockItem, editOn: Boolean, scope: CoroutineScope, onDismiss: () -> Unit) {
    var allowance by remember { mutableIntStateOf(item.allowance) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bright,
        title = { Text(item.label, color = Dark) },
        text = {
            Column {
                AllowanceSlider(allowance) { allowance = it }
                if (!editOn) Text(
                    "Without edit mode the allowance can only be lowered.",
                    style = MaterialTheme.typography.bodySmall, color = DimDark,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) { addBlock(item.copy(allowance = allowance), editOn) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AllowanceSlider(allowance: Int, onChange: (Int) -> Unit) {
    Text(
        "Daily allowance: " +
            if (allowance == 0) "0 min (fully blocked)" else "$allowance min",
        color = Dark,
    )
    Slider(
        allowance.toFloat(), { onChange((it / 5).roundToInt() * 5) },
        valueRange = 0f..120f, steps = 23,
    )
}

@Composable
fun SectionHeader(t: String) = Text(
    t, Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Dark,
)

@Composable
fun EmptyHint(t: String) =
    Text(t, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = DimDark)

@Composable
fun BlockRow(item: BlockItem, editOn: Boolean, scope: CoroutineScope, now: Long, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.label, fontWeight = FontWeight.Medium, color = Dark)
            if (item.value != item.label)
                Text(item.value, style = MaterialTheme.typography.bodySmall, color = DimDark)
        }
        Text(
            if (item.allowance == 0) "blocked"
            else "${Engine.remainingMin(item, now)}/${item.allowance} min",
            style = MaterialTheme.typography.bodySmall, color = DimDark,
        )
        IconButton(
            onClick = { scope.launch(Dispatchers.IO) { App.dao.deleteItem(item.value) } },
            enabled = editOn,
        ) { Icon(Icons.Default.Delete, "Delete", tint = if (editOn) Dark else MidGray) }
    }
}

@Composable
fun AddDialog(editOn: Boolean, scope: CoroutineScope, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var dtab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var site by remember { mutableStateOf("") }
    var allowance by remember { mutableIntStateOf(0) }
    val selected = remember { mutableStateListOf<String>() }
    val apps by produceState(emptyList<Triple<String, String, ImageBitmap>>()) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).map {
                Triple(
                    it.activityInfo.packageName,
                    it.loadLabel(pm).toString(),
                    it.loadIcon(pm).toImageBitmap(),
                )
            }
                .distinctBy { it.first }
                .filter { it.first != ctx.packageName }
                .sortedBy { it.second.lowercase() }
        }
    }
    val labels = remember(apps) { apps.associate { it.first to it.second } }
    val canAdd = if (dtab == 0) selected.isNotEmpty() else normalizeHost(site).contains(".")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bright,
        title = { Text("Add a block", color = Dark) },
        text = {
            Column {
                TabRow(dtab, containerColor = Bright, contentColor = Dark) {
                    Tab(dtab == 0, { dtab = 0 }, text = { Text("App") })
                    Tab(dtab == 1, { dtab = 1 }, text = { Text("Website") })
                }
                Spacer(Modifier.height(8.dp))
                if (dtab == 0) {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth(),
                        label = { Text("Search apps") }, singleLine = true,
                    )
                    LazyColumn(Modifier.height(240.dp)) {
                        items(apps.filter { query.isBlank() || it.second.contains(query, true) },
                            key = { it.first }) { (pkg, label, icon) ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (pkg in selected) MidGray else Bright)
                                    .clickable {
                                        if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(icon, null, Modifier.size(32.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(label, color = Dark)
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        site, { site = it }, Modifier.fillMaxWidth(),
                        label = { Text("Website (e.g. tiktok.com)") }, singleLine = true,
                    )
                }
                Spacer(Modifier.height(12.dp))
                AllowanceSlider(allowance) { allowance = it }
            }
        },
        confirmButton = {
            TextButton(enabled = canAdd, onClick = {
                scope.launch(Dispatchers.IO) {
                    if (dtab == 0) selected.forEach { pkg ->
                        addBlock(BlockItem(pkg, TYPE_APP, labels[pkg] ?: pkg, allowance), editOn)
                    } else {
                        val host = normalizeHost(site)
                        addBlock(BlockItem(host, TYPE_SITE, host, allowance), editOn)
                    }
                }
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ponytail: fixed 96px render, adaptive icons look fine at 32dp
private fun Drawable.toImageBitmap(): ImageBitmap {
    val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    setBounds(0, 0, c.width, c.height)
    draw(c)
    return b.asImageBitmap()
}

// ponytail: re-adding an existing item outside edit mode can only lower its
// allowance, otherwise re-adding would be a loophole to loosen a block.
private fun addBlock(new: BlockItem, editOn: Boolean) {
    val existing = Engine.items.find { it.value == new.value }
    val allowance =
        if (existing != null && !editOn) minOf(existing.allowance, new.allowance)
        else new.allowance
    App.dao.insertItem(new.copy(allowance = allowance))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleTab(windows: List<CheatWindow>, editOn: Boolean, scope: CoroutineScope) {
    var picking by remember { mutableStateOf<Pair<CheatWindow, Boolean>?>(null) } // window, isStart
    LazyColumn(Modifier.fillMaxSize()) {
        if (windows.isEmpty()) item {
            EmptyHint(
                if (editOn) "No time windows. Tap + to add a cheat window."
                else "No time windows. Enter edit mode to add one."
            )
        }
        items(windows, key = { it.id }) { w ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .background(MidGray, RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f)) {
                        "MTWTFSS".forEachIndexed { i, c ->
                            val on = w.days and (1 shl i) != 0
                            Box(
                                Modifier.padding(end = 6.dp).size(28.dp)
                                    .background(if (on) Dark else Bright, CircleShape)
                                    .clickable(enabled = editOn) {
                                        scope.launch(Dispatchers.IO) {
                                            App.dao.insertWindow(w.copy(days = w.days xor (1 shl i)))
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    c.toString(), color = if (on) Bright else DimDark,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    if (editOn) IconButton({
                        scope.launch(Dispatchers.IO) { App.dao.deleteWindow(w.id) }
                    }) { Icon(Icons.Default.Close, "Delete", tint = Dark) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        fmtTime(w.startMin),
                        Modifier.clickable(enabled = editOn) { picking = w to true },
                        style = MaterialTheme.typography.headlineSmall, color = Dark,
                    )
                    Text("  –  ", color = DimDark)
                    Text(
                        fmtTime(w.endMin),
                        Modifier.clickable(enabled = editOn) { picking = w to false },
                        style = MaterialTheme.typography.headlineSmall, color = Dark,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }

    picking?.let { (w, isStart) ->
        val state = rememberTimePickerState(
            (if (isStart) w.startMin else w.endMin) / 60,
            (if (isStart) w.startMin else w.endMin) % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            containerColor = Bright,
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val m = state.hour * 60 + state.minute
                    scope.launch(Dispatchers.IO) {
                        App.dao.insertWindow(if (isStart) w.copy(startMin = m) else w.copy(endMin = m))
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SettingsTab(
    activity: MainActivity,
    prefs: androidx.datastore.preferences.core.Preferences?,
    now: Long,
    scope: CoroutineScope,
) {
    val ctx = LocalContext.current
    val editOn = prefs?.get(Prefs.EDIT) ?: false
    val arm = prefs?.get(Prefs.ARM) ?: 0L
    val cooldownMs = ((prefs?.get(Prefs.COOLDOWN) ?: 0.1f) * 60_000).toLong()
    val confirmMs = ((prefs?.get(Prefs.CONFIRM) ?: 1f) * 60_000).toLong()
    var showTimers by remember { mutableStateOf(false) }

    // missed confirm window -> lapse back to "Turn On"
    LaunchedEffect(now, arm) {
        if (arm > 0 && now > arm + cooldownMs + confirmMs)
            App.prefs.edit { it[Prefs.ARM] = 0L }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val btnMod = Modifier.fillMaxWidth()
        when {
            editOn -> SettingsButton("Turn Off", btnMod, container = Dark) {
                scope.launch { App.prefs.edit { it[Prefs.EDIT] = false; it[Prefs.ARM] = 0L } }
            }
            arm == 0L -> SettingsButton("Turn On", btnMod) {
                scope.launch { App.prefs.edit { it[Prefs.ARM] = System.currentTimeMillis() } }
            }
            now < arm + cooldownMs -> SettingsButton(
                "Cooldown ${fmt(arm + cooldownMs - now)}", btnMod, container = DimDark,
            ) { scope.launch { App.prefs.edit { it[Prefs.ARM] = 0L } } }
            else -> SettingsButton(
                "Confirm: ${fmt(arm + cooldownMs + confirmMs - now)}", btnMod,
                container = if ((now / 500) % 2 == 0L) Dark else DimDark,
            ) { scope.launch { App.prefs.edit { it[Prefs.EDIT] = true; it[Prefs.ARM] = 0L } } }
        }
        Text(
            "Edit mode unlocks removing blocks, schedule changes, timers, import and shutdown.",
            style = MaterialTheme.typography.bodySmall, color = DimDark,
        )

        SettingsButton("Set timers", btnMod, enabled = editOn) { showTimers = true }
        SettingsButton("Permissions", btnMod) { MainActivity.openPerms.value = true }

        val noColor = prefs?.get(Prefs.NO_COLOR) ?: false
        val noInternet = prefs?.get(Prefs.NO_INTERNET) ?: false
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleChip("No color", noColor, editOn, Modifier.weight(1f)) { on ->
                Engine.noColor = on // apply now, don't wait for the flow
                applyNoColor(ctx)
                scope.launch { App.prefs.edit { it[Prefs.NO_COLOR] = on } }
            }
            ToggleChip("No internet", noInternet, editOn, Modifier.weight(1f)) { on ->
                Engine.noInternet = on
                scope.launch { App.prefs.edit { it[Prefs.NO_INTERNET] = on } }
                if (DnsVpnService.running) activity.startService(
                    Intent(activity, DnsVpnService::class.java).setAction("reconfig")
                )
            }
        }
        if (noColor && ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) Text(
            "No color needs: adb shell pm grant ${ctx.packageName} " +
                "android.permission.WRITE_SECURE_SETTINGS",
            style = MaterialTheme.typography.bodySmall, color = DimDark,
        )

        SectionHeader("Backup")
        BackupRow(activity, prefs, editOn, scope)

        Spacer(Modifier.weight(1f))
        SettingsButton("Shutdown", btnMod, enabled = editOn, container = Dark) {
            scope.launch {
                App.prefs.edit { it[Prefs.SHUTDOWN] = true }
                activity.startService(
                    Intent(activity, DnsVpnService::class.java).setAction("stop")
                )
                activity.stopService(Intent(activity, WatchdogService::class.java))
                activity.finishAffinity()
            }
        }
    }

    if (showTimers) TimersDialog(prefs, scope) { showTimers = false }
}

// Always switchable on; switching off needs edit mode.
@Composable
fun ToggleChip(
    label: String,
    checked: Boolean,
    editOn: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier.background(MidGray, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = Dark, fontWeight = FontWeight.Medium)
        Switch(
            checked, onChange, enabled = !checked || editOn,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Dark, checkedThumbColor = Bright,
                disabledCheckedTrackColor = DimDark, disabledCheckedThumbColor = MidGray,
            ),
        )
    }
}

@Composable
fun SettingsButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = Dark,
    onClick: () -> Unit,
) {
    Button(
        onClick, modifier, enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = Bright,
            disabledContainerColor = MidGray, disabledContentColor = DimDark,
        ),
        shape = RoundedCornerShape(12.dp),
    ) { Text(text, Modifier.padding(vertical = 6.dp)) }
}

@Composable
fun BackupRow(
    activity: MainActivity,
    prefs: androidx.datastore.preferences.core.Preferences?,
    editOn: Boolean,
    scope: CoroutineScope,
) {
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            val json = exportJson(prefs)
            scope.launch(Dispatchers.IO) {
                activity.contentResolver.openOutputStream(it)?.use { o ->
                    o.write(json.toByteArray())
                }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val text = activity.contentResolver.openInputStream(it)
                        ?.bufferedReader()?.readText() ?: return@launch
                    importJson(text, App.dao, App.prefs)
                } catch (_: Exception) {
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsButton("Export", Modifier.weight(1f)) {
            exporter.launch("focus-backup.json")
        }
        SettingsButton("Import", Modifier.weight(1f), enabled = editOn) {
            importer.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }
}

@Composable
fun TimersDialog(
    prefs: androidx.datastore.preferences.core.Preferences?,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    var cd by remember { mutableStateOf((prefs?.get(Prefs.COOLDOWN) ?: 0.1f).toString()) }
    var cf by remember { mutableStateOf((prefs?.get(Prefs.CONFIRM) ?: 1f).toString()) }
    var et by remember { mutableStateOf((prefs?.get(Prefs.EDIT_TIMEOUT) ?: 5f).toString()) }
    var confirmOdd by remember { mutableStateOf(false) }

    fun parse(s: String) = s.trim().replace(',', '.').toFloatOrNull()
    fun save() {
        val c = parse(cd) ?: return
        val f = parse(cf) ?: return
        val t = parse(et) ?: return
        scope.launch {
            App.prefs.edit {
                it[Prefs.COOLDOWN] = "%.1f".format(c.coerceIn(0f, 999f)).toFloat()
                it[Prefs.CONFIRM] = "%.1f".format(f.coerceIn(0.1f, 999f)).toFloat()
                it[Prefs.EDIT_TIMEOUT] = "%.1f".format(t.coerceIn(0.1f, 999f)).toFloat()
            }
        }
        onDismiss()
    }

    if (confirmOdd) {
        AlertDialog(
            onDismissRequest = { confirmOdd = false },
            containerColor = Bright,
            title = { Text("Are you sure?") },
            text = {
                Text(
                    "A cooldown above 30 minutes or a confirm window below " +
                        "1 minute makes edit mode hard to reach."
                )
            },
            confirmButton = { TextButton(onClick = { save() }) { Text("Set anyway") } },
            dismissButton = { TextButton(onClick = { confirmOdd = false }) { Text("Back") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Bright,
        title = { Text("Set timers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cd, { cd = it }, label = { Text("Cooldown (minutes)") }, singleLine = true)
                OutlinedTextField(cf, { cf = it }, label = { Text("Confirm window (minutes)") }, singleLine = true)
                OutlinedTextField(et, { et = it }, label = { Text("Edit mode time (minutes)") }, singleLine = true)
                Text("One decimal allowed, e.g. 0.1 = 6 seconds. Edit mode time: " +
                    "how long edit mode survives while the app is not open.",
                    style = MaterialTheme.typography.bodySmall, color = DimDark)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = parse(cd)
                val f = parse(cf)
                if (c == null || f == null || parse(et) == null) return@TextButton
                if (c > 30f || f < 1f) confirmOdd = true else save()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PermissionsScreen(activity: MainActivity, now: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Dark) }
            Text("Permissions", style = MaterialTheme.typography.titleLarge, color = Dark)
        }
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        remember(now) { 0 } // recheck statuses on each tick
        PermRow("Accessibility service", BlockerService.running) {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PermRow("VPN (website blocking)", VpnService.prepare(ctx) == null) {
            activity.requestVpn()
        }
        PermRow("Device admin", dpm?.isAdminActive(admin) == true) {
            ctx.startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Prevents casual uninstall of Focus."
                    )
            )
        }
        PermRow("Notifications", nm?.areNotificationsEnabled() == true) {
            activity.requestNotifications()
        }
        PermRow("Display over other apps", Settings.canDrawOverlays(ctx)) {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")
                )
            )
        }
    }
}

@Composable
fun PermRow(name: String, ok: Boolean, fix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Dark, fontWeight = FontWeight.Medium)
            Text(
                if (ok) "Granted" else "Missing",
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) DimDark else Dark,
                fontWeight = if (ok) FontWeight.Normal else FontWeight.Bold,
            )
        }
        if (!ok) SettingsButton("Fix", onClick = fix)
    }
}
