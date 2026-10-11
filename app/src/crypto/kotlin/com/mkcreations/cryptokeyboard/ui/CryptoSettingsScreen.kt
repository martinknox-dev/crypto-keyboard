package com.mkcreations.cryptokeyboard.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mkcreations.cryptokeyboard.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import com.mkcreations.cryptokeyboard.accessibility.CryptoAccessibilityService
import com.mkcreations.cryptokeyboard.core.SharedState

@Composable
fun CryptoSettingsScreen() = FlorisScreen {
    title = "Crypto Engine"

    val navController = LocalNavController.current
    val context = LocalContext.current

    var isExchangeMode by remember { mutableStateOf(SharedState.isExchangeModeActive) }
    var isAccessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context))
    }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinValue by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var pinCurrent by remember { mutableStateOf("") }
    var pinConfigured by remember {
        mutableStateOf(
            SettingsStore.getInstance(context).loadSettings().pinHash.isNotEmpty()
        )
    }
    val coroutineScope = rememberCoroutineScope()

    content {
        PreferenceGroup(title = "Key Management") {
            Preference(
                icon = Icons.Default.VpnKey,
                title = "Manage Keys",
                summary = "Generate, export, or import your encryption keys",
                onClick = {
                    navController.navigate(Routes.Settings.KeyManagement)
                }
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
                    isExchangeMode = newState

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
                    "Screen scanning service is running"
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
                title = "Change App Lock PIN",
                summary = "Change or disable the PIN that locks the keyboard",
                onClick = {
                    showPinDialog = true
                }
            )

            Preference(
                icon = Icons.Default.Security,
                title = "Panic Settings",
                summary = "Configure emergency wipe triggers",
                onClick = {
                    navController.navigate(Routes.Settings.PanicSettings)
                }
            )
        }

        if (showPinDialog) {
            AlertDialog(
                onDismissRequest = { showPinDialog = false },
                title = { Text("App Lock PIN") },
                text = {
                    Column {
                        if (pinConfigured) {
                            OutlinedTextField(
                                value = pinCurrent,
                                onValueChange = { pinCurrent = it },
                                label = { Text("Current PIN") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedTextField(
                            value = pinValue,
                            onValueChange = { pinValue = it },
                            label = { Text("New PIN (4+ digits)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pinConfirm,
                            onValueChange = { pinConfirm = it },
                            label = { Text("Confirm PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        when {
                            pinValue.length < 4 -> {
                                Toast.makeText(context, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                            }
                            pinValue != pinConfirm -> {
                                Toast.makeText(context, "PINs do not match", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                val store = SettingsStore.getInstance(context)
                                val needsCurrent = pinConfigured
                                val current = pinCurrent
                                val newPin = pinValue
                                coroutineScope.launch {
                                    // PBKDF2-600k: verification and hashing on IO, never the UI thread
                                    val currentOk = !needsCurrent ||
                                        withContext(Dispatchers.IO) { store.verifyPin(current) }
                                    if (!currentOk) {
                                        Toast.makeText(context, "Current PIN is incorrect", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    withContext(Dispatchers.IO) { store.setPin(newPin) }
                                    pinValue = ""
                                    pinConfirm = ""
                                    pinCurrent = ""
                                    showPinDialog = false
                                    pinConfigured = true
                                    Toast.makeText(context, "App lock enabled", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        if (!pinConfigured) {
                            showPinDialog = false
                        } else {
                            val store = SettingsStore.getInstance(context)
                            val current = pinCurrent
                            coroutineScope.launch {
                                val currentOk = withContext(Dispatchers.IO) { store.verifyPin(current) }
                                if (!currentOk) {
                                    Toast.makeText(context, "Current PIN is incorrect", Toast.LENGTH_LONG).show()
                                    return@launch
                                }
                                store.clearPin()
                                pinValue = ""
                                pinConfirm = ""
                                pinCurrent = ""
                                showPinDialog = false
                                pinConfigured = false
                                Toast.makeText(context, "App lock disabled", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("Turn off lock") }
                }
            )
        }
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