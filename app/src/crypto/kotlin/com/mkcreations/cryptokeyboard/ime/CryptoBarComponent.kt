package com.mkcreations.cryptokeyboard.ime

import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.mkcreations.cryptokeyboard.core.SharedState
import com.mkcreations.cryptokeyboard.crypto.CoverGenerator

/**
 * The crypto toolbar UI that appears above the keyboard when crypto mode is active.
 * Contains: secret message field, cover message field, key selector, send button, switch keyboard button.
 *
 * This is built as a View (not Compose) so it can be injected into
 * FlorisBoard's toolbar area without touching their Compose code.
 */
class CryptoBarComponent(
    private val context: Context,
    private val cryptoInputManager: CryptoInputManager
) {

    companion object {
        private const val TAG = "CryptoBarComponent"
    }

    private var rootView: View? = null
    private var secretInput: EditText? = null
    private var coverInput: EditText? = null
    private var keySelector: Spinner? = null
    private var sendButton: Button? = null
    private var switchKeyboardButton: ImageButton? = null
    private var statusText: TextView? = null
    private var modeToggle: ImageButton? = null

    // Callback to commit text to the current input field
    var onCommitText: ((String) -> Unit)? = null

    // Callback when mode changes
    var onModeChanged: ((Boolean) -> Unit)? = null

    /**
     * Creates the crypto bar view programmatically
     * (No XML inflation needed — keeps it self-contained)
     */
    fun createView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // --- Row 1: Status + Mode Toggle + Switch Keyboard ---
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Mode toggle button
        modeToggle = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_lock_lock)
            setBackgroundColor(0x00000000)
            setColorFilter(0xFF4CAF50.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { toggleCryptoMode() }
            contentDescription = "Toggle Crypto Mode"
        }
        topRow.addView(modeToggle)

        // Status text
        statusText = TextView(context).apply {
            text = "🔒 Crypto Mode"
            setTextColor(0xFF4CAF50.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, 0, 0)
        }
        topRow.addView(statusText)

        // Key selector (Spinner)
        keySelector = Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(0xFF2A2A4E.toInt())
        }
        setupKeySelector()
        topRow.addView(keySelector)

        // Switch keyboard button
        switchKeyboardButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            setBackgroundColor(0x00000000)
            setColorFilter(0xFFBBBBBB.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { switchToPreviousKeyboard() }
            contentDescription = "Switch Keyboard"
        }
        topRow.addView(switchKeyboardButton)

        root.addView(topRow)

        // --- Row 2: Secret Message Input ---
        secretInput = EditText(context).apply {
            hint = "Secret message…"
            setHintTextColor(0xFF666666.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2A2A4E.toInt())
            textSize = 14f
            minLines = 1
            maxLines = 3
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            // Prevent this EditText from stealing focus from the actual app input
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(secretInput)

        // --- Row 3: Cover Message Input ---
        coverInput = EditText(context).apply {
            hint = "Cover message (visible text)…"
            setHintTextColor(0xFF666666.toInt())
            setTextColor(0xFFCCCCCC.toInt())
            setBackgroundColor(0xFF222244.toInt())
            textSize = 13f
            minLines = 1
            maxLines = 2
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(coverInput)

        // --- Row 4: Action Buttons ---
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }

        // Auto-generate cover button
        val autoButton = Button(context).apply {
            text = "Auto Cover"
            setTextColor(0xFFBBBBBB.toInt())
            setBackgroundColor(0xFF2A2A4E.toInt())
            textSize = 11f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                coverInput?.setText(CoverGenerator.generate(
                    cryptoInputManager.getActiveKeyInfo()?.coverStyle
                        ?: com.mkcreations.cryptokeyboard.model.CoverStyle.RANDOM_CASUAL
                ))
            }
        }
        buttonRow.addView(autoButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) })

        // Send encrypted button
        sendButton = Button(context).apply {
            text = "🔒 Send"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF4CAF50.toInt())
            textSize = 13f
            setPadding(dp(16), dp(6), dp(16), dp(6))
            setOnClickListener { handleSendEncrypted() }
        }
        buttonRow.addView(sendButton)

        root.addView(buttonRow)

        rootView = root
        return root
    }

    /**
     * Sets up the key selector dropdown
     */
    private fun setupKeySelector() {
        try {
            val keys = cryptoInputManager.getAllKeyInfos()
            if (keys.isEmpty()) {
                val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, listOf("No keys"))
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                keySelector?.adapter = adapter
                return
            }

            val keyLabels = keys.map { "${it.label} (${it.id.take(4)})" }
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, keyLabels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            keySelector?.adapter = adapter

            // Set current selection
            val activeId = SharedState.activeKeyId
            val activeIndex = keys.indexOfFirst { it.id == activeId }
            if (activeIndex >= 0) {
                keySelector?.setSelection(activeIndex)
            }

            keySelector?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (position < keys.size) {
                        cryptoInputManager.setActiveKey(keys[position].id)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup key selector", e)
        }
    }

    /**
     * Handles the encrypt and send action
     */
    private fun handleSendEncrypted() {
        val secret = secretInput?.text?.toString() ?: ""
        val cover = coverInput?.text?.toString() ?: ""

        if (secret.isBlank()) {
            Toast.makeText(context, "Enter a secret message", Toast.LENGTH_SHORT).show()
            return
        }

        val result = cryptoInputManager.encryptMessage(secret, cover)

        when (result) {
            is CryptoInputManager.EncryptionResult.Success -> {
                // Commit the encrypted text to the app's input field
                onCommitText?.invoke(result.finalMessage)

                // Clear fields
                secretInput?.text?.clear()
                coverInput?.text?.clear()

                Toast.makeText(context, "Encrypted with key: ${result.keyLabel}", Toast.LENGTH_SHORT).show()
            }
            is CryptoInputManager.EncryptionResult.Error -> {
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Toggles crypto mode on/off
     */
    private fun toggleCryptoMode() {
        val newState = !SharedState.isCryptoModeActive
        SharedState.isCryptoModeActive = newState
        onModeChanged?.invoke(newState)
        updateModeUI(newState)
    }

    /**
     * Updates the UI to reflect current mode
     */
    fun updateModeUI(isCryptoMode: Boolean) {
        if (isCryptoMode) {
            statusText?.text = "🔒 Crypto Mode"
            statusText?.setTextColor(0xFF4CAF50.toInt())
            modeToggle?.setColorFilter(0xFF4CAF50.toInt())
            secretInput?.visibility = View.VISIBLE
            coverInput?.visibility = View.VISIBLE
            sendButton?.visibility = View.VISIBLE
        } else {
            statusText?.text = "🔓 Normal Mode"
            statusText?.setTextColor(0xFF888888.toInt())
            modeToggle?.setColorFilter(0xFF888888.toInt())
            secretInput?.visibility = View.GONE
            coverInput?.visibility = View.GONE
            sendButton?.visibility = View.GONE
        }
    }

    /**
     * Switches to the previously selected keyboard
     */
    private fun switchToPreviousKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show keyboard picker", e)
            Toast.makeText(context, "Cannot switch keyboard", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Refreshes the key selector (call after key changes)
     */
    fun refreshKeys() {
        setupKeySelector()
    }

    /**
     * Shows or hides the entire crypto bar
     */
    fun setVisible(visible: Boolean) {
        rootView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Gets the current state
     */
    fun isCryptoMode(): Boolean = SharedState.isCryptoModeActive

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}