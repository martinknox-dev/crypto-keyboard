package com.mkcreations.cryptokeyboard.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnKey
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import com.mkcreations.cryptokeyboard.accessibility.CryptoAccessibilityService
import com.mkcreations.cryptokeyboard.core.SharedState
import com.mkcreations.cryptokeyboard.crypto.PinHasher
import com.mkcreations.cryptokeyboard.model.PanicTrigger
import com.mkcreations.cryptokeyboard.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rememberCoroutineScope
import kotlinx.coroutines.withContext

@Composable
fun CryptoSettingsScreen() = FlorisScreen {
    title = "Crypto Engine"

    val navController = LocalNavController.current
    val context = LocalContext.current

    var isExchangeMode by remember { mutableStateOf(SharedState.isExchangeModeActive) }
    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }

    content {
        // Gate the whole screen behind the app-lock PIN (if enabled)
        PinLockGate {
            CryptoSettingsContent(
                isExchangeMode = isExchangeMode,
                onExchangeModeChanged = { isExchangeMode = it },
                isAccessibilityEnabled = isAccessibilityEnabled,
                onAccessibilityEnabledChanged = { isAccessibilityEnabled = it },
                onOpenKeyManagement = { navController.navigate(Routes.Settings.KeyManagement) }
            )
        }
    }
}

@Composable
private fun CryptoSettingsContent(
    isExchangeMode: Boolean,
    onExchangeModeChanged: (Boolean) -> Unit,
    isAccessibilityEnabled: Boolean,
    onAccessibilityEnabledChanged: (Boolean) -> Unit,
    onOpenKeyManagement: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsStore.getInstance(context) }
    var settings by remember { mutableStateOf(settingsStore.loadSettings()) }

    var showChangePinDialog by remember { mutableStateOf(false) }
    var showDisableLockDialog by remember { mutableStateOf(false) }
    var showPanicDialog by remember { mutableStateOf(false) }

    PreferenceGroup(title = "Key Management") {
        Preference(
            icon = Icons.Default.VpnKey,
            title = "Manage Keys",
            summary = "Generate, export, or import your encryption keys",
            onClick = onOpenKeyManagement
        )
    }

    PreferenceGroup(title = "Exchange Mode") {
        Preference(
            icon = Icons.Default.SwapHoriz,
            title = if (isExchangeMode) "Exchange Mode: ON" else "Exchange Mode: OFF",
            summary = if (isExchangeMode)
                "Screen scanning active — encrypted messages will be decrypted automatically"
            else
                "Tap to enable automatic decryption of on-screen messages",
            onClick = {
                if (!isAccessibilityEnabled) {
                    Toast.makeText(
                        context,
                        "Enable Accessibility Service first",
                        Toast.LENGTH_LONG
                    ).show()
                    return@Preference
                }
                val newState = !isExchangeMode
                SharedState.isExchangeModeActive = newState
                onExchangeModeChanged(newState)

                // Notify the accessibility service
                CryptoAccessibilityService.instance?.onExchangeModeChanged(newState)

                Toast.makeText(
                    context,
                    if (newState) "Exchange Mode enabled" else "Exchange Mode disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        Preference(
            icon = Icons.Default.Accessibility,
            title = if (isAccessibilityEnabled) "Accessibility: Connected ✓" else "Accessibility: Not Enabled",
            summary = if (isAccessibilityEnabled)
                "Screen scanning service is running (password fields are never read)"
            else
                "Required for Exchange Mode — tap to enable in system settings",
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Cannot open settings", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    PreferenceGroup(title = "Security") {
        Preference(
            icon = Icons.Default.Lock,
            title = "App Lock",
            summary = if (settings.appLockEnabled)
                "PIN required to open keys & crypto settings" +
                        (if (settings.pinHash.isBlank()) " — set a PIN!" else "")
            else
                "Protect keys and crypto settings with a PIN",
            onClick = {
                if (settings.appLockEnabled) {
                    showDisableLockDialog = true
                } else {
                    showChangePinDialog = true // doubles as set-PIN dialog
                }
            }
        )

        if (settings.appLockEnabled && settings.pinHash.isNotBlank()) {
            Preference(
                icon = Icons.Default.Lock,
                title = "Change App Lock PIN",
                summary = "Update the PIN used to access your keys",
                onClick = { showChangePinDialog = true }
            )
        }

        Preference(
            icon = Icons.Default.Security,
            title = "Panic Settings",
            summary = if (settings.panicEnabled)
                "Panic mode enabled (${settings.panicTrigger.name.lowercase()})"
            else
                "Configure emergency wipe triggers",
            onClick = { showPanicDialog = true }
        )
    }

    // --- Set / Change PIN dialog ---
    if (showChangePinDialog) {
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showChangePinDialog = false },
            title = {
                Text(if (settings.pinHash.isBlank()) "Set App Lock PIN" else "Change App Lock PIN")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (settings.pinHash.isNotBlank()) {
                        OutlinedTextField(
                            value = currentPin,
                            onValueChange = { if (it.length <= 6) { currentPin = it; error = null } },
                            label = { Text("Current PIN") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 6) { newPin = it; error = null } },
                        label = { Text("New PIN (4-6 digits)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { if (it.length <= 6) { confirmPin = it; error = null } },
                        label = { Text("Confirm new PIN") },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    // Hashing/verification runs off the main thread (600k PBKDF2)
                    scope.launch(Dispatchers.Default) {
                        when {
                            settings.pinHash.isNotBlank() &&
                                    !PinHasher.verifyPin(currentPin, settings.pinHash) -> {
                                error = "Current PIN is wrong"
                            }
                            newPin.length < 4 -> error = "PIN must be at least 4 digits"
                            newPin != confirmPin -> error = "PINs do not match"
                            else -> {
                                val hash = PinHasher.hashPin(newPin)
                                withContext(Dispatchers.Main) {
                                    settingsStore.setPinHash(hash)
                                    settings = settingsStore.loadSettings()
                                    showChangePinDialog = false
                                    Toast.makeText(context, "PIN saved", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Disable app lock dialog (requires current PIN) ---
    if (showDisableLockDialog) {
        var currentPin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showDisableLockDialog = false },
            title = { Text("Disable App Lock?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your current PIN to disable the app lock.")
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 6) { currentPin = it; error = null } },
                        label = { Text("Current PIN") },
                        singleLine = true,
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch(Dispatchers.Default) {
                        if (PinHasher.verifyPin(currentPin, settings.pinHash)) {
                            withContext(Dispatchers.Main) {
                                settingsStore.clearAppLock()
                                settings = settingsStore.loadSettings()
                                showDisableLockDialog = false
                                Toast.makeText(context, "App lock disabled", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            error = "Wrong PIN"
                        }
                    }
                }) {
                    Text("Disable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableLockDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- Panic settings dialog ---
    if (showPanicDialog) {
        var enabled by remember { mutableStateOf(settings.panicEnabled) }
        var trigger by remember { mutableStateOf(settings.panicTrigger) }
        var switchKeyboard by remember { mutableStateOf(settings.panicSwitchKeyboard) }
        var disableExchange by remember { mutableStateOf(settings.panicDisableExchange) }
        var removeOverlays by remember { mutableStateOf(settings.panicRemoveOverlays) }
        var clearCache by remember { mutableStateOf(settings.panicClearCache) }
        var lockApp by remember { mutableStateOf(settings.panicLockApp) }
        var clearKeys by remember { mutableStateOf(settings.panicClearKeys) }

        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            title = { Text("Panic Settings") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                        Text("Enable panic mode")
                    }
                    if (enabled) {
                        Text("Trigger:", style = MaterialTheme.typography.bodySmall)
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text(trigger.name)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                PanicTrigger.entries.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.name) },
                                        onClick = { trigger = t; expanded = false }
                                    )
                                }
                            }
                        }
                        PanicToggle("Remove decrypted overlays", removeOverlays) { removeOverlays = it }
                        PanicToggle("Clear decryption cache", clearCache) { clearCache = it }
                        PanicToggle("Disable exchange mode", disableExchange) { disableExchange = it }
                        PanicToggle("Switch to previous keyboard", switchKeyboard) { switchKeyboard = it }
                        PanicToggle("Lock the app", lockApp) { lockApp = it }
                        PanicToggle("⚠ Wipe all keys (irreversible!)", clearKeys) { clearKeys = it }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    settingsStore.saveSettings(
                        settings.copy(
                            panicEnabled = enabled,
                            panicTrigger = trigger,
                            panicSwitchKeyboard = switchKeyboard,
                            panicDisableExchange = disableExchange,
                            panicRemoveOverlays = removeOverlays,
                            panicClearCache = clearCache,
                            panicLockApp = lockApp,
                            panicClearKeys = clearKeys
                        )
                    )
                    settings = settingsStore.loadSettings()
                    showPanicDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PanicToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Checks if our accessibility service is enabled
 */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    return try {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val ourService = "${context.packageName}/com.mkcreations.cryptokeyboard.accessibility.CryptoAccessibilityService"
        enabledServices.contains(ourService)
    } catch (e: Exception) {
        false
    }
}
