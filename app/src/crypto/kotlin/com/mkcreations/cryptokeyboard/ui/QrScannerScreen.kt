package com.mkcreations.cryptokeyboard.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
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
    var scanError by remember { mutableStateOf<String?>(null) }
    var flashEnabled by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionState = if (granted) PermissionState.GRANTED else PermissionState.DENIED
    }

    // Auto-request permission on first open if not already granted
    LaunchedEffect(permissionState) {
        if (permissionState == PermissionState.CHECKING) {
            permissionState = PermissionState.REQUESTING
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                        text = "Camera access is needed to scan QR codes.\nGrant permission and try again.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        permissionState = PermissionState.REQUESTING
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) {
                        Text("Grant Permission")
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