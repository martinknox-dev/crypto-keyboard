package com.mkcreations.cryptokeyboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mkcreations.cryptokeyboard.crypto.PinHasher
import com.mkcreations.cryptokeyboard.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gates sensitive crypto screens (key management, settings) behind the
 * app-lock PIN.
 *
 * Before this gate existed, "App Lock" was a stored setting that nothing ever
 * verified — the PIN was neither settable nor checked anywhere. This gate
 * makes the feature real:
 *
 * - If the app lock is disabled → content is shown directly.
 * - If enabled and a PIN is configured → PIN entry is required (with
 *   exponential lockout after failed attempts).
 * - If enabled but no PIN is configured yet → a set-PIN flow is shown.
 *
 * A successful unlock is remembered for this session; if a lock timeout is
 * configured, the PIN is not asked again until that timeout expires.
 */
@Composable
fun PinLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore.getInstance(context) }
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(settingsStore.loadSettings()) }
    var unlockedThisSession by remember { mutableStateOf(false) }

    // PIN entry state
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lockoutRemainingMs by remember { mutableStateOf(settingsStore.getPinLockoutRemainingMs()) }

    // Set/change PIN state
    var showPinDialog by remember { mutableStateOf(false) }

    val needsPinSetup = settings.appLockEnabled && settings.pinHash.isBlank()
    // Timeout 0 = ask on every entry to a gated screen; otherwise honor the
    // configured timeout since the last successful unlock.
    val locked = settings.appLockEnabled && !unlockedThisSession &&
            (settings.lockTimeoutSeconds == 0 || settingsStore.isAppLocked())

    if (!locked) {
        content()
        return
    }

    // Auto-open the set-PIN dialog when the lock is enabled without a PIN
    LaunchedEffect(needsPinSetup) {
        if (needsPinSetup) showPinDialog = true
    }

    // Count down the lockout so the UI unlocks itself when it expires
    LaunchedEffect(lockoutRemainingMs) {
        if (lockoutRemainingMs > 0) {
            delay(lockoutRemainingMs)
            lockoutRemainingMs = 0
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("🔒", fontSize = 40.sp)
            Text(
                "App Locked",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Enter your PIN to access keys and crypto settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (lockoutRemainingMs > 0) {
                Text(
                    "Too many failed attempts. Try again in ${lockoutRemainingMs / 1000 + 1}s.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = {
                        if (it.length <= 6) {
                            pinInput = it
                            errorMessage = null
                        }
                    },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = {
                        if (pinInput.length < 4) {
                            errorMessage = "PIN must be at least 4 digits"
                            return@Button
                        }
                        // PBKDF2 at 600k iterations is too slow for the main thread
                        scope.launch(Dispatchers.Default) {
                            val ok = PinHasher.verifyPin(pinInput, settings.pinHash)
                            if (ok) {
                                settingsStore.recordUnlock()
                                settingsStore.resetPinFailures()
                                if (PinHasher.isLegacyFormat(settings.pinHash)) {
                                    // Transparently upgrade legacy weak hashes
                                    settingsStore.setPinHash(PinHasher.hashPin(pinInput))
                                }
                                unlockedThisSession = true
                                pinInput = ""
                                errorMessage = null
                            } else {
                                val lockoutUntil = settingsStore.recordPinFailure()
                                lockoutRemainingMs = lockoutUntil - System.currentTimeMillis()
                                errorMessage = "Wrong PIN"
                                pinInput = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Unlock")
                }
            }
        }
    }

    if (showPinDialog) {
        SetPinDialog(
            title = if (settings.pinHash.isBlank()) "Set App Lock PIN" else "Change App Lock PIN",
            requireCurrentPin = settings.pinHash.isNotBlank(),
            currentPinHash = settings.pinHash,
            onDismiss = { showPinDialog = false },
            onPinSet = { newHash ->
                settingsStore.setPinHash(newHash)
                settings = settingsStore.loadSettings()
                unlockedThisSession = true
                showPinDialog = false
            }
        )
    }
}

@Composable
private fun SetPinDialog(
    title: String,
    requireCurrentPin: Boolean,
    currentPinHash: String,
    onDismiss: () -> Unit,
    onPinSet: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (requireCurrentPin) {
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 6) { currentPin = it; error = null } },
                        label = { Text("Current PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 6) { newPin = it; error = null } },
                    label = { Text("New PIN (4-6 digits)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 6) { confirmPin = it; error = null } },
                    label = { Text("Confirm new PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // Hashing/verification runs off the main thread (600k PBKDF2)
                scope.launch(Dispatchers.Default) {
                    when {
                        requireCurrentPin && !PinHasher.verifyPin(currentPin, currentPinHash) -> {
                            error = "Current PIN is wrong"
                        }
                        newPin.length < 4 -> {
                            error = "PIN must be at least 4 digits"
                        }
                        newPin != confirmPin -> {
                            error = "PINs do not match"
                        }
                        else -> {
                            val hash = PinHasher.hashPin(newPin)
                            withContext(Dispatchers.Main) {
                                onPinSet(hash)
                            }
                        }
                    }
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
