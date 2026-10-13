// Modified by MK - created CryptoHook file (2026)

package dev.patrickgold.florisboard.ime.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.FlorisImeService
import android.view.KeyEvent
import kotlinx.coroutines.delay

object CryptoBarHook {

    private const val TAG = "CryptoBarHook"

    internal var _isInitialized = false
    internal val _isCryptoModeActive = mutableStateOf(false)
    internal val _activeField = mutableStateOf(CryptoField.NONE)
    internal val _secretMessage = mutableStateOf("")
    internal val _coverMessage = mutableStateOf("")
    internal val _activeKeyLabel = mutableStateOf("No key")
    internal val _activeKeyId = mutableStateOf("")
    internal val _isExpanded = mutableStateOf(false)
    internal val _isExchangeModeActive = mutableStateOf(false)
    internal val _showKeySelector = mutableStateOf(false)
    internal val _allKeyLabels = mutableStateOf<List<Pair<String, String>>>(emptyList())

    private var sharedStateInstance: Any? = null
    private var sharedStateClass: Class<*>? = null
    private var cryptoInputManagerInstance: Any? = null
    private var encryptMethod: java.lang.reflect.Method? = null
    private var getActiveKeyInfoMethod: java.lang.reflect.Method? = null
    private var getAllKeyInfosMethod: java.lang.reflect.Method? = null
    private var setActiveKeyMethod: java.lang.reflect.Method? = null
    private var coverGeneratorInstance: Any? = null
    private var coverGenerateMethod: java.lang.reflect.Method? = null
    private var coverStyleClass: Class<*>? = null
    private var tryDecryptMethod: java.lang.reflect.Method? = null
    private var detectInviteMethod: java.lang.reflect.Method? = null
    private var exchangeModeGetter: java.lang.reflect.Method? = null
    private var exchangeModeSetter: java.lang.reflect.Method? = null
    private var accessibilityServiceClass: Class<*>? = null
    private var accessibilityInstanceField: java.lang.reflect.Field? = null
    private var a11yOnKeyTappedMethod: java.lang.reflect.Method? = null
    private var a11yOnSpacebarGestureMethod: java.lang.reflect.Method? = null

    private var appContext: Context? = null
    private var lockPromptShowing = false

    val isCryptoModeActive: Boolean get() = _isCryptoModeActive.value
    val isCapturingInput: Boolean get() = _isCryptoModeActive.value && _activeField.value != CryptoField.NONE

    enum class CryptoField {
        NONE, SECRET, COVER
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (_isInitialized) return
        try {
            sharedStateClass = Class.forName("com.mkcreations.cryptokeyboard.core.SharedState")
            sharedStateInstance = sharedStateClass!!.kotlin.objectInstance
            sharedStateClass!!.getMethod("init", Context::class.java).invoke(sharedStateInstance, context)

            val cimClass = Class.forName("com.mkcreations.cryptokeyboard.ime.CryptoInputManager")
            cryptoInputManagerInstance = cimClass.getConstructor(Context::class.java).newInstance(context)
            encryptMethod = cimClass.getMethod("encryptMessage", String::class.java, String::class.java)
            getActiveKeyInfoMethod = cimClass.getMethod("getActiveKeyInfo")
            getAllKeyInfosMethod = cimClass.getMethod("getAllKeyInfos")
            setActiveKeyMethod = cimClass.getMethod("setActiveKey", String::class.java)
            tryDecryptMethod = cimClass.getMethod("tryDecrypt", String::class.java)
            detectInviteMethod = cimClass.getMethod("detectInvite", String::class.java)

            val coverGenClass = Class.forName("com.mkcreations.cryptokeyboard.crypto.CoverGenerator")
            coverGeneratorInstance = coverGenClass.kotlin.objectInstance
            coverStyleClass = Class.forName("com.mkcreations.cryptokeyboard.model.CoverStyle")
            coverGenerateMethod = coverGenClass.getMethod("generate", coverStyleClass, String::class.java)

            exchangeModeGetter = sharedStateClass!!.getMethod("isExchangeModeActive")
            exchangeModeSetter = sharedStateClass!!.getMethod("setExchangeModeActive", Boolean::class.javaPrimitiveType)

            try {
                accessibilityServiceClass = Class.forName(
                    "com.mkcreations.cryptokeyboard.accessibility.CryptoAccessibilityService"
                )
                // Panic trigger bridge: cached so per-key-tap calls stay cheap.
                accessibilityInstanceField = accessibilityServiceClass
                    ?.getDeclaredField("instance")
                    ?.apply { isAccessible = true }
                a11yOnKeyTappedMethod = accessibilityServiceClass
                    ?.getMethod("onKeyTapped", Boolean::class.javaPrimitiveType)
                a11yOnSpacebarGestureMethod = accessibilityServiceClass
                    ?.getMethod("onSpacebarPanicGesture")
            } catch (e: Exception) {}

            val cryptoGetter = sharedStateClass!!.getMethod("isCryptoModeActive")
            _isCryptoModeActive.value = cryptoGetter.invoke(sharedStateInstance) as? Boolean ?: false
            _isExchangeModeActive.value = exchangeModeGetter?.invoke(sharedStateInstance) as? Boolean ?: false

            refreshKeyLabel()
            refreshAllKeys()

            _isInitialized = true
            Log.d(TAG, "Initialized")
        } catch (e: ClassNotFoundException) {
            _isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            _isInitialized = false
        }
    }

    fun toggleCryptoMode() {
        if (!_isInitialized) return
        try {
            val newState = !_isCryptoModeActive.value
            _isCryptoModeActive.value = newState
            if (!newState) {
                _activeField.value = CryptoField.NONE
                _isExpanded.value = false
                _showKeySelector.value = false
            } else {
                _activeField.value = CryptoField.SECRET
                _isExpanded.value = true
                refreshKeyLabel()
                refreshAllKeys()
            }
            sharedStateClass!!.getMethod("setCryptoModeActive", Boolean::class.javaPrimitiveType)
                .invoke(sharedStateInstance, newState)
            // Bar resize shifts the chat layout above — recalculate overlay
            // positions once the debounce lets the layout settle.
            doRefreshOverlays()
        } catch (e: Exception) {
            Log.e(TAG, "toggleCryptoMode failed", e)
        }
    }

    /**
     * Expands the compact bar and activates the Secret field. Used by every
     * "Tap ▴ to compose" entry point (text tap, ▴ button, swipe-up) so opening
     * the bar always lands focus on the Secret field, matching what the strip
     * toggle already does.
     */
    fun expandAndFocusSecret() {
        if (!_isInitialized) return
        _isExpanded.value = true
        _activeField.value = CryptoField.SECRET
        doRefreshOverlays()
    }

    /**
     * The user interacted with a text field on the screen while a crypto field
     * was active. Re-target input: if both fields are still empty the bar has
     * nothing to protect, so close it entirely; if anything is typed, keep the
     * bar open (content stays visible) but deactivate the crypto field so
     * keystrokes go to the tapped field instead of blinking carets in the bar.
     * No-op when no crypto field is active, so normal typing in the app never
     * collapses the bar.
     */
    fun onAppFieldInteraction() {
        if (!_isInitialized) return
        if (!_isCryptoModeActive.value) return
        if (_activeField.value == CryptoField.NONE) return
        if (_secretMessage.value.isEmpty() && _coverMessage.value.isEmpty()) {
            toggleCryptoMode() // both empty → close the bar (incl. SharedState sync)
        } else {
            _activeField.value = CryptoField.NONE
        }
    }

    fun toggleExchangeMode() {
        if (!_isInitialized) return
        try {
            val newState = !_isExchangeModeActive.value
            _isExchangeModeActive.value = newState
            exchangeModeSetter?.invoke(sharedStateInstance, newState)

            try {
                val serviceInstance = accessibilityServiceClass
                    ?.getDeclaredField("instance")
                    ?.apply { isAccessible = true }
                    ?.get(null)
                if (serviceInstance != null) {
                    accessibilityServiceClass
                        ?.getMethod("onExchangeModeChanged", Boolean::class.javaPrimitiveType)
                        ?.invoke(serviceInstance, newState)
                }
            } catch (e: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "toggleExchangeMode failed", e)
        }
    }

    /**
     * Reports a completed key tap to the accessibility service so the
     * spacebar triple-tap panic trigger can count consecutive space taps.
     * Any non-space key resets the service's burst counter.
     */
    fun onKeyTapped(isSpacebar: Boolean) {
        if (!_isInitialized) return
        try {
            val service = accessibilityInstanceField?.get(null) ?: return
            a11yOnKeyTappedMethod?.invoke(service, isSpacebar)
        } catch (e: Exception) {}
    }

    /**
     * Spacebar quick-swipe panic trigger. Returns true when the gesture was
     * consumed as a panic gesture (panic system armed + trigger enabled).
     */
    fun onSpacebarPanicSwipe(): Boolean {
        if (!_isInitialized) return false
        return try {
            val service = accessibilityInstanceField?.get(null) ?: return false
            a11yOnSpacebarGestureMethod?.invoke(service) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun interceptCommitText(text: String): Boolean {
        if (!_isCryptoModeActive.value || _activeField.value == CryptoField.NONE) return false
        when (_activeField.value) {
            CryptoField.SECRET -> _secretMessage.value += text
            CryptoField.COVER -> _coverMessage.value += text
            CryptoField.NONE -> return false
        }
        return true
    }

    fun interceptDelete(): Boolean {
        if (!_isCryptoModeActive.value || _activeField.value == CryptoField.NONE) return false
        when (_activeField.value) {
            CryptoField.SECRET -> {
                val c = _secretMessage.value
                if (c.isNotEmpty()) _secretMessage.value = c.dropLast(1)
            }
            CryptoField.COVER -> {
                val c = _coverMessage.value
                if (c.isNotEmpty()) _coverMessage.value = c.dropLast(1)
            }
            CryptoField.NONE -> return false
        }
        return true
    }

    fun onKeyboardShow() {
        if (!_isInitialized) return
        try {
            sharedStateClass!!.getMethod("setKeyboardVisible", Boolean::class.javaPrimitiveType)
                .invoke(sharedStateInstance, true)
            sharedStateClass!!.getMethod("recordActivity").invoke(sharedStateInstance)
            refreshKeyLabel()
            refreshAllKeys()
            _isExchangeModeActive.value = exchangeModeGetter?.invoke(sharedStateInstance) as? Boolean ?: false
        } catch (e: Exception) {}
        maybeLaunchAppLock()
    }

    fun onKeyboardHide() {
        if (!_isInitialized) return
        // lockPromptShowing is intentionally NOT reset here: hiding the IME
        // while the lock prompt launches must not re-arm a relaunch (loop
        // risk). It re-arms via onAppLockClosed() and when the lock disengages.
        _activeField.value = CryptoField.NONE
        _showKeySelector.value = false
        try {
            sharedStateClass!!.getMethod("setKeyboardVisible", Boolean::class.javaPrimitiveType)
                .invoke(sharedStateInstance, false)
        } catch (e: Exception) {}
    }

    /**
     * Shows the PIN gate when the app lock has engaged. Reflective so this
     * file (main source set) can reference the crypto-flavor classes; on the
     * floris flavor the classes are absent and the call is a no-op.
     */
    private fun maybeLaunchAppLock() {
        val ctx = appContext ?: return
        try {
            val storeClass = Class.forName("com.mkcreations.cryptokeyboard.storage.SettingsStore")
            val getInstance = storeClass.getMethod("getInstance", Context::class.java)
            val store = getInstance.invoke(null, ctx)
            val locked = storeClass.getMethod("isAppLocked").invoke(store) as? Boolean ?: false
            if (!locked) {
                lockPromptShowing = false // lock released — arm for the next engagement
                return
            }
            if (lockPromptShowing) return // prompt already alive (singleTop absorbs repeats)
            val intent = Intent(ctx, Class.forName("com.mkcreations.cryptokeyboard.ui.AppLockActivity"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            lockPromptShowing = true
        } catch (e: ClassNotFoundException) {
            // floris flavor — no crypto app lock
        } catch (e: Exception) {
            Log.e(TAG, "App lock launch failed", e)
        }
    }

    /** Called by AppLockActivity.onDestroy so the launch guard re-arms. */
    fun onAppLockClosed() {
        lockPromptShowing = false
    }

    fun destroy() {
        appContext = null
        lockPromptShowing = false
        sharedStateInstance = null
        sharedStateClass = null
        cryptoInputManagerInstance = null
        encryptMethod = null
        getActiveKeyInfoMethod = null
        getAllKeyInfosMethod = null
        setActiveKeyMethod = null
        tryDecryptMethod = null
        detectInviteMethod = null
        coverGeneratorInstance = null
        coverGenerateMethod = null
        coverStyleClass = null
        exchangeModeGetter = null
        exchangeModeSetter = null
        accessibilityServiceClass = null
        _isInitialized = false
        _isCryptoModeActive.value = false
        _activeField.value = CryptoField.NONE
        _secretMessage.value = ""
        _coverMessage.value = ""
        _activeKeyLabel.value = "No key"
        _activeKeyId.value = ""
        _isExpanded.value = false
        _isExchangeModeActive.value = false
        _showKeySelector.value = false
        _allKeyLabels.value = emptyList()
    }

    internal fun doEncrypt(secret: String, cover: String): String? {
        if (!_isInitialized || cryptoInputManagerInstance == null) return null
        return try {
            val result = encryptMethod?.invoke(cryptoInputManagerInstance, secret, cover) ?: return null
            val successClass = Class.forName(
                "com.mkcreations.cryptokeyboard.ime.CryptoInputManager\$EncryptionResult\$Success"
            )
            if (successClass.isInstance(result)) {
                successClass.getMethod("getFinalMessage").invoke(result) as? String
            } else {
                val errorClass = Class.forName(
                    "com.mkcreations.cryptokeyboard.ime.CryptoInputManager\$EncryptionResult\$Error"
                )
                if (errorClass.isInstance(result)) {
                    Log.e(TAG, "Encrypt error: ${errorClass.getMethod("getMessage").invoke(result)}")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "doEncrypt failed", e)
            null
        }
    }

    internal fun doDecrypt(message: String): DecryptedInfo? {
        if (!_isInitialized || cryptoInputManagerInstance == null) return null
        return try {
            val result = tryDecryptMethod?.invoke(cryptoInputManagerInstance, message) ?: return null
            val successClass = Class.forName(
                "com.mkcreations.cryptokeyboard.ime.CryptoInputManager\$DecryptionResult\$Success"
            )
            if (successClass.isInstance(result)) {
                DecryptedInfo(
                    secretMessage = successClass.getMethod("getSecretMessage").invoke(result) as? String ?: "",
                    keyLabel = successClass.getMethod("getKeyLabel").invoke(result) as? String ?: "?"
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "doDecrypt failed", e)
            null
        }
    }

    /**
     * True when [message] carries a valid in-band key invite (Path B). Reachable
     * from the main source set via reflection; always false on the floris flavor
     * where CryptoInputManager doesn't exist.
     */
    internal fun doDetectInvite(message: String): Boolean {
        if (!_isInitialized || cryptoInputManagerInstance == null) return false
        return try {
            detectInviteMethod?.invoke(cryptoInputManagerInstance, message) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Asks the accessibility service to kick a debounced overlay rescan so
     * overlay positions/messages are recalculated after a known button press
     * (e.g. secret-field expand/collapse) shifts the layout. No-op on the floris
     * flavor, where the crypto accessibility service is absent.
     */
    internal fun doRefreshOverlays() {
        if (!_isInitialized) return
        try {
            val serviceInstance = accessibilityServiceClass
                ?.getDeclaredField("instance")
                ?.apply { isAccessible = true }
                ?.get(null)
            if (serviceInstance != null) {
                accessibilityServiceClass
                    ?.getMethod("requestOverlayRefresh")
                    ?.invoke(serviceInstance)
            }
        } catch (e: Exception) {
            // floris flavor / service not connected — safe to ignore
        }
    }

    /**
     * Sends encrypted message by:
     * 1. Committing text to the input field
     * 2. Simulating Enter/Send key press to actually send it
     *
     * Tries multiple strategies to trigger send:
     * - EditorInfo action (ACTION_SEND, ACTION_DONE, etc.)
     * - Enter key event
     * - Search action
     */
    internal fun sendEncryptedMessage(context: Context, encrypted: String): Boolean {
        val ic = FlorisImeService.currentInputConnection() ?: return false

        try {
            // Step 1: Clear any existing text in the input field
            ic.beginBatchEdit()

            // Select all existing text and delete it
            val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            if (extracted != null && extracted.text != null && extracted.text.isNotEmpty()) {
                ic.setSelection(0, extracted.text.length)
                ic.commitText("", 1)
            }

            // Step 2: Commit the encrypted text
            ic.commitText(encrypted, 1)
            ic.endBatchEdit()

            // Step 3: Trigger send — try multiple strategies
            val sent = trySendViaEditorAction(ic)
                    || trySendViaEnterKey(ic)
                    || trySendViaPerformAction(ic)

            if (sent) {
                Log.d(TAG, "Message sent successfully")
            } else {
                Log.d(TAG, "Send key simulation failed — text committed but not auto-sent")
                // Text is still in the input field — user can manually tap send
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "sendEncryptedMessage failed", e)
            return false
        }
    }

    /**
     * Strategy 1: Use EditorInfo action
     * Most messengers use ACTION_SEND for their send button
     */
    private fun trySendViaEditorAction(ic: InputConnection): Boolean {
        return try {
            val editorInfo = FlorisImeService.currentEditorInfo()
            if (editorInfo != null) {
                val imeAction = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
                when (imeAction) {
                    EditorInfo.IME_ACTION_SEND -> {
                        ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
                        true
                    }
                    EditorInfo.IME_ACTION_DONE -> {
                        ic.performEditorAction(EditorInfo.IME_ACTION_DONE)
                        true
                    }
                    EditorInfo.IME_ACTION_GO -> {
                        ic.performEditorAction(EditorInfo.IME_ACTION_GO)
                        true
                    }
                    else -> {
                        // Try ACTION_SEND anyway — many messengers support it
                        ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
                        true
                    }
                }
            } else {
                ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "EditorAction send failed: ${e.message}")
            false
        }
    }

    /**
     * Strategy 2: Simulate Enter key press
     * Some messengers send on Enter
     */
    private fun trySendViaEnterKey(ic: InputConnection): Boolean {
        return try {
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
            ic.sendKeyEvent(downEvent)
            ic.sendKeyEvent(upEvent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "Enter key send failed: ${e.message}")
            false
        }
    }

    /**
     * Strategy 3: Perform action via InputConnection
     */
    private fun trySendViaPerformAction(ic: InputConnection): Boolean {
        return try {
            ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets the current EditorInfo from FlorisImeService
     */
    private fun FlorisImeService.Companion.currentEditorInfo(): EditorInfo? {
        return try {
            val serviceClass = Class.forName("dev.patrickgold.florisboard.FlorisImeService")
            // Try to get the current editor info via reflection
            val instanceField = serviceClass.getDeclaredField("currentInstance")
            instanceField.isAccessible = true
            val instance = instanceField.get(null)
            if (instance != null) {
                val method = serviceClass.getMethod("getCurrentInputEditorInfo")
                method.invoke(instance) as? EditorInfo
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "Could not get EditorInfo: ${e.message}")
            null
        }
    }

    internal fun refreshKeyLabel() {
        if (!_isInitialized || cryptoInputManagerInstance == null) return
        try {
            val info = getActiveKeyInfoMethod?.invoke(cryptoInputManagerInstance)
            if (info != null) {
                _activeKeyLabel.value = info::class.java.getMethod("getLabel").invoke(info) as? String ?: "No key"
                _activeKeyId.value = info::class.java.getMethod("getId").invoke(info) as? String ?: ""
            } else {
                _activeKeyLabel.value = "No key"
                _activeKeyId.value = ""
            }
        } catch (e: Exception) {
            _activeKeyLabel.value = "No key"
            _activeKeyId.value = ""
        }
    }

    internal fun refreshAllKeys() {
        if (!_isInitialized || cryptoInputManagerInstance == null) return
        try {
            val allKeys = getAllKeyInfosMethod?.invoke(cryptoInputManagerInstance) as? List<*> ?: return
            _allKeyLabels.value = allKeys.map { keyInfo ->
                val id = keyInfo!!::class.java.getMethod("getId").invoke(keyInfo) as? String ?: ""
                val label = keyInfo::class.java.getMethod("getLabel").invoke(keyInfo) as? String ?: ""
                id to label
            }
        } catch (e: Exception) {}
    }

    internal fun selectKey(keyId: String) {
        if (!_isInitialized || cryptoInputManagerInstance == null) return
        try {
            setActiveKeyMethod?.invoke(cryptoInputManagerInstance, keyId)
            refreshKeyLabel()
            _showKeySelector.value = false
        } catch (e: Exception) {}
    }

    internal fun cycleKey() {
        if (!_isInitialized) return
        val keys = _allKeyLabels.value
        if (keys.size <= 1) return
        val currentId = _activeKeyId.value
        val currentIndex = keys.indexOfFirst { it.first == currentId }
        val nextIndex = if (currentIndex < 0 || currentIndex >= keys.size - 1) 0 else currentIndex + 1
        selectKey(keys[nextIndex].first)
    }

    internal fun generateCover(): String {
        if (!_isInitialized || coverGeneratorInstance == null) return "ok"
        return try {
            val randomCasual = coverStyleClass!!.enumConstants?.firstOrNull() ?: return "ok"
            coverGenerateMethod?.invoke(coverGeneratorInstance, randomCasual, "") as? String ?: "ok"
        } catch (e: Exception) { "ok" }
    }

    data class DecryptedInfo(
        val secretMessage: String,
        val keyLabel: String
    )
}

// ============================================================
// COMPOSE UI
// ============================================================

@Composable
fun CryptoBarHook() {
    if (!CryptoBarHook._isInitialized) return

    val isCryptoMode by CryptoBarHook._isCryptoModeActive
    val isExpanded by CryptoBarHook._isExpanded

    Column(modifier = Modifier.fillMaxWidth()) {
        CryptoModeStrip(isCryptoMode)

        AnimatedVisibility(
            visible = isCryptoMode,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(),
            exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(),
        ) {
            if (isExpanded) {
                ExpandedCryptoBar()
            } else {
                CompactCryptoBar()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CryptoModeStrip(isCryptoMode: Boolean) {
    val haptic = LocalHapticFeedback.current
    val isExchange by CryptoBarHook._isExchangeModeActive

    val bgBrush = if (isCryptoMode) {
        Brush.horizontalGradient(listOf(Color(0xFF0A2E14), Color(0xFF0D1B2A)))
    } else {
        Brush.horizontalGradient(listOf(Color(0xFF12121F), Color(0xFF12121F)))
    }

    val indicatorColor by animateColorAsState(
        targetValue = if (isCryptoMode) Color(0xFF4CAF50) else Color(0xFF444455),
        animationSpec = tween(300), label = "strip"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgBrush)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                CryptoBarHook.toggleCryptoMode()
            }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Text(
                text = if (isCryptoMode) "CRYPTO" else "NORMAL",
                color = indicatorColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isCryptoMode) {
                val exchangeColor by animateColorAsState(
                    targetValue = if (isExchange) Color(0xFF2196F3) else Color(0xFF444455),
                    animationSpec = tween(200), label = "exchange"
                )
                Text(
                    text = if (isExchange) "👁 ON" else "👁",
                    color = exchangeColor,
                    fontSize = 10.sp,
                    fontWeight = if (isExchange) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isExchange) Color(0xFF0D1B3A) else Color.Transparent)
                        .combinedClickable(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                CryptoBarHook.toggleExchangeMode()
                            },
                            onLongClick = {}
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                Text(
                    text = if (CryptoBarHook._isExpanded.value) "▾" else "▸",
                    color = Color(0xFF666677),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        CryptoBarHook._isExpanded.value = !CryptoBarHook._isExpanded.value
                    }
                )
            }

            Text(
                text = "⌨",
                color = Color(0xFF444455),
                fontSize = 12.sp,
                modifier = Modifier.clickable { FlorisImeService.switchToPrevInputMethod() },
            )
        }
    }
}

@Composable
private fun CompactCryptoBar() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val secretMessage by CryptoBarHook._secretMessage
    val activeKeyLabel by CryptoBarHook._activeKeyLabel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A2E))
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount < -20) CryptoBarHook.expandAndFocusSecret()
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KeyPill(
            label = activeKeyLabel,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                CryptoBarHook.cycleKey()
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                CryptoBarHook._showKeySelector.value = true
                CryptoBarHook._isExpanded.value = true
            }
        )

        Text(
            text = if (secretMessage.isNotEmpty()) {
                "\"${secretMessage.take(30)}${if (secretMessage.length > 30) "…" else ""}\""
            } else "Tap ▴ to compose",
            color = if (secretMessage.isNotEmpty()) Color(0xFFCCCCDD) else Color(0xFF555566),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable { CryptoBarHook.expandAndFocusSecret() }
        )

        if (secretMessage.isNotEmpty()) {
            QuickSendButton(context)
        }

        Text(
            text = "▴",
            color = Color(0xFF666677),
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF222244))
                .clickable { CryptoBarHook.expandAndFocusSecret() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ExpandedCryptoBar() {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val activeField by CryptoBarHook._activeField
    val secretMessage by CryptoBarHook._secretMessage
    val coverMessage by CryptoBarHook._coverMessage
    val activeKeyLabel by CryptoBarHook._activeKeyLabel
    val showKeySelector by CryptoBarHook._showKeySelector
    val isExchange by CryptoBarHook._isExchangeModeActive
    val allKeys by CryptoBarHook._allKeyLabels
    val activeKeyId by CryptoBarHook._activeKeyId

    var showSendSuccess by remember { mutableStateOf(false) }

    val estimatedSize = remember(secretMessage) {
        if (secretMessage.isEmpty()) 0
        else 1 + 12 + secretMessage.toByteArray(Charsets.UTF_8).size + 16
    }
    val invisibleChars = estimatedSize * 2
    val totalChars = invisibleChars + coverMessage.length + 8
    val isNearLimit = totalChars > 3000
    val isOverLimit = totalChars > 4000

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), Color(0xFF151528))))
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (dragAmount > 20) CryptoBarHook._isExpanded.value = false
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyPill(
                label = activeKeyLabel,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    CryptoBarHook._showKeySelector.value = !showKeySelector
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    CryptoBarHook.cycleKey()
                }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (secretMessage.isNotEmpty()) {
                    val statsColor = when {
                        isOverLimit -> Color(0xFFFF5252)
                        isNearLimit -> Color(0xFFFF9800)
                        else -> Color(0xFF555566)
                    }
                    Text(
                        text = "${secretMessage.length}c · ${totalChars}/4k",
                        color = statsColor,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                ClipboardDecryptButton(context)

                Text(
                    text = "▾",
                    color = Color(0xFF666677),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF222244))
                        .clickable { CryptoBarHook._isExpanded.value = false }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = showKeySelector && allKeys.size > 1,
            enter = expandVertically(tween(150)) + fadeIn(),
            exit = shrinkVertically(tween(100)) + fadeOut()
        ) {
            KeySelectorPanel(
                keys = allKeys,
                activeKeyId = activeKeyId,
                onSelect = { keyId ->
                    CryptoBarHook.selectKey(keyId)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        CryptoInputField(
            label = "🔒 Secret",
            value = secretMessage,
            isActive = activeField == CryptoBarHook.CryptoField.SECRET,
            activeColor = Color(0xFF4CAF50),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                CryptoBarHook._activeField.value = CryptoBarHook.CryptoField.SECRET
            },
            onClear = { CryptoBarHook._secretMessage.value = "" },
            placeholder = "Tap here, type your secret…",
        )

        Spacer(modifier = Modifier.height(4.dp))

        CryptoInputField(
            label = "👁 Cover",
            value = coverMessage,
            isActive = activeField == CryptoBarHook.CryptoField.COVER,
            activeColor = Color(0xFF2196F3),
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                CryptoBarHook._activeField.value = CryptoBarHook.CryptoField.COVER
            },
            onClear = { CryptoBarHook._coverMessage.value = "" },
            placeholder = "Cover text (optional)…",
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (activeField != CryptoBarHook.CryptoField.NONE) {
                ActionChip(
                    text = "▼ Normal",
                    textColor = Color(0xFF888899),
                    bgColor = Color(0xFF1E1E33),
                    onClick = { CryptoBarHook._activeField.value = CryptoBarHook.CryptoField.NONE }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            ActionChip(
                text = "⚡ Auto",
                textColor = Color(0xFF9999BB),
                bgColor = Color(0xFF252545),
                onClick = {
                    CryptoBarHook._coverMessage.value = CryptoBarHook.generateCover()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            )

            if (secretMessage.isNotEmpty() || coverMessage.isNotEmpty()) {
                ActionChip(
                    text = "✕ Clear",
                    textColor = Color(0xFF886666),
                    bgColor = Color(0xFF251E1E),
                    onClick = {
                        CryptoBarHook._secretMessage.value = ""
                        CryptoBarHook._coverMessage.value = ""
                    }
                )
            }

            SendButton(
                context = context,
                secretMessage = secretMessage,
                coverMessage = coverMessage,
                isOverLimit = isOverLimit,
                onSuccess = {
                    showSendSuccess = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
        }

        AnimatedVisibility(
            visible = showSendSuccess,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(500))
        ) {
            LaunchedEffect(showSendSuccess) {
                delay(1200)
                showSendSuccess = false
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1B5E20))
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("✓ Encrypted & sent!", color = Color(0xFF81C784), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ============================================================
// COMPONENTS
// ============================================================

@Composable
private fun KeySelectorPanel(
    keys: List<Pair<String, String>>,
    activeKeyId: String,
    onSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF151530))
            .padding(6.dp)
    ) {
        Text(
            text = "SELECT KEY",
            color = Color(0xFF555566),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
        )

        keys.forEach { (id, label) ->
            val isActive = id == activeKeyId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) Color(0xFF1B3A1B) else Color(0xFF1A1A30))
                    .border(
                        1.dp,
                        if (isActive) Color(0xFF4CAF50) else Color.Transparent,
                        RoundedCornerShape(6.dp)
                    )
                    .clickable { onSelect(id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "🔑", fontSize = 11.sp)
                    Text(
                        text = label,
                        color = if (isActive) Color(0xFF81C784) else Color(0xFFAAAABB),
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isActive) {
                        Text("✓", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(id.take(8), color = Color(0xFF444455), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyPill(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val hasMultipleKeys = CryptoBarHook._allKeyLabels.value.size > 1

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B3A1B))
            .border(1.dp, Color(0xFF2E7D32), RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = "🔑", fontSize = 10.sp)
        Text(
            text = label,
            color = Color(0xFF81C784),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 80.dp)
        )
        if (hasMultipleKeys) {
            Text(text = "⟳", color = Color(0xFF558855), fontSize = 10.sp)
        }
    }
}

@Composable
private fun CryptoInputField(
    label: String,
    value: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    onClear: () -> Unit,
    placeholder: String,
) {
    val borderColor by animateColorAsState(
        if (isActive) activeColor else Color.Transparent, tween(200), label = "border"
    )
    val bgColor by animateColorAsState(
        if (isActive) Color(0xFF222244) else Color(0xFF1E1E38), tween(200), label = "bg"
    )

    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(isActive) {
        if (isActive) {
            while (true) { delay(530); cursorVisible = !cursorVisible }
        } else cursorVisible = false
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (isActive) activeColor else Color(0xFF555566),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Reserved caret slot: fixed 6.dp x 16.dp, always composed, and
                    // the caret is a plain Spacer rectangle (not text), so its blink
                    // has zero font-metric or line-height coupling — the row and the
                    // whole bar keep an identical size on every blink. The caret
                    // renders at insertion point 0 (before the hint), and the hint
                    // keeps its unfocused string on focus — placeholder behavior.
                    CaretSlot(isActive = isActive, visible = cursorVisible, activeColor = activeColor)
                    Text(
                        placeholder,
                        color = if (isActive) activeColor.copy(alpha = 0.4f) else Color(0xFF3A3A4A),
                        fontSize = 13.sp,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(value, color = if (isActive) Color(0xFFEEEEFF) else Color(0xFFAAAABB), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    CaretSlot(isActive = isActive, visible = cursorVisible, activeColor = activeColor)
                }
            }
        }

        if (value.isNotEmpty()) {
            Text(
                "✕", color = Color(0xFF555566), fontSize = 11.sp,
                modifier = Modifier.clip(CircleShape).background(Color(0xFF2A2A44)).clickable { onClear() }.padding(4.dp)
            )
        }
    }
}

/**
 * Fixed-size caret slot: the dimensions (6.dp x 16.dp) never change and the
 * caret is drawn as a plain rectangle, so blinking cannot reflow the row — the
 * bar size stays constant regardless of font metrics or glyph fallback.
 */
@Composable
private fun CaretSlot(isActive: Boolean, visible: Boolean, activeColor: Color) {
    Box(modifier = Modifier.width(6.dp).height(16.dp)) {
        if (isActive && visible) {
            Spacer(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(1.5.dp)
                    .height(16.dp)
                    .background(activeColor)
            )
        }
    }
}

@Composable
private fun ActionChip(text: String, textColor: Color, bgColor: Color, onClick: () -> Unit) {
    Text(
        text = text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bgColor).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

@Composable
private fun SendButton(
    context: Context,
    secretMessage: String,
    coverMessage: String,
    isOverLimit: Boolean,
    onSuccess: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (isPressed) 0.93f else 1f,
        spring(stiffness = Spring.StiffnessHigh), label = "scale"
    )

    val bgColor = when {
        secretMessage.isEmpty() -> Color(0xFF2A3A2A)
        isOverLimit -> Color(0xFF5D2020)
        else -> Color(0xFF2E7D32)
    }
    val textColor = when {
        secretMessage.isEmpty() -> Color(0xFF556655)
        isOverLimit -> Color(0xFFFF8A80)
        else -> Color.White
    }

    Text(
        text = if (isOverLimit) "⚠ Too long" else "🔒 Send",
        color = textColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(enabled = secretMessage.isNotEmpty() && !isOverLimit) {
                isPressed = true

                // Deselect field so typing goes back to app
                CryptoBarHook._activeField.value = CryptoBarHook.CryptoField.NONE

                val encrypted = CryptoBarHook.doEncrypt(secretMessage, coverMessage)
                if (encrypted != null) {
                    // Use the new send method that simulates Enter/Send
                    val sent = CryptoBarHook.sendEncryptedMessage(context, encrypted)
                    CryptoBarHook._secretMessage.value = ""
                    CryptoBarHook._coverMessage.value = ""
                    if (sent) {
                        onSuccess()
                    } else {
                        Toast.makeText(context, "Text placed — tap send manually", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Encrypt failed — check key in Settings", Toast.LENGTH_LONG).show()
                }
                isPressed = false
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun QuickSendButton(context: Context) {
    val haptic = LocalHapticFeedback.current
    Text(
        text = "🔒▸",
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2E7D32))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val secret = CryptoBarHook._secretMessage.value
                val cover = CryptoBarHook._coverMessage.value
                if (secret.isNotEmpty()) {
                    CryptoBarHook._activeField.value = CryptoBarHook.CryptoField.NONE
                    val encrypted = CryptoBarHook.doEncrypt(secret, cover)
                    if (encrypted != null) {
                        val sent = CryptoBarHook.sendEncryptedMessage(context, encrypted)
                        CryptoBarHook._secretMessage.value = ""
                        CryptoBarHook._coverMessage.value = ""
                        if (sent) {
                            Toast.makeText(context, "✓ Sent!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun ClipboardDecryptButton(context: Context) {
    var decryptResult by remember { mutableStateOf<CryptoBarHook.DecryptedInfo?>(null) }
    var showResult by remember { mutableStateOf(false) }

    Column {
        Text(
            text = "📋🔓",
            fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF252535))
                .clickable {
                    try {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                        if (clipText.isNullOrEmpty()) {
                            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                            return@clickable
                        }
                        // In-band key invite (Path B): launch the approval
                        // Activity instead of showing a decrypt result panel.
                        if (CryptoBarHook.doDetectInvite(clipText)) {
                            val intent = Intent().setClassName(
                                context,
                                "com.mkcreations.cryptokeyboard.ui.KeyInviteApprovalActivity"
                            ).putExtra("invite_raw_message", clipText)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Invite approval unavailable", Toast.LENGTH_SHORT).show()
                            }
                            return@clickable
                        }
                        val result = CryptoBarHook.doDecrypt(clipText)
                        if (result != null) {
                            decryptResult = result
                            showResult = true
                        } else {
                            Toast.makeText(context, "No hidden message in clipboard", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Clipboard access failed", Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(horizontal = 6.dp, vertical = 3.dp)
        )

        AnimatedVisibility(
            visible = showResult,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            decryptResult?.let { info ->
                LaunchedEffect(showResult) { delay(8000); showResult = false }
                Box(
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0D2818))
                        .border(1.dp, Color(0xFF2E7D32), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🔓 Key: ${info.keyLabel}", color = Color(0xFF4CAF50), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text("✕", color = Color(0xFF666666), fontSize = 10.sp, modifier = Modifier.clickable { showResult = false })
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(info.secretMessage, color = Color(0xFFE0E0E0), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}