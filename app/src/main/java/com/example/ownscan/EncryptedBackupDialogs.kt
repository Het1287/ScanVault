package com.example.ownscan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// -------------------------------------------------------------
// PASSWORD STRENGTH EVALUATOR
// -------------------------------------------------------------

enum class PasswordStrengthLevel(val label: String, val fraction: Float) {
    EMPTY("", 0f),
    VERY_WEAK("Very Weak (min 8 chars)", 0.2f),
    WEAK("Weak", 0.4f),
    FAIR("Fair", 0.7f),
    STRONG("Strong", 1.0f)
}

fun evaluatePasswordStrength(password: String): PasswordStrengthLevel {
    if (password.isEmpty()) return PasswordStrengthLevel.EMPTY
    if (password.length < 8) return PasswordStrengthLevel.VERY_WEAK

    var score = 0
    if (password.length >= 8) score++
    if (password.length >= 12) score++
    if (password.any { it.isUpperCase() }) score++
    if (password.any { it.isLowerCase() }) score++
    if (password.any { it.isDigit() }) score++
    if (password.any { !it.isLetterOrDigit() }) score++

    return when {
        score <= 2 -> PasswordStrengthLevel.WEAK
        score in 3..4 -> PasswordStrengthLevel.FAIR
        else -> PasswordStrengthLevel.STRONG
    }
}

// -------------------------------------------------------------
// EXPORT ENCRYPTED BACKUP DIALOG
// -------------------------------------------------------------

@Composable
fun ExportEncryptedBackupDialog(
    colors: AppColors,
    docCount: Int,
    onDismiss: () -> Unit,
    onPasswordConfirmed: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val strength = remember(password) { evaluatePasswordStrength(password) }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔐", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Encrypted Backup", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Encrypt $docCount document(s) into an AES-256-GCM protected archive (.zip.enc).",
                    color = colors.textPrimary,
                    fontSize = 13.sp
                )

                // Warning Banner
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.accentAmber.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, colors.accentAmber.copy(alpha = 0.5f))
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Text("⚠️", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Important: Forgotten passwords CANNOT be recovered. ScanVault never stores this password. If you lose it, your backup is permanently inaccessible.",
                            color = colors.accentAmber,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    label = { Text("Backup Password (min 8 chars)", color = colors.textMuted) },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Text(if (isPasswordVisible) "👁️" else "🙈", fontSize = 14.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.cardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Password Strength Indicator
                if (password.isNotEmpty()) {
                    val strengthColor = when (strength) {
                        PasswordStrengthLevel.VERY_WEAK, PasswordStrengthLevel.WEAK -> Color(0xFFE53935) // Red
                        PasswordStrengthLevel.FAIR -> colors.accentAmber
                        PasswordStrengthLevel.STRONG -> Color(0xFF43A047) // Green
                        else -> colors.textMuted
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Strength: ${strength.label}",
                                color = strengthColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(colors.cardBorder, RoundedCornerShape(2.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(strength.fraction)
                                    .fillMaxHeight()
                                    .background(strengthColor, RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        errorMessage = null
                    },
                    label = { Text("Confirm Password", color = colors.textMuted) },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.cardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password.length < 8) {
                        errorMessage = "Password must be at least 8 characters long"
                    } else if (password != confirmPassword) {
                        errorMessage = "Passwords do not match"
                    } else {
                        onPasswordConfirmed(password)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                Text("Select Destination", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}

// -------------------------------------------------------------
// RESTORE ENCRYPTED BACKUP PASSWORD DIALOG
// -------------------------------------------------------------

@Composable
fun RestoreEncryptedBackupPasswordDialog(
    colors: AppColors,
    onDismiss: () -> Unit,
    onPasswordEntered: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔓", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Enter Backup Password", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter the password you used when creating this encrypted .zip.enc file.",
                    color = colors.textMuted,
                    fontSize = 13.sp
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password", color = colors.textMuted) },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Text(if (isPasswordVisible) "👁️" else "🙈", fontSize = 14.sp)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.cardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (password.isNotBlank()) {
                        onPasswordEntered(password)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                enabled = password.isNotBlank()
            ) {
                Text("Decrypt & Restore", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}

// -------------------------------------------------------------
// LOCK ACCESS GATE EXPLANATION DIALOG
// -------------------------------------------------------------

@Composable
fun LockAccessGateDialog(
    colors: AppColors,
    itemName: String,
    isFolder: Boolean = false,
    onDismiss: () -> Unit,
    onConfirmLock: () -> Unit
) {
    AlertDialog(
        containerColor = colors.surface,
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isFolder) "Lock Folder" else "Lock Document",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Lock '$itemName' behind biometric authentication?",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.accent.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.25f))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "ℹ️ Access Gate Notice:",
                            color = colors.accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "• Encrypted at rest: Page images, OCR text, and originals are encrypted with AES-256-GCM using hardware-backed Android Keystore keys.\n" +
                                    "• Biometric authentication: Fingerprint, face, or device credential required to unlock.\n" +
                                    "• In-memory session decryption: Decrypted only on demand during active session; automatically wiped on relock, backgrounding, or inactivity.\n" +
                                    "• Search privacy: Page text is never indexed in Room or FTS in plaintext; search matches only title and tags and never leaks text snippets.\n" +
                                    "• Screen protection: Screenshots and recent apps switcher previews are blocked (FLAG_SECURE) while viewing locked documents.\n" +
                                    "• Secure backups: Locked items are excluded from unencrypted Cloud backups to protect your privacy. They are securely included in password-encrypted local backups.",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmLock,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
            ) {
                Text("Lock", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}
