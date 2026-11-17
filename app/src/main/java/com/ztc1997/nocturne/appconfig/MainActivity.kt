package com.ztc1997.nocturne.appconfig

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ztc1997.nocturne.R
import com.ztc1997.nocturne.core.ConfigKeys
import com.ztc1997.nocturne.core.SleepWindow
import kotlinx.coroutines.launch

/** 设置页入口：原 View 构建实现已迁移到 Compose Material 3。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NocturneTheme {
                SettingsScreen()
            }
        }
    }
}

private data class OverrideOption(val labelRes: Int, val value: Int)

private val OVERRIDE_OPTIONS = listOf(
    OverrideOption(R.string.override_auto, ConfigKeys.OVERRIDE_AUTO),
    OverrideOption(R.string.override_on, ConfigKeys.OVERRIDE_ON),
    OverrideOption(R.string.override_off, ConfigKeys.OVERRIDE_OFF),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    var enabled by remember {
        mutableStateOf(ModulePreferences.getBoolean(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED))
    }
    var scheduleEnabled by remember {
        mutableStateOf(
            ModulePreferences.getBoolean(
                ConfigKeys.SCHEDULE_ENABLED, ConfigKeys.DEFAULT_SCHEDULE_ENABLED,
            ),
        )
    }
    var start by remember {
        mutableStateOf(
            ModulePreferences.getString(ConfigKeys.WINDOW_START, ConfigKeys.DEFAULT_WINDOW_START),
        )
    }
    var end by remember {
        mutableStateOf(
            ModulePreferences.getString(ConfigKeys.WINDOW_END, ConfigKeys.DEFAULT_WINDOW_END),
        )
    }
    var linkDnd by remember {
        mutableStateOf(ModulePreferences.getBoolean(ConfigKeys.LINK_DND, ConfigKeys.DEFAULT_LINK_DND))
    }
    var override by remember {
        mutableIntStateOf(ModulePreferences.getInt(ConfigKeys.MANUAL_OVERRIDE, ConfigKeys.OVERRIDE_AUTO))
    }
    var backendRes by remember { mutableIntStateOf(backendStatusRes()) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    // 观察者必须用同一个引用，保证 observe / unobserve 成对。
    DisposableEffect(Unit) {
        val observer: () -> Unit = { backendRes = backendStatusRes() }
        ModulePreferences.observe(observer)
        onDispose { ModulePreferences.unobserve(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 点击回调不是 Composable 上下文，提前把文案解析好。
    val invalidTimeMessage = stringResource(R.string.error_time_format)
    val savedMessage = stringResource(R.string.save_success)
    val saveFailedMessage = stringResource(R.string.save_failed)

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HeroCard(backendRes = backendRes)
            }
            item { SectionTitle(stringResource(R.string.section_trigger)) }
            item {
                SettingsCard {
                    SettingSwitchRow(
                        headline = stringResource(R.string.setting_enabled),
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                    )
                    SettingSwitchRow(
                        headline = stringResource(R.string.setting_schedule),
                        checked = scheduleEnabled,
                        onCheckedChange = { scheduleEnabled = it },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TimeField(
                            label = stringResource(R.string.time_start),
                            value = start,
                            onClick = { showStartPicker = true },
                            modifier = Modifier.weight(1f),
                        )
                        TimeField(
                            label = stringResource(R.string.time_end),
                            value = end,
                            onClick = { showEndPicker = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SettingSwitchRow(
                        headline = stringResource(R.string.setting_link_dnd),
                        checked = linkDnd,
                        onCheckedChange = { linkDnd = it },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.section_manual_override)) }
            item {
                SettingsCard {
                    OVERRIDE_OPTIONS.forEach { option ->
                        ListItem(
                            headlineContent = { Text(stringResource(option.labelRes)) },
                            leadingContent = {
                                RadioButton(
                                    selected = override == option.value,
                                    onClick = null,
                                )
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            modifier = Modifier.clickable { override = option.value },
                        )
                    }
                }
            }
            item {
                InfoText(stringResource(R.string.info_placeholder))
            }
            item {
                Button(
                    onClick = {
                        if (SleepWindow.parseHhMm(start) == null || SleepWindow.parseHhMm(end) == null) {
                            showSnackbar(invalidTimeMessage)
                            return@Button
                        }
                        val ok = ModulePreferences.edit {
                            putBoolean(ConfigKeys.ENABLED, enabled)
                            putBoolean(ConfigKeys.SCHEDULE_ENABLED, scheduleEnabled)
                            putString(ConfigKeys.WINDOW_START, start)
                            putString(ConfigKeys.WINDOW_END, end)
                            putBoolean(ConfigKeys.LINK_DND, linkDnd)
                            putInt(ConfigKeys.MANUAL_OVERRIDE, override)
                        }
                        showSnackbar(if (ok) savedMessage else saveFailedMessage)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (showStartPicker) {
        TimeSelectionDialog(
            initialValue = start,
            fallbackValue = ConfigKeys.DEFAULT_WINDOW_START,
            onConfirm = { start = it },
            onDismiss = { showStartPicker = false },
        )
    }
    if (showEndPicker) {
        TimeSelectionDialog(
            initialValue = end,
            fallbackValue = ConfigKeys.DEFAULT_WINDOW_END,
            onConfirm = { end = it },
            onDismiss = { showEndPicker = false },
        )
    }
}

private fun String.toHourMinute(fallback: String): Pair<Int, Int> {
    val total = SleepWindow.parseHhMm(this)
        ?: SleepWindow.parseHhMm(fallback)
        ?: return 0 to 0
    return total / 60 to total % 60
}

private fun formatHhMm(hour: Int, minute: Int): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

@Composable
private fun TimeField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSelectionDialog(
    initialValue: String,
    fallbackValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val (initialHour, initialMinute) = initialValue.toHourMinute(fallbackValue)
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.time_picker_title)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(formatHhMm(timePickerState.hour, timePickerState.minute))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        TimePicker(state = timePickerState)
    }
}

private fun backendStatusRes(): Int = when (ModulePreferences.backend()) {
    ModulePreferences.Backend.REMOTE -> R.string.backend_connected
    ModulePreferences.Backend.LOCAL -> R.string.backend_local
    ModulePreferences.Backend.NONE -> R.string.backend_uninitialized
}

@Composable
private fun HeroCard(backendRes: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_placeholder_moon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column {
                Text(
                    text = stringResource(R.string.hero_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(backendRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingSwitchRow(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(headline) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
