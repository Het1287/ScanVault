package com.example.ownscan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BarcodeScanResult(
    val rawValue: String,
    val displayValue: String,
    val formatName: String,
    val valueType: Int,
    val isUrl: Boolean,
    val isUpi: Boolean,
    val upiVpa: String? = null,
    val upiName: String? = null,
    val upiAmount: String? = null,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null
)

object QrBarcodeScannerEngine {

    suspend fun scanBitmap(bitmap: Bitmap): List<BarcodeScanResult> = suspendCancellableCoroutine { cont ->
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        val scanner = BarcodeScanning.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val results = barcodes.mapNotNull { parseBarcode(it) }
                cont.resume(results)
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    private fun parseBarcode(barcode: Barcode): BarcodeScanResult? {
        val raw = barcode.rawValue ?: barcode.displayValue ?: return null
        val formatName = when (barcode.format) {
            Barcode.FORMAT_QR_CODE -> "QR Code"
            Barcode.FORMAT_AZTEC -> "Aztec"
            Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
            Barcode.FORMAT_PDF417 -> "PDF-417"
            Barcode.FORMAT_CODE_128 -> "Code 128"
            Barcode.FORMAT_CODE_39 -> "Code 39"
            Barcode.FORMAT_CODE_93 -> "Code 93"
            Barcode.FORMAT_EAN_13 -> "EAN-13"
            Barcode.FORMAT_EAN_8 -> "EAN-8"
            Barcode.FORMAT_UPC_A -> "UPC-A"
            Barcode.FORMAT_UPC_E -> "UPC-E"
            Barcode.FORMAT_ITF -> "ITF"
            else -> "Barcode"
        }

        val isUpi = raw.startsWith("upi://pay", ignoreCase = true)
        var upiVpa: String? = null
        var upiName: String? = null
        var upiAmount: String? = null
        if (isUpi) {
            try {
                val uri = Uri.parse(raw)
                upiVpa = uri.getQueryParameter("pa")
                upiName = uri.getQueryParameter("pn")
                upiAmount = uri.getQueryParameter("am")
            } catch (_: Exception) {}
        }

        val isUrl = barcode.valueType == Barcode.TYPE_URL || raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)
        val wifi = barcode.wifi

        return BarcodeScanResult(
            rawValue = raw,
            displayValue = barcode.displayValue ?: raw,
            formatName = formatName,
            valueType = barcode.valueType,
            isUrl = isUrl,
            isUpi = isUpi,
            upiVpa = upiVpa,
            upiName = upiName,
            upiAmount = upiAmount,
            wifiSsid = wifi?.ssid,
            wifiPassword = wifi?.password
        )
    }

    fun openUrl(context: Context, url: String) {
        try {
            val validUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(validUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchUpiPayment(context: Context, upiRaw: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(upiRaw)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Pay with UPI"))
        } catch (e: Exception) {
            Toast.makeText(context, "No UPI app found: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Scanned Code", text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share Scanned Content"))
    }
}

@Composable
fun BarcodeResultDialog(
    result: BarcodeScanResult,
    colors: AppColors,
    context: Context,
    onDismiss: () -> Unit
) {
    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📷 Scanned ${result.formatName}", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.cardSubtle,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (result.isUpi) {
                            Text("UPI Payment Request", color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            result.upiName?.let { Text("Payee: $it", color = colors.textPrimary, fontSize = 13.sp) }
                            result.upiVpa?.let { Text("UPI ID: $it", color = colors.textMuted, fontSize = 12.sp) }
                            result.upiAmount?.let { Text("Amount: ₹$it", color = colors.accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                        } else if (result.wifiSsid != null) {
                            Text("Wi-Fi Network", color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("SSID: ${result.wifiSsid}", color = colors.textPrimary, fontSize = 13.sp)
                            result.wifiPassword?.let { Text("Password: $it", color = colors.textMuted, fontSize = 12.sp) }
                        } else {
                            Text(
                                text = result.displayValue,
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (result.isUpi) {
                        Button(
                            onClick = {
                                QrBarcodeScannerEngine.launchUpiPayment(context, result.rawValue)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pay UPI", color = Color.White, fontSize = 12.sp)
                        }
                    } else if (result.isUrl) {
                        Button(
                            onClick = {
                                QrBarcodeScannerEngine.openUrl(context, result.rawValue)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Open Link", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            QrBarcodeScannerEngine.copyToClipboard(context, result.rawValue)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy", color = colors.textPrimary, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            QrBarcodeScannerEngine.shareText(context, result.rawValue)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share", color = colors.textPrimary, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = colors.accent)
            }
        }
    )
}
