package com.mkcreations.cryptokeyboard.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.mkcreations.cryptokeyboard.crypto.QrCodeHelper
import com.mkcreations.cryptokeyboard.model.CipherKey
import java.util.concurrent.Executors

private enum class PermissionState {
    CHECKING,
    GRANTED,
    DENIED,
    REQUESTING
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
fun QrScannerScreen(
    onKeyScanned: (CipherKey) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var permissionState by remember {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        mutableStateOf(
            if (hasPermission) PermissionState.GRANTED
            else PermissionState.CHECKING
        )
    }

    var hasScanned by remember { mutableStateOf(false) }
    // State precision: only treat the permission as blocked after a real
    // request attempt — "rationale == false" before any dialog was ever
    // shown means "never asked", not "permanently blocked".
    var hasAttemptedRequest by remember { mutableStateOf(false) }
    var permissionBlocked by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var flashEnabled by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAttemptedRequest = true
        if (granted) {
            permissionBlocked = false
            permissionState = PermissionState.GRANTED
        } else {
            permissionBlocked = computePermissionBlocked(context, hasAttemptedRequest = true)
            permissionState = PermissionState.DENIED
        }
    }

    // Auto-request permission on first open if not already granted
    LaunchedEffect(permissionState) {
        if (permissionState == PermissionState.CHECKING) {
            permissionState = PermissionState.REQUESTING
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Seamless resume: re-check the real permission status whenever the
    // screen comes back to the foreground. Granted in the app's Settings
    // page → jump straight into the camera preview; revoked → back to the
    // denied screen with a freshly computed blocked-state. An in-flight
    // request keeps waiting for its launcher callback instead of racing it.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                when {
                    granted -> {
                        permissionBlocked = false
                        permissionState = PermissionState.GRANTED
                    }
                    permissionState == PermissionState.GRANTED -> {
                        permissionBlocked = computePermissionBlocked(context, hasAttemptedRequest)
                        permissionState = PermissionState.DENIED
                    }
                    permissionState == PermissionState.REQUESTING -> {
                        // Dialog still deciding — the launcher callback decides.
                    }
                    else -> {
                        permissionBlocked = computePermissionBlocked(context, hasAttemptedRequest)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (permissionState) {

            PermissionState.GRANTED -> {
                // ==================== CAMERA PREVIEW ====================
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder()
                                .build()
                                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                            val imageAnalyzer = ImageAnalysis.Builder()
                                .setTargetResolution(Size(1280, 720))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(
                                        Executors.newSingleThreadExecutor()
                                    ) { imageProxy ->
                                        if (hasScanned) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }

                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null) {
                                            val inputImage = InputImage.fromMediaImage(
                                                mediaImage,
                                                imageProxy.imageInfo.rotationDegrees
                                            )

                                            val scanner = BarcodeScanning.getClient()
                                            scanner.process(inputImage)
                                                .addOnSuccessListener { barcodes ->
                                                    for (barcode in barcodes) {
                                                        if (barcode.valueType == Barcode.TYPE_TEXT) {
                                                            val rawValue = barcode.rawValue ?: continue
                                                            if (QrCodeHelper.isValidPayload(rawValue)) {
                                                                when (val parsed = QrCodeHelper.parseQrPayload(rawValue)) {
                                                                    is QrCodeHelper.ParseOutcome.Success -> {
                                                                        if (!hasScanned) {
                                                                            hasScanned = true
                                                                            onKeyScanned(parsed.key)
                                                                        }
                                                                    }
                                                                    is QrCodeHelper.ParseOutcome.Failure -> {
                                                                        scanError = "Invalid key data in QR code"
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                .addOnFailureListener {
                                                    // Silently continue
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }
                                }

                            try {
                                cameraProvider.unbindAll()
                                camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalyzer
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Scanning overlay
                ScannerOverlay()

                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }

                    Text(
                        text = "Scan Key QR Code",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    IconButton(
                        onClick = {
                            flashEnabled = !flashEnabled
                            camera?.cameraControl?.enableTorch(flashEnabled)
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Flash"
                        )
                    }
                }

                // Bottom instruction
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Black.copy(alpha = 0.7f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (scanError != null) "⚠️ $scanError"
                            else "Point camera at a Cipher Key QR code",
                            color = if (scanError != null) Color(0xFFFF6B6B) else Color.White,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            PermissionState.DENIED -> {
                // ==================== PERMISSION DENIED ====================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "📷", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera permission was denied",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (permissionBlocked) {
                            "Camera access is blocked. Tap below, select Permissions, and enable Camera."
                        } else {
                            "Camera access is needed to scan QR codes.\nGrant permission and try again."
                        },
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        if (permissionBlocked) {
                            // Fallback: the system will never show the request
                            // dialog again — send the user to our app's page in
                            // system settings (Permissions → Camera).
                            openAppSettings(context)
                        } else {
                            // Still askable: ask the system for camera permission again.
                            permissionState = PermissionState.REQUESTING
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }) {
                        Text(if (permissionBlocked) "Open App Settings" else "Grant Permission")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }

            PermissionState.CHECKING,
            PermissionState.REQUESTING -> {
                // ==================== WAITING FOR PERMISSION ====================
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Waiting for camera permission...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Semi-transparent background
        drawRect(color = Color.Black.copy(alpha = 0.6f))

        // Clear center square
        val scanSize = minOf(canvasWidth, canvasHeight) * 0.65f
        val left = (canvasWidth - scanSize) / 2f
        val top = (canvasHeight - scanSize) / 2.5f

        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(scanSize, scanSize),
            cornerRadius = CornerRadius(16f, 16f),
            blendMode = BlendMode.Clear
        )

        // Corner brackets
        val bracketLength = 40f
        val bracketWidth = 4f
        val bracketColor = Color.White
        val cornerRadius = 16f

        // Top-left
        drawLine(bracketColor, Offset(left, top + bracketLength), Offset(left, top + cornerRadius), bracketWidth)
        drawLine(bracketColor, Offset(left + cornerRadius, top), Offset(left + bracketLength, top), bracketWidth)

        // Top-right
        drawLine(bracketColor, Offset(left + scanSize, top + bracketLength), Offset(left + scanSize, top + cornerRadius), bracketWidth)
        drawLine(bracketColor, Offset(left + scanSize - cornerRadius, top), Offset(left + scanSize - bracketLength, top), bracketWidth)

        // Bottom-left
        drawLine(bracketColor, Offset(left, top + scanSize - bracketLength), Offset(left, top + scanSize - cornerRadius), bracketWidth)
        drawLine(bracketColor, Offset(left + cornerRadius, top + scanSize), Offset(left + bracketLength, top + scanSize), bracketWidth)

        // Bottom-right
        drawLine(bracketColor, Offset(left + scanSize, top + scanSize - bracketLength), Offset(left + scanSize, top + scanSize - cornerRadius), bracketWidth)
        drawLine(bracketColor, Offset(left + scanSize - cornerRadius, top + scanSize), Offset(left + scanSize - bracketLength, top + scanSize), bracketWidth)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * True only when a permission request was actually attempted AND the system
 * no longer shows the rationale — i.e. permanently blocked (the request
 * dialog would be auto-denied silently). The hasAttemptedRequest guard keeps
 * "never asked" (rationale == false) from being misread as blocked.
 */
private fun computePermissionBlocked(context: Context, hasAttemptedRequest: Boolean): Boolean {
    if (!hasAttemptedRequest) return false
    val activity = context.findActivity() ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(
        activity, Manifest.permission.CAMERA
    )
}

/**
 * Fallback destination when the permission dialog can no longer be shown.
 * Android does not expose an intent for the per-permission sub-page, so this
 * lands on our app's page in system settings — Permissions → Camera is one
 * tap from there.
 */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // OEM without the settings screen: give written directions instead.
        Toast.makeText(
            context,
            "Open Settings > Apps > Permissions > Camera and enable it",
            Toast.LENGTH_LONG
        ).show()
    }
}