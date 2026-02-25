package com.mkcreations.cryptokeyboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore
import com.mkcreations.cryptokeyboard.crypto.KeyGenerator
import com.mkcreations.cryptokeyboard.crypto.KeyExporter
import com.mkcreations.cryptokeyboard.crypto.QrCodeHelper
import com.mkcreations.cryptokeyboard.model.CipherKey
import com.mkcreations.cryptokeyboard.model.CoverStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyManagementScreen() = FlorisScreen {
    title = "Manage Keys"

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Async-safe state
    var secureKeyStore by remember { mutableStateOf<SecureKeyStore?>(null) }
    var keyList by remember { mutableStateOf<List<CipherKey>>(emptyList()) }
    var activeKeyId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Dialog States
    var showGenerateDialog by remember { mutableStateOf(false) }
    var showPhraseDialog by remember { mutableStateOf<PhraseDialogData?>(null) }
    var showImportPhraseDialog by remember { mutableStateOf(false) }
    var showImportFileDialog by remember { mutableStateOf(false) }
    var showExportFileDialogFor by remember { mutableStateOf<CipherKey?>(null) }
    var showKeyDetailsFor by remember { mutableStateOf<CipherKey?>(null) }
    var showQrDisplayFor by remember { mutableStateOf<CipherKey?>(null) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showImportDropdown by remember { mutableStateOf(false) }

    // Input States
    var newKeyAlias by remember { mutableStateOf("") }
    var importKeyAlias by remember { mutableStateOf("") }
    var importPhraseText by remember { mutableStateOf("") }
    var importFileData by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var exportPassword by remember { mutableStateOf("") }
    var exportedResult by remember { mutableStateOf<String?>(null) }

    // File picker for import
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val fileContent = inputStream?.bufferedReader()?.readText() ?: ""
                inputStream?.close()
                importFileData = fileContent
                showImportFileDialog = true
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // File saver for export
    val fileSaverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && exportedResult != null) {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                outputStream?.write(exportedResult!!.toByteArray(Charsets.UTF_8))
                outputStream?.close()
                Toast.makeText(context, "Key exported to file!", Toast.LENGTH_SHORT).show()
                showExportFileDialogFor = null
                exportPassword = ""
                exportedResult = null
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Load keys asynchronously
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val store = SecureKeyStore.getInstance(context)
                val keys = store.getAllKeys()
                val activeId = store.getActiveKeyId()
                withContext(Dispatchers.Main) {
                    secureKeyStore = store
                    keyList = keys
                    activeKeyId = activeId
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    Toast.makeText(context, "Failed to load keys: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun refreshKeys() {
        secureKeyStore?.let { store ->
            try {
                keyList = store.getAllKeys()
                activeKeyId = store.getActiveKeyId()
            } catch (e: Exception) {
                Toast.makeText(context, "Error refreshing keys: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    content {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@content
        }

        val store = secureKeyStore
        if (store == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Failed to initialize secure storage.")
            }
            return@content
        }

        // --- TOP ACTION BUTTONS ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Generate button
            Button(
                onClick = { showGenerateDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Generate")
                Spacer(Modifier.width(4.dp))
                Text("Generate")
            }

            // Import dropdown button
            Box(modifier = Modifier.weight(1f)) {
                FilledTonalButton(
                    onClick = { showImportDropdown = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Import")
                    Spacer(Modifier.width(4.dp))
                    Text("Import")
                }

                DropdownMenu(
                    expanded = showImportDropdown,
                    onDismissRequest = { showImportDropdown = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Scan QR Code") },
                        onClick = {
                            showImportDropdown = false
                            showQrScanner = true
                        },
                        leadingIcon = {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("From Phrase (32 words)") },
                        onClick = {
                            showImportDropdown = false
                            showImportPhraseDialog = true
                        },
                        leadingIcon = {
                            Icon(Icons.Default.TextSnippet, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("From File") },
                        onClick = {
                            showImportDropdown = false
                            try {
                                filePickerLauncher.launch("application/json")
                            } catch (e: Exception) {
                                showImportFileDialog = true
                            }
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Download, contentDescription = null)
                        }
                    )
                }
            }
        }

        // =============================================
        // QR SCANNER (full screen overlay)
        // =============================================
        if (showQrScanner) {
            Dialog(
                onDismissRequest = { showQrScanner = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false
                )
            ) {
                QrScannerScreen(
                    onKeyScanned = { scannedKey ->
                        coroutineScope.launch {
                            try {
                                // Check if key already exists
                                val existingKeys = store.getAllKeys()
                                val duplicate = existingKeys.find { it.id == scannedKey.id }

                                if (duplicate != null) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            "Key '${duplicate.label}' already exists!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        showQrScanner = false
                                    }
                                    return@launch
                                }

                                withContext(Dispatchers.IO) {
                                    store.saveKey(scannedKey)
                                    if (existingKeys.isEmpty()) {
                                        store.setActiveKeyId(scannedKey.id)
                                    }
                                }

                                withContext(Dispatchers.Main) {
                                    refreshKeys()
                                    showQrScanner = false
                                    Toast.makeText(
                                        context,
                                        "Key '${scannedKey.label}' imported via QR! 🎉",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Import error: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    showQrScanner = false
                                }
                            }
                        }
                    },
                    onDismiss = { showQrScanner = false }
                )
            }
        }

        // =============================================
        // DIALOG: QR CODE DISPLAY
        // =============================================
        showQrDisplayFor?.let { keyForQr ->
            val qrBitmap = remember(keyForQr) {
                QrCodeHelper.generateQrBitmap(keyForQr, 800)
            }

            AlertDialog(
                onDismissRequest = { showQrDisplayFor = null },
                title = {
                    Text(
                        "🔑 QR: ${keyForQr.label}",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Let the other person scan this QR code to import your key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Fingerprint: ${keyForQr.id}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(280.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "QR Code for ${keyForQr.label}",
                                    modifier = Modifier
                                        .size(260.dp)
                                        .padding(8.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(280.dp)
                                    .background(
                                        MaterialTheme.colorScheme.errorContainer,
                                        RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Failed to generate QR code",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "⚠️ Anyone who scans this QR can read your encrypted messages. Show it only to trusted people!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showQrDisplayFor = null }) {
                        Text("Done")
                    }
                }
            )
        }

        // =============================================
        // DIALOG: GENERATE KEY
        // =============================================
        if (showGenerateDialog) {
            AlertDialog(
                onDismissRequest = {
                    showGenerateDialog = false
                    newKeyAlias = ""
                },
                title = { Text("Generate New Key") },
                text = {
                    OutlinedTextField(
                        value = newKeyAlias,
                        onValueChange = { newKeyAlias = it },
                        label = { Text("Key Name (e.g., Alice, Work)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        if (newKeyAlias.isNotBlank()) {
                            coroutineScope.launch {
                                try {
                                    val rawKey = KeyGenerator.generateKey()
                                    val fingerprintId = KeyGenerator.fingerprint(rawKey)
                                    val phrases = KeyGenerator.keyToWordPhrase(rawKey)

                                    val newKey = CipherKey(
                                        id = fingerprintId,
                                        label = newKeyAlias,
                                        keyBytes = rawKey,
                                        createdAt = System.currentTimeMillis(),
                                        lastUsedAt = 0L,
                                        coverStyle = CoverStyle.RANDOM_CASUAL,
                                        compatibleChars = emptyList()
                                    )

                                    withContext(Dispatchers.IO) {
                                        store.saveKey(newKey)
                                        if (keyList.isEmpty()) {
                                            store.setActiveKeyId(newKey.id)
                                        }
                                    }

                                    refreshKeys()
                                    showGenerateDialog = false
                                    newKeyAlias = ""

                                    // Show the phrase dialog
                                    showPhraseDialog = PhraseDialogData(
                                        keyLabel = newKey.label,
                                        keyId = newKey.id,
                                        phrases = phrases
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }) {
                        Text("Generate")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showGenerateDialog = false
                        newKeyAlias = ""
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // =============================================
        // DIALOG: SHOW PHRASES (after generate or view)
        // =============================================
        showPhraseDialog?.let { data ->
            AlertDialog(
                onDismissRequest = { showPhraseDialog = null },
                title = {
                    Text(
                        "🔑 Key: ${data.keyLabel}",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "Share this key with the other person using QR, phrase, or file export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Fingerprint: ${data.keyId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Show QR button prominently
                        Button(
                            onClick = {
                                // Find the key to show QR
                                val key = keyList.find { it.id == data.keyId }
                                if (key != null) {
                                    showPhraseDialog = null
                                    showQrDisplayFor = key
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = "QR")
                            Spacer(Modifier.width(8.dp))
                            Text("Show QR Code")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Numbered phrase grid
                        Text(
                            "Or share these 32 words:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val phraseText = data.phrases.mapIndexed { index, word ->
                            "${index + 1}. $word"
                        }

                        // Display in 2 columns
                        val leftColumn = phraseText.take(16)
                        val rightColumn = phraseText.drop(16)

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                leftColumn.forEach { line ->
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                rightColumn.forEach { line ->
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Copy button
                        OutlinedButton(
                            onClick = {
                                val phraseString = data.phrases.joinToString(" ")
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Key Phrase", phraseString))
                                Toast.makeText(context, "Phrases copied to clipboard!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            Spacer(Modifier.width(8.dp))
                            Text("Copy All 32 Words")
                        }

                        // Share button
                        OutlinedButton(
                            onClick = {
                                val phraseString = data.phrases.joinToString(" ")
                                val shareText = "🔑 Cipher Key: ${data.keyLabel}\n\n$phraseString\n\nPaste these 32 words in Crypto Keyboard → Import → Phrase"
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Key Phrase"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = "Share")
                            Spacer(Modifier.width(8.dp))
                            Text("Share via...")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "⚠️ Anyone with these words or QR can read your encrypted messages. Share securely!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showPhraseDialog = null }) {
                        Text("Done")
                    }
                }
            )
        }

        // =============================================
        // DIALOG: IMPORT BY PHRASE
        // =============================================
        if (showImportPhraseDialog) {
            AlertDialog(
                onDismissRequest = {
                    showImportPhraseDialog = false
                    importKeyAlias = ""
                    importPhraseText = ""
                },
                title = { Text("Import Key from Phrase") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Paste the 32 words you received from the other person.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = importKeyAlias,
                            onValueChange = { importKeyAlias = it },
                            label = { Text("Key Name (e.g., Alice)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = importPhraseText,
                            onValueChange = { importPhraseText = it },
                            label = { Text("Paste 32 words here") },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (importKeyAlias.isNotBlank() && importPhraseText.isNotBlank()) {
                            coroutineScope.launch {
                                try {
                                    val words = importPhraseText
                                        .trim()
                                        .split("\\s+".toRegex())
                                        .map { it.lowercase().trim() }
                                        .filter { it.isNotEmpty() }

                                    if (words.size != 32) {
                                        Toast.makeText(
                                            context,
                                            "Expected 32 words, got ${words.size}. Check and try again.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@launch
                                    }

                                    val keyBytes = KeyGenerator.wordPhraseToKey(words)
                                    if (keyBytes == null) {
                                        Toast.makeText(
                                            context,
                                            "Invalid phrase — some words are not recognized.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@launch
                                    }

                                    val fingerprintId = KeyGenerator.fingerprint(keyBytes)

                                    val newKey = CipherKey(
                                        id = fingerprintId,
                                        label = importKeyAlias,
                                        keyBytes = keyBytes,
                                        createdAt = System.currentTimeMillis(),
                                        lastUsedAt = 0L,
                                        coverStyle = CoverStyle.RANDOM_CASUAL,
                                        compatibleChars = emptyList()
                                    )

                                    withContext(Dispatchers.IO) {
                                        store.saveKey(newKey)
                                    }

                                    refreshKeys()
                                    showImportPhraseDialog = false
                                    importKeyAlias = ""
                                    importPhraseText = ""
                                    Toast.makeText(context, "Key imported from phrase!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Import error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }) {
                        Text("Import")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showImportPhraseDialog = false
                        importKeyAlias = ""
                        importPhraseText = ""
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // =============================================
        // DIALOG: IMPORT BY FILE
        // =============================================
        if (showImportFileDialog) {
            AlertDialog(
                onDismissRequest = {
                    showImportFileDialog = false
                    importFileData = ""
                    importPassword = ""
                    importKeyAlias = ""
                },
                title = { Text("Import Key from File") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (importFileData.isEmpty()) {
                            Text(
                                text = "Paste the exported key data below:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = importFileData,
                                onValueChange = { importFileData = it },
                                label = { Text("Paste export data") },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = "✅ Key data loaded (${importFileData.length} chars)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedTextField(
                            value = importKeyAlias,
                            onValueChange = { importKeyAlias = it },
                            label = { Text("Key Name (optional, overrides original)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = importPassword,
                            onValueChange = { importPassword = it },
                            label = { Text("Decryption Password") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (importFileData.isNotBlank() && importPassword.isNotBlank()) {
                            coroutineScope.launch {
                                try {
                                    val importedKey = withContext(Dispatchers.IO) {
                                        KeyExporter.importKey(importFileData, importPassword)
                                    }
                                    if (importedKey != null) {
                                        val finalKey = if (importKeyAlias.isNotBlank()) {
                                            importedKey.copy(label = importKeyAlias)
                                        } else {
                                            importedKey
                                        }
                                        withContext(Dispatchers.IO) {
                                            store.saveKey(finalKey)
                                        }
                                        refreshKeys()
                                        showImportFileDialog = false
                                        importFileData = ""
                                        importPassword = ""
                                        importKeyAlias = ""
                                        Toast.makeText(context, "Key imported from file!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Wrong password or invalid data.", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Import error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }) {
                        Text("Import")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showImportFileDialog = false
                        importFileData = ""
                        importPassword = ""
                        importKeyAlias = ""
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // =============================================
        // DIALOG: EXPORT TO FILE
        // =============================================
        showExportFileDialogFor?.let { keyToExport ->
            AlertDialog(
                onDismissRequest = {
                    showExportFileDialogFor = null
                    exportPassword = ""
                    exportedResult = null
                },
                title = { Text("Export: ${keyToExport.label}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (exportedResult == null) {
                            Text(
                                "Enter a strong password to encrypt this key file.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = exportPassword,
                                onValueChange = { exportPassword = it },
                                label = { Text("Encryption Password") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                "✅ Key encrypted! Choose how to save:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                confirmButton = {
                    if (exportedResult == null) {
                        Button(onClick = {
                            if (exportPassword.isNotBlank()) {
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        KeyExporter.exportKey(keyToExport, exportPassword)
                                    }
                                    if (result != null) {
                                        exportedResult = result
                                    } else {
                                        Toast.makeText(context, "Export failed!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }) {
                            Text("Encrypt")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                try {
                                    val fileName = "cipher_key_${keyToExport.label.replace(" ", "_")}.json"
                                    fileSaverLauncher.launch(fileName)
                                } catch (e: Exception) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Key Export", exportedResult))
                                    Toast.makeText(context, "Copied to clipboard (file save unavailable)", Toast.LENGTH_LONG).show()
                                    showExportFileDialogFor = null
                                    exportPassword = ""
                                    exportedResult = null
                                }
                            }) {
                                Text("💾 Save File")
                            }

                            FilledTonalButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Key Export", exportedResult))
                                Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                showExportFileDialogFor = null
                                exportPassword = ""
                                exportedResult = null
                            }) {
                                Text("📋 Copy")
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showExportFileDialogFor = null
                        exportPassword = ""
                        exportedResult = null
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // =============================================
        // DIALOG: KEY DETAILS (view phrases + QR button)
        // =============================================
        showKeyDetailsFor?.let { key ->
            val phrases = remember(key) { KeyGenerator.keyToWordPhrase(key.keyBytes) }

            AlertDialog(
                onDismissRequest = { showKeyDetailsFor = null },
                title = {
                    Text("🔑 ${key.label}", fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        // Key info
                        Text("Fingerprint: ${key.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Created: ${java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(key.createdAt))}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (key.lastUsedAt > 0) {
                            Text(
                                "Last used: ${java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(key.lastUsedAt))}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text("Cover style: ${key.coverStyle.displayName}", style = MaterialTheme.typography.bodySmall)

                        Spacer(modifier = Modifier.height(12.dp))

                        // QR Code button
                        Button(
                            onClick = {
                                showKeyDetailsFor = null
                                showQrDisplayFor = key
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = "QR")
                            Spacer(Modifier.width(8.dp))
                            Text("Show QR Code")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("Recovery Phrase (32 words):", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                        Spacer(modifier = Modifier.height(8.dp))

                        // Phrase grid
                        val phraseText = phrases.mapIndexed { index, word -> "${index + 1}. $word" }
                        val leftColumn = phraseText.take(16)
                        val rightColumn = phraseText.drop(16)

                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                leftColumn.forEach { line ->
                                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                rightColumn.forEach { line ->
                                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Copy phrases
                        OutlinedButton(
                            onClick = {
                                val phraseString = phrases.joinToString(" ")
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Key Phrase", phraseString))
                                Toast.makeText(context, "Phrases copied!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            Spacer(Modifier.width(8.dp))
                            Text("Copy Phrase")
                        }

                        // Share phrases
                        OutlinedButton(
                            onClick = {
                                val phraseString = phrases.joinToString(" ")
                                val shareText = "🔑 Cipher Key: ${key.label}\n\n$phraseString\n\nPaste these 32 words in Crypto Keyboard → Import → Phrase"
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Key Phrase"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = "Share")
                            Spacer(Modifier.width(8.dp))
                            Text("Share Phrase")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "⚠️ Anyone with these words or QR can read your encrypted messages!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showKeyDetailsFor = null }) {
                        Text("Done")
                    }
                }
            )
        }

        // =============================================
        // KEY LIST
        // =============================================
        if (keyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No keys yet.\nGenerate a new key or import one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                keyList.forEach { key ->
                    KeyItemCard(
                        key = key,
                        isActive = key.id == activeKeyId,
                        onActivate = {
                            try {
                                store.setActiveKeyId(key.id)
                                activeKeyId = key.id
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onViewDetails = { showKeyDetailsFor = key },
                        onShowQr = { showQrDisplayFor = key },
                        onExportFile = { showExportFileDialogFor = key },
                        onDelete = {
                            coroutineScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        store.deleteKey(key.id)
                                    }
                                    refreshKeys()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// --- Data class for phrase dialog ---
data class PhraseDialogData(
    val keyLabel: String,
    val keyId: String,
    val phrases: List<String>
)

@Composable
fun KeyItemCard(
    key: CipherKey,
    isActive: Boolean,
    onActivate: () -> Unit,
    onViewDetails: () -> Unit,
    onShowQr: () -> Unit,
    onExportFile: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = key.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ID: ${key.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Short: ${com.mkcreations.cryptokeyboard.crypto.KeyGenerator.shortPhrase(key.keyBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (isActive) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isActive) {
                    TextButton(onClick = onActivate) { Text("Activate") }
                }
                IconButton(onClick = onViewDetails) {
                    Icon(Icons.Default.Visibility, contentDescription = "View Details")
                }
                IconButton(onClick = onShowQr) {
                    Icon(Icons.Default.QrCode, contentDescription = "Show QR")
                }
                IconButton(onClick = onExportFile) {
                    Icon(Icons.Default.SaveAlt, contentDescription = "Export File")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}