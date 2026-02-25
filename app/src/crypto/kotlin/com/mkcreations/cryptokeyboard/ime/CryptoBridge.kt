package com.mkcreations.cryptokeyboard.ime

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.mkcreations.cryptokeyboard.core.SharedState

/**
 * THE BRIDGE — connects our crypto layer to FlorisBoard's IME
 * without modifying FlorisBoard source code.
 *
 * This class provides static methods that can be called from
 * a single, minimal hook point in FlorisBoard's code.
 *
 * HOOK STRATEGY:
 * We need exactly ONE modification in FlorisBoard's code:
 * In their main keyboard view composition, we call CryptoBridge.onKeyboardViewCreated()
 * which injects our crypto bar above the keyboard.
 */
object CryptoBridge {

    private const val TAG = "CryptoBridge"

    private var cryptoBar: CryptoBarComponent? = null
    private var cryptoInputManager: CryptoInputManager? = null
    private var spacebarGesture: SpacebarGestureHandler? = null
    private var isInitialized = false

    /**
     * Initialize the bridge. Call once from FlorisBoard's onCreate or onCreateInputView.
     */
    fun initialize(context: Context) {
        if (isInitialized) return

        try {
            SharedState.init(context)
            cryptoInputManager = CryptoInputManager(context)
            cryptoBar = CryptoBarComponent(context, cryptoInputManager!!)

            // Setup spacebar gesture handler
            spacebarGesture = SpacebarGestureHandler(
                onSwipeLeft = {
                    Log.d(TAG, "Spacebar swipe LEFT → toggle crypto mode")
                    toggleCryptoMode()
                },
                onSwipeRight = {
                    Log.d(TAG, "Spacebar swipe RIGHT → toggle crypto mode")
                    toggleCryptoMode()
                }
            )

            isInitialized = true
            Log.d(TAG, "CryptoBridge initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CryptoBridge", e)
        }
    }

    /**
     * Creates and returns the crypto bar view to be injected above the keyboard.
     * The caller should add this view to the keyboard's root layout.
     *
     * @param commitTextCallback Lambda that commits text to the current input field
     */
    fun createCryptoBarView(
        context: Context,
        commitTextCallback: (String) -> Unit
    ): View? {
        initialize(context)

        return try {
            val bar = cryptoBar ?: return null
            bar.onCommitText = commitTextCallback
            bar.onModeChanged = { isCrypto ->
                Log.d(TAG, "Crypto mode changed: $isCrypto")
            }

            val view = bar.createView()
            bar.updateModeUI(SharedState.isCryptoModeActive)
            view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create crypto bar view", e)
            null
        }
    }

    /**
     * Toggles crypto mode and updates the UI
     */
    fun toggleCryptoMode() {
        val newState = !SharedState.isCryptoModeActive
        SharedState.isCryptoModeActive = newState
        cryptoBar?.updateModeUI(newState)
        Log.d(TAG, "Crypto mode toggled: $newState")
    }

    /**
     * Get the spacebar gesture handler to attach to spacebar
     */
    fun getSpacebarGestureHandler(): SpacebarGestureHandler? = spacebarGesture

    /**
     * Notify that the keyboard is being shown
     */
    fun onKeyboardShow() {
        SharedState.isKeyboardVisible = true
        SharedState.recordActivity()
        cryptoBar?.refreshKeys()
    }

    /**
     * Notify that the keyboard is being hidden
     */
    fun onKeyboardHide() {
        SharedState.isKeyboardVisible = false
    }

    /**
     * Check if crypto mode is active
     */
    fun isCryptoMode(): Boolean = SharedState.isCryptoModeActive

    /**
     * Clean up
     */
    fun destroy() {
        cryptoBar = null
        cryptoInputManager = null
        spacebarGesture = null
        isInitialized = false
    }
}