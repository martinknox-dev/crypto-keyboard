package com.mkcreations.cryptokeyboard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mkcreations.cryptokeyboard.accessibility.CryptoAccessibilityService
import com.mkcreations.cryptokeyboard.crypto.InviteApproval
import com.mkcreations.cryptokeyboard.crypto.MessageProcessor
import com.mkcreations.cryptokeyboard.storage.SecureKeyStore

/**
 * Hosts the invite approval UI. A Compose dialog cannot live inside a
 * FLAG_NOT_TOUCHABLE overlay or an IME, so both detection paths (screen
 * overlay tap and clipboard decrypt button) launch this translucent Activity
 * instead — one surface, two entries.
 */
class KeyInviteApprovalActivity : ComponentActivity() {

    companion object {
        const val EXTRA_RAW_MESSAGE = "invite_raw_message"

        fun start(context: Context, rawMessage: String) {
            val intent = Intent(context, KeyInviteApprovalActivity::class.java)
                .putExtra(EXTRA_RAW_MESSAGE, rawMessage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_RAW_MESSAGE)
        val envelope = raw?.let { MessageProcessor.extractInvite(it) }
        if (envelope == null) {
            Toast.makeText(this, "No valid key invitation found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            InviteApprovalDialog(
                note = envelope.note,
                onSave = { alias ->
                    val key = InviteApproval.toCipherKey(envelope, alias)
                    SecureKeyStore.getInstance(this).saveKey(key)
                    raw?.let { CryptoAccessibilityService.instance?.onInviteAccepted(it) }
                    CryptoAccessibilityService.instance?.onKeysChanged()
                    Toast.makeText(this, "Contact \u201C${key.label}\u201D saved", Toast.LENGTH_SHORT).show()
                    finish()
                },
                onDecline = { finish() },
            )
        }
    }
}

@Composable
private fun InviteApprovalDialog(
    note: String,
    onSave: (alias: String) -> Unit,
    onDecline: () -> Unit,
) {
    var alias by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AlertDialog(
            onDismissRequest = onDecline,
            title = { Text("Key Invitation Received") },
            text = {
                Column {
                    Text(
                        text = if (note.isBlank()) "No note from sender." else "Note: $note",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Verification: Unverified \u00B7 Ephemeral",
                        textAlign = TextAlign.Start,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text("Contact name / alias") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = { onSave(alias) }) { Text("Save Contact") }
            },
            dismissButton = {
                TextButton(onClick = onDecline) { Text("Decline") }
            },
        )
    }
}
