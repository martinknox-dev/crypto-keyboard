package com.mkcreations.cryptokeyboard.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mkcreations.cryptokeyboard.accessibility.CryptoAccessibilityService
import com.mkcreations.cryptokeyboard.core.PanicConfig
import com.mkcreations.cryptokeyboard.core.PanicToggle
import com.mkcreations.cryptokeyboard.model.AppSettings
import com.mkcreations.cryptokeyboard.storage.SettingsStore
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.util.AccessibilityUtils
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup

/**
 * Panic Actions settings: one master switch, independent triggers, and the
 * effects a panic run executes.
 *
 * While the master switch is off, every trigger toggle is locked — and among
 * the actions only "switch to previous keyboard" stays editable (user
 * decision; it also defaults to on).
 */
@Composable
fun PanicSettingsScreen() = FlorisScreen {
    title = "Panic Actions"

    val context = LocalContext.current
    val store = remember { SettingsStore.getInstance(context) }
    var settings by remember { mutableStateOf(store.loadSettings()) }
    var showWipeConfirm by remember { mutableStateOf(false) }

    fun persist(newSettings: AppSettings) {
        settings = newSettings
        store.saveSettings(newSettings)
        // Sensors and trigger counters re-register for the new configuration.
        CryptoAccessibilityService.instance?.refreshPanicTriggers()
    }

    content {
        // Every trigger lives in the accessibility service — never show an
        // "Armed" state the system cannot actually deliver on.
        val serviceEnabled = AccessibilityUtils.isServiceEnabled(context)
        if (!serviceEnabled) {
            PreferenceGroup(title = "Accessibility service") {
                Preference(
                    icon = Icons.Default.Warning,
                    title = "Panic triggers are inactive",
                    summary = "The accessibility service is not enabled — enable " +
                        "Crypto Keyboard Decoder in system settings",
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (e: Exception) {
                            // No settings activity on this device — nothing to open
                        }
                    },
                )
            }
        }

        PreferenceGroup(title = "Master") {
            PanicSwitchRow(
                title = "Panic Actions",
                summary = when {
                    !serviceEnabled -> "Accessibility service not enabled — triggers cannot fire"
                    settings.panicEnabled -> "Armed — enabled triggers below can fire"
                    else -> "Off — every trigger is disabled"
                },
                checked = settings.panicEnabled,
                enabled = PanicConfig.isToggleEnabled(settings, PanicToggle.MASTER),
                onCheckedChange = { persist(settings.copy(panicEnabled = it)) },
            )
        }

        PreferenceGroup(title = "Triggers") {
            PanicSwitchRow(
                title = "Shake device",
                summary = "Vigorous shake (3 shakes within 1.5 seconds)",
                checked = settings.panicTriggerShake,
                enabled = PanicConfig.isToggleEnabled(settings, PanicToggle.TRIGGER_SHAKE),
                onCheckedChange = { persist(settings.copy(panicTriggerShake = it)) },
            )
            PanicSwitchRow(
                title = "Flip face-down",
                summary = "Screen turned down onto a surface (tilt + proximity)",
                checked = settings.panicTriggerFaceDown,
                enabled = PanicConfig.isToggleEnabled(settings, PanicToggle.TRIGGER_FACE_DOWN),
                onCheckedChange = { persist(settings.copy(panicTriggerFaceDown = it)) },
            )
            PanicSwitchRow(
                title = "Spacebar gesture",
                summary = "Quick swipe down on spacebar, or triple-tap it",
                checked = settings.panicTriggerSpacebar,
                enabled = PanicConfig.isToggleEnabled(settings, PanicToggle.TRIGGER_SPACEBAR),
                onCheckedChange = { persist(settings.copy(panicTriggerSpacebar = it)) },
            )
            PanicSwitchRow(
                title = "Hardware key sequence",
                summary = "Triple-press Volume Down within 1.5 seconds",
                checked = settings.panicTriggerVolumeSequence,
                enabled = PanicConfig.isToggleEnabled(settings, PanicToggle.TRIGGER_VOLUME),
                onCheckedChange = { persist(settings.copy(panicTriggerVolumeSequence = it)) },
            )
        }

        PreferenceGroup(title = "Actions") {
            PanicSwitchRow(
                title = "Switch to previous keyboard",
                summary = "Return to the previously used keyboard (falls back to the picker)",
                checked = settings.panicActionSwitchIme,
                enabled = PanicConfig.isToggleEnabled(settings, PanicToggle.ACTION_SWITCH_IME),
                onCheckedChange = { persist(settings.copy(panicActionSwitchIme = it)) },
            )
            PanicSwitchRow(
                title = "Dismiss decrypt overlays & bubbles",
                summary = "Hide all overlays and disable exchange mode",
                checked = settings.panicActionRemoveOverlays,
                enabled = PanicConfig.isToggleEnabled(settings, PanicToggle.ACTION_REMOVE_OVERLAYS),
                onCheckedChange = { persist(settings.copy(panicActionRemoveOverlays = it)) },
            )
            PanicSwitchRow(
                title = "Flush decrypt cache and lock app",
                summary = "Clear decrypted secrets and relock (app lock PIN required to lock)",
                checked = settings.panicActionClearCache,
                enabled = PanicConfig.isToggleEnabled(settings, PanicToggle.ACTION_CLEAR_CACHE),
                onCheckedChange = { persist(settings.copy(panicActionClearCache = it)) },
            )
            PanicSwitchRow(
                title = "Nuclear key wipe / reset all identities",
                summary = "Permanently delete all encryption keys when panic fires",
                checked = settings.panicActionWipeKeys,
                enabled = PanicConfig.isToggleEnabled(settings, PanicToggle.ACTION_WIPE_KEYS),
                onCheckedChange = { enable ->
                    if (enable) {
                        showWipeConfirm = true
                    } else {
                        persist(settings.copy(panicActionWipeKeys = false))
                    }
                },
            )
        }

        if (showWipeConfirm) {
            AlertDialog(
                onDismissRequest = { showWipeConfirm = false },
                title = { Text("Reset all identities?") },
                text = {
                    Text(
                        "Every encryption key, the active key selection, and per-chat " +
                            "key mappings will be permanently deleted when panic fires. " +
                            "This cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showWipeConfirm = false
                        persist(settings.copy(panicActionWipeKeys = true))
                    }) { Text("Enable wipe") }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeConfirm = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun PanicSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val titleColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (enabled) 1f else 0.38f
    )
    val summaryColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (enabled) 1f else 0.38f
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = summaryColor,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}
