package com.example.ownscan

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.*
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import java.util.Arrays
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// -------------------------------------------------------------
// BIOMETRIC & DEVICE CREDENTIAL AUTHENTICATION
// -------------------------------------------------------------

object BiometricAuthManager {

    fun getAuthStatus(context: Context): Int {
        val biometricManager = BiometricManager.from(context)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
        return biometricManager.canAuthenticate(authenticators)
    }

    fun isAuthAvailable(context: Context): Boolean {
        return getAuthStatus(context) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun openSecuritySettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL).apply {
                    putExtra(
                        android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                }
            } else {
                Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e("BiometricAuth", "Failed opening security settings: ${e.message}")
            }
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Item",
        subtitle: String = "Use fingerprint, face, or device PIN/pattern to unlock",
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val status = getAuthStatus(activity)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            val msg = when (status) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "No screen lock or biometric is set up on this device. Please set up a screen lock (PIN, Pattern, Password, or Biometric) in device Settings."
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                    "Biometric hardware is not available on this device."
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                    "Biometric hardware is temporarily unavailable. Please try again."
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                    "Security update required for biometric authentication."
                else ->
                    "Authentication is currently unavailable on this device."
            }
            onError(msg)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptInfoBuilder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            promptInfoBuilder.setDeviceCredentialAllowed(true)
        }

        val promptInfo = promptInfoBuilder.build()

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Authentication failed. Please try again.")
                }
            }
        )

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            android.util.Log.e("BiometricAuth", "Failed to start prompt: ${e.message}")
            onError(e.message ?: "Authentication error")
        }
    }
}

// -------------------------------------------------------------
// FLAG_SECURE SCREEN PROTECTION (SCREENSHOT & RECENTS PREVENTION)
// -------------------------------------------------------------

object SecureFlagHelper {
    fun setSecureFlag(activity: Activity, secure: Boolean) {
        try {
            if (secure) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        } catch (e: Exception) {
            android.util.Log.e("SecureFlagHelper", "Error toggling FLAG_SECURE: ${e.message}")
        }
    }
}

// -------------------------------------------------------------
// PASSWORD-PROTECTED PDF EXPORT (APACHE-2.0 PDFBOX-ANDROID)
// -------------------------------------------------------------

object PasswordPdfExporter {

    private fun generateSecureRandomPassword(length: Int = 32): String {
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_+="
        val random = SecureRandom()
        val sb = java.lang.StringBuilder(length)
        for (i in 0 until length) {
            sb.append(allowedChars[random.nextInt(allowedChars.length)])
        }
        return sb.toString()
    }

    /**
     * Exports the given page image files to a PDF, optionally password-protected using Apache-2.0 PDFBox.
     * Fails closed: if encryption is requested and fails, returns null, deletes all temp files, and never
     * falls back to exporting unprotected content.
     * Uses 256-bit AES encryption with distinct user and owner passwords.
     */
    suspend fun exportPdf(
        context: Context,
        baseName: String,
        imageFiles: List<File>,
        password: String? = null,
        ownerPassword: String? = null,
        watermarkText: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        if (imageFiles.isEmpty()) return@withContext null

        val generatedFile = generatePdfFile(
            context, baseName, imageFiles, password, ownerPassword, watermarkText
        ) ?: return@withContext null
        try {
            savePdfToDownloads(context, generatedFile, "$baseName.pdf")
        } finally {
            if (generatedFile.exists()) generatedFile.delete()
        }
    }

    suspend fun generatePdfFile(
        context: Context,
        baseName: String,
        imageFiles: List<File>,
        password: String? = null,
        ownerPassword: String? = null,
        watermarkText: String? = null
    ): File? = withContext(Dispatchers.IO) {
        if (imageFiles.isEmpty()) return@withContext null

        var protectedPdfFile: File? = null
        val tempRawPdf: File = run {
            val nativePdf = PdfDocument()
            val candidateRaw = File(context.cacheDir, "raw_${System.currentTimeMillis()}_${SecureRandom().nextInt(100000)}.pdf")
            try {
                imageFiles.forEachIndexed { index, file ->
                    val rawBitmap = decodeFileCapped(file.absolutePath) ?: return@forEachIndexed
                    val bitmap = if (!watermarkText.isNullOrBlank()) {
                        val procBmp = MaskAndWatermarkEngine.processImage(context, rawBitmap, watermarkText)
                        if (procBmp != rawBitmap) rawBitmap.recycle()
                        procBmp
                    } else {
                        rawBitmap
                    }

                    try {
                        val a4Width = 595
                        val a4Height = 842
                        val isLandscape = bitmap.width > bitmap.height
                        val pageWidth = if (isLandscape) a4Height else a4Width
                        val pageHeight = if (isLandscape) a4Width else a4Height

                        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                        val page = nativePdf.startPage(pageInfo)

                        val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                        val pageAspect = pageWidth.toFloat() / pageHeight.toFloat()
                        val (destW, destH) = if (bmpAspect > pageAspect) {
                            pageWidth.toFloat() to (pageWidth.toFloat() / bmpAspect)
                        } else {
                            (pageHeight.toFloat() * bmpAspect) to pageHeight.toFloat()
                        }
                        val left = (pageWidth - destW) / 2f
                        val top = (pageHeight - destH) / 2f

                        page.canvas.drawColor(android.graphics.Color.WHITE)
                        page.canvas.drawBitmap(bitmap, null, android.graphics.RectF(left, top, left + destW, top + destH), null)
                        nativePdf.finishPage(page)
                    } finally {
                        bitmap.recycle()
                    }
                }

                FileOutputStream(candidateRaw).use { out ->
                    nativePdf.writeTo(out)
                }
                candidateRaw
            } catch (e: Exception) {
                android.util.Log.e("PasswordPdfExporter", "Error generating raw PDF: ${e.message}", e)
                candidateRaw.delete()
                return@withContext null
            } finally {
                nativePdf.close()
            }
        }

        val hasPassword = !password.isNullOrBlank()
        if (hasPassword) {
            val candidateProtected = File(context.cacheDir, "protected_${System.currentTimeMillis()}_${SecureRandom().nextInt(100000)}.pdf")
            protectedPdfFile = candidateProtected
            try {
                PDFBoxResourceLoader.init(context)
                val document = PDDocument.load(tempRawPdf)
                try {
                    val actualOwnerPassword = if (!ownerPassword.isNullOrBlank()) ownerPassword else generateSecureRandomPassword()
                    val accessPermission = AccessPermission()
                    val protectionPolicy = StandardProtectionPolicy(actualOwnerPassword, password, accessPermission).apply {
                        encryptionKeyLength = 256 // 256-bit AES supported by pdfbox-android
                        permissions = accessPermission
                    }
                    document.protect(protectionPolicy)
                    document.save(candidateProtected)
                } finally {
                    document.close()
                }
                tempRawPdf.delete()
                candidateProtected
            } catch (e: Exception) {
                android.util.Log.e("PasswordPdfExporter", "Failed to encrypt PDF: ${e.message}", e)
                tempRawPdf.delete()
                candidateProtected.delete()
                null
            }
        } else {
            tempRawPdf
        }
    }

    fun savePdfToDownloads(context: Context, pdfFile: File, fileName: String): Uri? {
        val resolver = context.contentResolver
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ScanVault")
                }
                val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (targetUri != null) {
                    resolver.openOutputStream(targetUri)?.use { out ->
                        FileInputStream(pdfFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    return targetUri
                }
            } else {
                val targetDir = getExportTargetDir(Environment.DIRECTORY_DOWNLOADS)
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)
                pdfFile.copyTo(targetFile, overwrite = true)
                return Uri.fromFile(targetFile)
            }
        } catch (e: Exception) {
            android.util.Log.e("PasswordPdfExporter", "Error saving final PDF to storage: ${e.message}", e)
        }
        return null
    }
}

// -------------------------------------------------------------
// LOCAL ENCRYPTED BACKUP & RESTORE (AES-256-GCM + PBKDF2)
// -------------------------------------------------------------

sealed class EncryptedRestoreResult {
    data class Success(val restoredCount: Int, val skippedCount: Int, val badEntriesCount: Int) : EncryptedRestoreResult()
    object InvalidFileFormat : EncryptedRestoreResult()
    object IncorrectPasswordOrCorrupted : EncryptedRestoreResult()
    data class Error(val message: String) : EncryptedRestoreResult()
}

data class DocManifestEntry(
    val folderName: String,
    val displayName: String,
    val parentFolder: String? = null,
    val meta: DocMeta? = null
)

data class BackupManifest(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val documents: List<DocManifestEntry> = emptyList()
) {
    fun toJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(this)

    companion object {
        fun fromJson(jsonStr: String): BackupManifest {
            return try {
                Gson().fromJson(jsonStr, BackupManifest::class.java) ?: BackupManifest()
            } catch (_: Exception) {
                BackupManifest()
            }
        }
    }
}

object LocalEncryptedBackupManager {

    private val MAGIC_BYTES = "OWNSCANE".toByteArray(Charsets.US_ASCII) // 8 bytes magic header
    val BACKUP_DEK_MAGIC = "OWNSCANB".toByteArray(Charsets.US_ASCII) // 8 bytes magic header for DEK re-wrapped under backup password
    const val FORMAT_VERSION: Byte = 3
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12 // Standard GCM base IV length
    private const val GCM_TAG_LENGTH = 128 // bits
    const val PBKDF2_ITERATIONS = 210000 // OWASP-recommended >= 210,000 for PBKDF2-HMAC-SHA256
    private const val KEY_LENGTH = 256 // bits
    private const val CHUNK_SIZE = 1024 * 1024 // 1 MB chunked authenticated streaming

    fun zeroOut(chars: CharArray) {
        chars.fill('\u0000')
    }

    fun zeroOut(bytes: ByteArray) {
        bytes.fill(0)
    }

    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) {
                android.util.Log.e(tag, msg, tr)
            } else {
                android.util.Log.e(tag, msg)
            }
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg ${tr?.message ?: ""}")
        }
    }

    /**
     * Derives an AES-256 key from a user password and salt using PBKDF2WithHmacSHA256.
     * Zeroes out sensitive intermediate byte arrays and clears the PBEKeySpec.
     */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val secretBytes = factory.generateSecret(spec).encoded
            try {
                return SecretKeySpec(secretBytes, "AES")
            } finally {
                zeroOut(secretBytes)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun readExact(stream: InputStream, buffer: ByteArray): Boolean {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val count = stream.read(buffer, totalRead, buffer.size - totalRead)
            if (count == -1) return false
            totalRead += count
        }
        return true
    }

    /**
     * Derives a unique 12-byte IV for each chunk from baseIv and chunk counter.
     */
    fun deriveChunkIv(baseIv: ByteArray, chunkIndex: Int): ByteArray {
        val chunkIv = baseIv.copyOf()
        val counterBuf = ByteBuffer.allocate(4).putInt(chunkIndex).array()
        for (i in 0 until 4) {
            chunkIv[8 + i] = (chunkIv[8 + i].toInt() xor counterBuf[i].toInt()).toByte()
        }
        return chunkIv
    }

    /**
     * Builds AAD byte array from header: magic (8) + version (1) + salt (16) + iterations (4)
     */
    private fun buildAad(version: Byte, salt: ByteArray, iterations: Int): ByteArray {
        val bb = ByteBuffer.allocate(MAGIC_BYTES.size + 1 + salt.size + 4)
        bb.put(MAGIC_BYTES)
        bb.put(version)
        bb.put(salt)
        bb.putInt(iterations)
        return bb.array()
    }

    /**
     * Authenticates isFinal flag and chunkIndex within each chunk's AAD for format v3+.
     */
    fun buildChunkAad(headerAad: ByteArray, isFinal: Boolean, chunkIndex: Int): ByteArray {
        val bb = ByteBuffer.allocate(headerAad.size + 1 + 4)
        bb.put(headerAad)
        bb.put(if (isFinal) 1.toByte() else 0.toByte())
        bb.putInt(chunkIndex)
        return bb.array()
    }

    /**
     * Chunked authenticated GCM output stream.
     * Encrypts plaintext in 1 MB chunks with unique IVs, each with its own GCM tag.
     * Format per chunk: [isFinal: 1 byte][chunkLength: 4 bytes big-endian][ciphertext + 16-byte tag]
     */
    class ChunkedGcmOutputStream(
        private val out: OutputStream,
        private val keySpec: SecretKey,
        private val baseIv: ByteArray,
        private val aad: ByteArray,
        private val chunkSize: Int = CHUNK_SIZE,
        private val formatVersion: Byte = FORMAT_VERSION
    ) : OutputStream() {
        private val buffer = ByteArray(chunkSize)
        private var bufferPos = 0
        private var chunkIndex = 0
        private var closed = false

        override fun write(b: Int) {
            buffer[bufferPos++] = b.toByte()
            if (bufferPos == chunkSize) {
                flushChunk(isFinal = false)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var remaining = len
            var offset = off
            while (remaining > 0) {
                val toCopy = minOf(remaining, chunkSize - bufferPos)
                System.arraycopy(b, offset, buffer, bufferPos, toCopy)
                bufferPos += toCopy
                offset += toCopy
                remaining -= toCopy
                if (bufferPos == chunkSize) {
                    flushChunk(isFinal = false)
                }
            }
        }

        private fun flushChunk(isFinal: Boolean) {
            val chunkIv = deriveChunkIv(baseIv, chunkIndex)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, chunkIv))
            if (formatVersion >= 3) {
                cipher.updateAAD(buildChunkAad(aad, isFinal, chunkIndex))
            } else if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            val encrypted = cipher.doFinal(buffer, 0, bufferPos)

            out.write(if (isFinal) 1 else 0)
            val lenBuf = ByteBuffer.allocate(4).putInt(encrypted.size).array()
            out.write(lenBuf)
            out.write(encrypted)
            out.flush()

            bufferPos = 0
            chunkIndex++
        }

        override fun flush() {
            // Intentionally do not flush partial non-final chunk
        }

        override fun close() {
            if (!closed) {
                flushChunk(isFinal = true)
                out.flush()
                closed = true
            }
        }
    }

    /**
     * Chunked authenticated GCM input stream.
     * Reads and authenticates chunks one by one in 1 MB increments to prevent memory blow-up.
     * Fails if any tag fails, if header AAD is tampered, or if stream truncates before final chunk.
     */
    class ChunkedGcmInputStream(
        private val input: InputStream,
        private val keySpec: SecretKey,
        private val baseIv: ByteArray,
        private val aad: ByteArray,
        private val formatVersion: Byte = FORMAT_VERSION
    ) : InputStream() {
        private var chunkIndex = 0
        private var currentChunk: ByteArray? = null
        private var chunkPos = 0
        private var reachedFinal = false
        private var isEof = false

        private fun loadNextChunk(): Boolean {
            if (reachedFinal || isEof) return false

            val flag = input.read()
            if (flag == -1) {
                throw IOException("Premature EOF: archive is truncated (missing final chunk)")
            }
            val isFinal = (flag == 1)

            val lenBytes = ByteArray(4)
            if (!readExact(input, lenBytes)) {
                throw IOException("Premature EOF reading chunk length: archive is truncated")
            }
            val chunkLen = ByteBuffer.wrap(lenBytes).int
            if (chunkLen < 16 || chunkLen > CHUNK_SIZE + 64) {
                throw IOException("Invalid chunk length in archive: $chunkLen")
            }

            val encrypted = ByteArray(chunkLen)
            if (!readExact(input, encrypted)) {
                throw IOException("Premature EOF reading chunk data: archive is truncated")
            }

            val chunkIv = deriveChunkIv(baseIv, chunkIndex)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, chunkIv))
            if (formatVersion >= 3) {
                cipher.updateAAD(buildChunkAad(aad, isFinal, chunkIndex))
            } else if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            val decrypted = cipher.doFinal(encrypted)

            currentChunk = decrypted
            chunkPos = 0
            chunkIndex++
            if (isFinal) {
                reachedFinal = true
            }
            return true
        }

        override fun read(): Int {
            while (currentChunk == null || chunkPos >= currentChunk!!.size) {
                if (reachedFinal) {
                    isEof = true
                    return -1
                }
                if (!loadNextChunk()) {
                    isEof = true
                    return -1
                }
            }
            return currentChunk!![chunkPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (currentChunk == null || chunkPos >= currentChunk!!.size) {
                if (reachedFinal) {
                    isEof = true
                    return -1
                }
                if (!loadNextChunk()) {
                    isEof = true
                    return -1
                }
            }
            val available = currentChunk!!.size - chunkPos
            val toRead = minOf(len, available)
            System.arraycopy(currentChunk!!, chunkPos, b, off, toRead)
            chunkPos += toRead
            return toRead
        }

        override fun close() {
            input.close()
        }
    }

    private fun zipFolderRecursively(
        currentDir: File,
        baseZipPath: String,
        zos: ZipOutputStream,
        backupKeySpec: SecretKey? = null
    ) {
        val files = currentDir.listFiles() ?: return
        for (file in files) {
            val entryPath = if (baseZipPath.isEmpty()) file.name else "$baseZipPath/${file.name}"
            if (file.isDirectory) {
                zipFolderRecursively(file, entryPath, zos, backupKeySpec)
            } else if (file.isFile) {
                if (file.name == ".dek.enc" && backupKeySpec != null) {
                    try {
                        val rawDek = LockedDocCryptoManager.getOrDecryptDek(currentDir)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        val iv = ByteArray(12)
                        SecureRandom().nextBytes(iv)
                        cipher.init(Cipher.ENCRYPT_MODE, backupKeySpec, GCMParameterSpec(128, iv))
                        val encDek = cipher.doFinal(rawDek.encoded)

                        val entry = ZipEntry(entryPath)
                        entry.time = file.lastModified()
                        zos.putNextEntry(entry)
                        zos.write(BACKUP_DEK_MAGIC)
                        zos.write(iv)
                        val lenBuf = ByteBuffer.allocate(4).putInt(encDek.size).array()
                        zos.write(lenBuf)
                        zos.write(encDek)
                        zos.closeEntry()
                        continue
                    } catch (e: Exception) {
                        logE("EncryptedBackup", "Failed re-wrapping DEK for backup in ${currentDir.name}: ${e.message}", e)
                        throw IllegalStateException("Failed to re-wrap DEK for backup in document '${currentDir.name}': ${e.message}", e)
                    }
                }
                val entry = ZipEntry(entryPath)
                entry.time = file.lastModified()
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    /**
     * Streams ZipOutputStream directly into the chunked encrypting stream.
     * No plaintext copy of user documents touches disk outside final document folders.
     */
    suspend fun createEncryptedBackupStream(
        docFolders: List<File>,
        passwordChars: CharArray,
        destinationOut: OutputStream,
        manifestEntries: List<DocManifestEntry>
    ): Boolean = withContext(Dispatchers.IO) {
        if (passwordChars.size < 8 || docFolders.isEmpty()) return@withContext false

        try {
            val secureRandom = SecureRandom()
            val salt = ByteArray(SALT_LENGTH)
            secureRandom.nextBytes(salt)

            val baseIv = ByteArray(IV_LENGTH)
            secureRandom.nextBytes(baseIv)

            val iterations = PBKDF2_ITERATIONS
            val keySpec = deriveKey(passwordChars, salt, iterations)
            val aad = buildAad(FORMAT_VERSION, salt, iterations)

            BufferedOutputStream(destinationOut).use { bos ->
                // Write header: [MAGIC (8)] [VERSION (1)] [SALT (16)] [BASE_IV (12)] [ITERATIONS (4)]
                bos.write(MAGIC_BYTES)
                bos.write(FORMAT_VERSION.toInt())
                bos.write(salt)
                bos.write(baseIv)
                val iterBuf = ByteBuffer.allocate(4).putInt(iterations).array()
                bos.write(iterBuf)
                bos.flush()

                // Stream ZipOutputStream directly through chunked AES-256-GCM
                val chunkedOut = ChunkedGcmOutputStream(bos, keySpec, baseIv, aad, formatVersion = FORMAT_VERSION)
                ZipOutputStream(BufferedOutputStream(chunkedOut)).use { zos ->
                    // 1. Write manifest.json first
                    val manifest = BackupManifest(documents = manifestEntries)
                    val manifestBytes = manifest.toJson().toByteArray(Charsets.UTF_8)
                    val manifestEntry = ZipEntry("manifest.json")
                    manifestEntry.time = System.currentTimeMillis()
                    zos.putNextEntry(manifestEntry)
                    zos.write(manifestBytes)
                    zos.closeEntry()

                    // 2. Recursively zip all document folders including .originals, ocr.txt, and meta.json
                    docFolders.forEach { docDir ->
                        if (docDir.exists() && docDir.isDirectory) {
                            zipFolderRecursively(docDir, docDir.name, zos, keySpec)
                        }
                    }
                }
            }

            true
        } catch (e: Exception) {
            logE("EncryptedBackup", "Failed to create encrypted backup stream: ${e.message}", e)
            false
        }
    }

    /**
     * Creates an encrypted backup (.zip.enc) saved to the user's chosen SAF Uri.
     */
    suspend fun createEncryptedBackup(
        context: Context,
        docFolders: List<File>,
        password: String,
        destinationUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        if (password.length < 8 || docFolders.isEmpty()) return@withContext false

        val passwordChars = password.toCharArray()
        try {
            val userFoldersDir = getUserFoldersDir(context)
            val manifestEntries = docFolders.map { docDir ->
                val meta = DocMeta.readFromDir(docDir)
                val parentFile = docDir.parentFile
                val parentFolder = if (parentFile != null && parentFile.parentFile == userFoldersDir) {
                    parentFile.name
                } else {
                    null
                }
                val displayName = docDir.name.substringAfter("__", docDir.name)
                DocManifestEntry(
                    folderName = docDir.name,
                    displayName = displayName,
                    parentFolder = parentFolder,
                    meta = meta
                )
            }

            val outStream = context.contentResolver.openOutputStream(destinationUri) ?: return@withContext false
            outStream.use { rawOut ->
                createEncryptedBackupStream(docFolders, passwordChars, rawOut, manifestEntries)
            }
        } finally {
            zeroOut(passwordChars)
        }
    }

    /**
     * Streams ZipInputStream directly from the decrypting stream into destination folders.
     * Resolves conflicts once per document folder, supports apply-to-all, and writes copies atomically.
     */
    suspend fun restoreEncryptedBackupStream(
        sourceIn: InputStream,
        passwordChars: CharArray,
        baseDocsDir: File,
        userFoldersDir: File,
        conflictResolver: suspend (docName: String, localTime: Long, backupTime: Long) -> ConflictResolution
    ): EncryptedRestoreResult = withContext(Dispatchers.IO) {
        if (passwordChars.isEmpty()) {
            return@withContext EncryptedRestoreResult.IncorrectPasswordOrCorrupted
        }

        try {
            val bis = BufferedInputStream(sourceIn)

            // 1. Read and validate magic header
            val magic = ByteArray(MAGIC_BYTES.size)
            if (!readExact(bis, magic) || !magic.contentEquals(MAGIC_BYTES)) {
                return@withContext EncryptedRestoreResult.InvalidFileFormat
            }

            val version = bis.read()
            if (version != 1 && version != 2 && version != FORMAT_VERSION.toInt()) {
                return@withContext EncryptedRestoreResult.InvalidFileFormat
            }

            val salt = ByteArray(SALT_LENGTH)
            val baseIv = ByteArray(IV_LENGTH)
            if (!readExact(bis, salt) || !readExact(bis, baseIv)) {
                return@withContext EncryptedRestoreResult.InvalidFileFormat
            }

            val (chunkedIn: InputStream, effectiveKey: SecretKey) = if (version == 1) {
                // Version 1 backward compatibility: PBKDF2 65536 iterations, unchunked streaming
                val keySpec = deriveKey(passwordChars, salt, 65536)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_LENGTH, baseIv))
                Pair(javax.crypto.CipherInputStream(bis, cipher), keySpec)
            } else {
                // Version 2 & 3: PBKDF2 iterations stored in header, chunked authenticated streaming
                val iterBuf = ByteArray(4)
                if (!readExact(bis, iterBuf)) {
                    return@withContext EncryptedRestoreResult.InvalidFileFormat
                }
                val iterations = ByteBuffer.wrap(iterBuf).int
                if (iterations < 10000 || iterations > 2_000_000) {
                    return@withContext EncryptedRestoreResult.InvalidFileFormat
                }
                val keySpec = deriveKey(passwordChars, salt, iterations)
                val aad = buildAad(version.toByte(), salt, iterations)
                Pair(ChunkedGcmInputStream(bis, keySpec, baseIv, aad, formatVersion = version.toByte()), keySpec)
            }

            val maxTotalRestoreBytes = 2L * 1024 * 1024 * 1024 // 2 GB
            val maxEntryBytes = 200L * 1024 * 1024 // 200 MB
            val maxTotalEntries = 20000

            var totalEntriesCount = 0
            var totalUncompressedBytes = 0L

            var restoredCount = 0
            var skippedCount = 0
            var badEntriesCount = 0

            var manifest: BackupManifest? = null
            val manifestMap = mutableMapOf<String, DocManifestEntry>()

            val folderDecisions = mutableMapOf<String, ConflictResolution>()
            val folderStagingDirs = mutableMapOf<String, File>()

            try {
                ZipInputStream(BufferedInputStream(chunkedIn)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        totalEntriesCount++
                        if (totalEntriesCount > maxTotalEntries) {
                            badEntriesCount++
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        if (entryName == "manifest.json") {
                            val manifestText = zis.reader(Charsets.UTF_8).readText()
                            manifest = BackupManifest.fromJson(manifestText)
                            manifest?.documents?.forEach { manifestMap[it.folderName] = it }
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        // ZIP-SLIP PREVENTION
                        if (entryName.contains("../") || entryName.contains("..\\") || entryName.startsWith("/") || entryName.startsWith("\\")) {
                            logE("EncryptedRestore", "Zip-slip detected and blocked: $entryName")
                            badEntriesCount++
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        val slashIndex = entryName.indexOf('/')
                        val docFolderName = if (slashIndex != -1) entryName.substring(0, slashIndex) else entryName
                        val relativePathInDoc = if (slashIndex != -1) entryName.substring(slashIndex + 1) else ""

                        if (docFolderName.isBlank() || docFolderName.startsWith(".")) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        val manifestEntry = manifestMap[docFolderName]
                        val rawParent = manifestEntry?.parentFolder
                        val sanitizedParent = if (!rawParent.isNullOrBlank()) sanitizeName(rawParent) else null
                        val targetParentDir = if (!sanitizedParent.isNullOrBlank() && sanitizedParent != "." && sanitizedParent != "..") {
                            val uFolder = File(userFoldersDir, sanitizedParent)
                            if (!uFolder.exists()) uFolder.mkdirs()
                            uFolder
                        } else {
                            if (!baseDocsDir.exists()) baseDocsDir.mkdirs()
                            baseDocsDir
                        }

                        val existingDocDir = File(targetParentDir, docFolderName)

                        if (!folderDecisions.containsKey(docFolderName)) {
                            if (existingDocDir.exists() && existingDocDir.isDirectory && existingDocDir.list()?.isNotEmpty() == true) {
                                val localModified = existingDocDir.lastModified()
                                val backupModified = if (entry.time > 0) entry.time else (manifestEntry?.meta?.trashedDate ?: System.currentTimeMillis())
                                val docDisplayName = manifestEntry?.displayName ?: docFolderName.substringAfter("__", docFolderName)

                                val resolution = conflictResolver(docDisplayName, localModified, backupModified)
                                when (resolution) {
                                    ConflictResolution.SKIP -> {
                                        folderDecisions[docFolderName] = ConflictResolution.SKIP
                                        skippedCount++
                                    }
                                    ConflictResolution.NEWEST_WINS -> {
                                        if (localModified >= backupModified) {
                                            folderDecisions[docFolderName] = ConflictResolution.SKIP
                                            skippedCount++
                                        } else {
                                            folderDecisions[docFolderName] = ConflictResolution.NEWEST_WINS
                                            val stagingDir = File(targetParentDir, ".temp_staging_${System.currentTimeMillis()}_$docFolderName")
                                            stagingDir.mkdirs()
                                            folderStagingDirs[docFolderName] = stagingDir
                                        }
                                    }
                                    ConflictResolution.KEEP_BOTH -> {
                                        folderDecisions[docFolderName] = ConflictResolution.KEEP_BOTH
                                        val stagingDir = File(targetParentDir, ".temp_staging_${System.currentTimeMillis()}_$docFolderName")
                                        stagingDir.mkdirs()
                                        folderStagingDirs[docFolderName] = stagingDir
                                    }
                                }
                            } else {
                                folderDecisions[docFolderName] = ConflictResolution.NEWEST_WINS
                                val stagingDir = File(targetParentDir, ".temp_staging_${System.currentTimeMillis()}_$docFolderName")
                                stagingDir.mkdirs()
                                folderStagingDirs[docFolderName] = stagingDir
                            }
                        }

                        val decision = folderDecisions[docFolderName]
                        if (decision == ConflictResolution.SKIP) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        val stagingDir = folderStagingDirs[docFolderName]
                        if (stagingDir == null) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        if (relativePathInDoc.isNotEmpty()) {
                            val destFile = File(stagingDir, relativePathInDoc)
                            val stagingCanonical = stagingDir.canonicalPath + File.separator
                            val destCanonical = destFile.canonicalPath

                            if (!destCanonical.startsWith(stagingCanonical) && destCanonical != stagingDir.canonicalPath) {
                                logE("EncryptedRestore", "Zip-slip detected and blocked: $entryName")
                                badEntriesCount++
                                zis.closeEntry()
                                entry = zis.nextEntry
                                continue
                            }

                            if (entry.isDirectory) {
                                destFile.mkdirs()
                            } else if (destFile.name == ".dek.enc") {
                                val maxDekBytes = 4096
                                val baos = ByteArrayOutputStream()
                                val buf = ByteArray(1024)
                                var readBytesTotal = 0
                                var exceeded = false
                                var n: Int
                                while (zis.read(buf).also { n = it } != -1) {
                                    readBytesTotal += n
                                    if (readBytesTotal > maxDekBytes) {
                                        exceeded = true
                                        break
                                    }
                                    baos.write(buf, 0, n)
                                }
                                if (exceeded) {
                                    badEntriesCount++
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                    continue
                                }
                                val entryBytes = baos.toByteArray()
                                if (entryBytes.size > maxEntryBytes || totalUncompressedBytes + entryBytes.size > maxTotalRestoreBytes) {
                                    badEntriesCount++
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                    continue
                                }
                                totalUncompressedBytes += entryBytes.size
                                if (entryBytes.size >= BACKUP_DEK_MAGIC.size + 12 + 4 &&
                                    entryBytes.copyOfRange(0, BACKUP_DEK_MAGIC.size).contentEquals(BACKUP_DEK_MAGIC)
                                ) {
                                    try {
                                        val offset = BACKUP_DEK_MAGIC.size
                                        val iv = entryBytes.copyOfRange(offset, offset + 12)
                                        val len = ByteBuffer.wrap(entryBytes, offset + 12, 4).int
                                        val encDek = entryBytes.copyOfRange(offset + 16, offset + 16 + len)

                                        val backupCipher = Cipher.getInstance("AES/GCM/NoPadding")
                                        backupCipher.init(Cipher.DECRYPT_MODE, effectiveKey, GCMParameterSpec(128, iv))
                                        val rawDek = backupCipher.doFinal(encDek)

                                        val masterKey = LockedDocCryptoManager.getMasterKey()
                                        val masterCipher = Cipher.getInstance("AES/GCM/NoPadding")
                                        val (newIv, newEncDek) = try {
                                            masterCipher.init(Cipher.ENCRYPT_MODE, masterKey)
                                            val enc = masterCipher.doFinal(rawDek)
                                            val genIv = masterCipher.iv ?: throw IllegalStateException("Keystore cipher did not return IV")
                                            Pair(genIv, enc)
                                        } catch (initEx: Exception) {
                                            val randomIv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                                            masterCipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(128, randomIv))
                                            val enc = masterCipher.doFinal(rawDek)
                                            Pair(randomIv, enc)
                                        }

                                        destFile.parentFile?.mkdirs()
                                        FileOutputStream(destFile).use { fos ->
                                            fos.write(LockedDocCryptoManager.FILE_MAGIC)
                                            fos.write(newIv)
                                            fos.write(ByteBuffer.allocate(4).putInt(newEncDek.size).array())
                                            fos.write(newEncDek)
                                        }
                                        Arrays.fill(rawDek, 0.toByte())
                                    } catch (e: Exception) {
                                        logE("EncryptedRestore", "Failed re-wrapping DEK to local keystore: ${e.message}", e)
                                        throw IOException("Failed re-wrapping locked document key under device Keystore: ${e.message}", e)
                                    }
                                } else {
                                    destFile.parentFile?.mkdirs()
                                    destFile.writeBytes(entryBytes)
                                }
                                if (entry.time > 0) {
                                    destFile.setLastModified(entry.time)
                                }
                            } else {
                                destFile.parentFile?.mkdirs()
                                var entryBytesRead = 0L
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var entryExceededCap = false
                                FileOutputStream(destFile).use { fos ->
                                    while (zis.read(buffer).also { bytesRead = it } != -1) {
                                        entryBytesRead += bytesRead
                                        totalUncompressedBytes += bytesRead
                                        if (entryBytesRead > maxEntryBytes || totalUncompressedBytes > maxTotalRestoreBytes) {
                                            entryExceededCap = true
                                            break
                                        }
                                        fos.write(buffer, 0, bytesRead)
                                    }
                                }
                                if (entryExceededCap) {
                                    badEntriesCount++
                                    destFile.delete()
                                    while (zis.read(buffer) != -1) {}
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                    continue
                                }
                                if (entry.time > 0) {
                                    destFile.setLastModified(entry.time)
                                }
                            }
                        }

                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                // Drain entire decrypted/authenticated stream to detect truncation
                val drainBuf = ByteArray(8192)
                while (chunkedIn.read(drainBuf) != -1) {
                    // Drain through to final chunk so ChunkedGcmInputStream verifies integrity and EOF
                }

                // Finalize staging atomically (temp folder, then rename)
                for ((docFolderName, stagingDir) in folderStagingDirs) {
                    val decision = folderDecisions[docFolderName]
                    val targetParentDir = stagingDir.parentFile ?: baseDocsDir
                    val existingDocDir = File(targetParentDir, docFolderName)
                    val manifestEntry = manifestMap[docFolderName]

                    when (decision) {
                        ConflictResolution.KEEP_BOTH -> {
                            val finalTarget = if (existingDocDir.exists()) {
                                val newTs = System.currentTimeMillis()
                                val baseDisplayName = manifestEntry?.displayName ?: docFolderName.substringAfter("__", docFolderName)
                                val restoredDisplayName = "$baseDisplayName (restored)"
                                val newFolderName = "${newTs}__${sanitizeName(restoredDisplayName).ifBlank { "restored" }}"
                                File(targetParentDir, newFolderName)
                            } else {
                                existingDocDir
                            }
                            if (stagingDir.renameTo(finalTarget)) {
                                restoredCount++
                            } else {
                                stagingDir.copyRecursively(finalTarget, overwrite = true)
                                stagingDir.deleteRecursively()
                                restoredCount++
                            }
                        }
                        ConflictResolution.NEWEST_WINS -> {
                            val backupExistingDir = if (existingDocDir.exists()) {
                                val tempBackup = File(targetParentDir, ".backup_old_${System.currentTimeMillis()}_$docFolderName")
                                if (existingDocDir.renameTo(tempBackup)) {
                                    tempBackup
                                } else {
                                    null
                                }
                            } else null

                            val success = if (stagingDir.renameTo(existingDocDir)) {
                                true
                            } else {
                                val copied = stagingDir.copyRecursively(existingDocDir, overwrite = true)
                                stagingDir.deleteRecursively()
                                copied
                            }

                            if (success) {
                                backupExistingDir?.deleteRecursively()
                                restoredCount++
                            } else {
                                if (backupExistingDir != null && backupExistingDir.exists()) {
                                    existingDocDir.deleteRecursively()
                                    backupExistingDir.renameTo(existingDocDir)
                                }
                                badEntriesCount++
                            }
                        }
                        else -> {
                            stagingDir.deleteRecursively()
                        }
                    }
                }

                EncryptedRestoreResult.Success(
                    restoredCount = restoredCount,
                    skippedCount = skippedCount,
                    badEntriesCount = badEntriesCount
                )
            } finally {
                folderStagingDirs.values.forEach { if (it.exists()) it.deleteRecursively() }
            }

        } catch (e: Exception) {
            var isAuthOrPadding = false
            var curr: Throwable? = e
            while (curr != null) {
                if (curr is AEADBadTagException || curr is BadPaddingException) {
                    isAuthOrPadding = true
                    break
                }
                curr = curr.cause
            }
            if (isAuthOrPadding) {
                EncryptedRestoreResult.IncorrectPasswordOrCorrupted
            } else {
                EncryptedRestoreResult.Error("Decryption error: ${e.message}")
            }
        }
    }

    /**
     * Restores documents from an encrypted backup (.zip.enc).
     * Rebuilds Room & FTS afterwards and purges memory thumbnail cache.
     */
    suspend fun restoreEncryptedBackup(
        context: Context,
        sourceUri: Uri,
        password: String,
        conflictResolver: suspend (docName: String, localTime: Long, backupTime: Long) -> ConflictResolution
    ): EncryptedRestoreResult = withContext(Dispatchers.IO) {
        val passwordChars = password.toCharArray()
        try {
            val baseDocsDir = getPrivateDocsDir(context)
            val userFoldersDir = getUserFoldersDir(context)
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext EncryptedRestoreResult.Error("Could not open input file")

            val result = inputStream.use { rawIn ->
                restoreEncryptedBackupStream(
                    sourceIn = rawIn,
                    passwordChars = passwordChars,
                    baseDocsDir = baseDocsDir,
                    userFoldersDir = userFoldersDir,
                    conflictResolver = conflictResolver
                )
            }

            if (result is EncryptedRestoreResult.Success && result.restoredCount > 0) {
                DocumentRepository.rebuildRoomFromMetaJson(context)
                thumbnailMemoryCache.evictAll()
            }

            result
        } finally {
            zeroOut(passwordChars)
        }
    }
}

// -------------------------------------------------------------
// REAL DOCUMENT LOCKING CRYPTO MANAGER (ANDROID KEYSTORE AES-256-GCM)
// -------------------------------------------------------------

/**
 * Thrown when a document's Data Encryption Key (.dek.enc) cannot be decrypted
 * due to corruption, master key mismatch, or header tampering.
 */
class DekDecryptionException(
    message: String,
    cause: Throwable? = null
) : GeneralSecurityException(message, cause)

object LockedDocCryptoManager {

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    const val KEY_ALIAS = "scanvault_locked_docs_master_key"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12
    val FILE_MAGIC = "OWNSCANL".toByteArray(Charsets.US_ASCII) // 8 bytes header for locked doc files
    private const val CHUNK_SIZE = 1024 * 1024 // 1 MB chunked streaming

    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) {
                android.util.Log.e(tag, msg, tr)
            } else {
                android.util.Log.e(tag, msg)
            }
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg ${tr?.message ?: ""}")
        }
    }

    /**
     * Test hook: allows injecting a software AES key for JVM unit tests where AndroidKeyStore is unavailable.
     */
    @Volatile
    var testSecretKey: SecretKey? = null

    /**
     * Retrieves or generates the non-exportable hardware-backed AES-256 key from the Android Keystore.
     * Never deletes the existing key on generic exceptions to prevent permanent loss of locked documents.
     */
    fun getMasterKey(): SecretKey {
        testSecretKey?.let { return it }

        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val key = try {
                keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            } catch (e: Exception) {
                logE("LockedDocCrypto", "Failed retrieving existing master key via getKey: ${e.message}", e)
                null
            }
            if (key != null) {
                return key
            }
            val entry = try {
                keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            } catch (e: Exception) {
                logE("LockedDocCrypto", "Failed retrieving existing master key via getEntry: ${e.message}", e)
                null
            }
            if (entry != null) {
                return entry.secretKey
            } else {
                throw KeyStoreException("Master key alias '$KEY_ALIAS' exists but could not be read as SecretKey")
            }
        }

        val keyGenerator = KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Resolves the Data Encryption Key (DEK) for a document folder.
     * Decrypts .dek.enc using the Android Keystore master key (or provided secretKey).
     * Throws [DekDecryptionException] on decryption or verification failure instead of silently falling back.
     */
    @Throws(DekDecryptionException::class)
    fun getOrDecryptDek(docDir: File, secretKey: SecretKey = getMasterKey()): SecretKey {
        var dir: File? = if (docDir.isDirectory) docDir else docDir.parentFile
        if (dir?.name == ".originals") {
            dir = dir.parentFile
        }
        val dekFile = if (dir != null) File(dir, ".dek.enc") else null
        if (dekFile == null || !dekFile.exists()) {
            // Backward-compatibility: legacy locked document without .dek.enc was encrypted directly with master key
            return secretKey
        }
        if (dekFile.length() == 0L) {
            throw DekDecryptionException("Corrupted document key file: .dek.enc is empty")
        }

        return try {
            FileInputStream(dekFile).use { fis ->
                val magic = ByteArray(FILE_MAGIC.size)
                if (fis.read(magic) != FILE_MAGIC.size) {
                    throw DekDecryptionException("Corrupted or empty DEK header in ${dekFile.name}")
                }
                if (magic.contentEquals(LocalEncryptedBackupManager.BACKUP_DEK_MAGIC)) {
                    throw DekDecryptionException("Document encryption key is wrapped under a backup password. Please restore this document using 'Restore Encrypted Backup'.")
                }
                if (!magic.contentEquals(FILE_MAGIC)) {
                    throw DekDecryptionException("Invalid DEK file magic header")
                }
                val dekIv = ByteArray(IV_LENGTH)
                if (fis.read(dekIv) != IV_LENGTH) {
                    throw DekDecryptionException("Truncated DEK initialization vector (IV)")
                }
                val lenBuf = ByteArray(4)
                if (fis.read(lenBuf) != 4) {
                    throw DekDecryptionException("Truncated DEK length header")
                }
                val len = ByteBuffer.wrap(lenBuf).int
                if (len <= 0 || len > 1024) {
                    throw DekDecryptionException("Invalid DEK payload length: $len")
                }
                val encDek = ByteArray(len)
                if (fis.read(encDek) != len) {
                    throw DekDecryptionException("Truncated DEK ciphertext payload")
                }

                val masterCipher = Cipher.getInstance("AES/GCM/NoPadding")
                masterCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, dekIv))
                val dekBytes = masterCipher.doFinal(encDek)
                val dekSpec = SecretKeySpec(dekBytes, "AES")
                Arrays.fill(dekBytes, 0.toByte())
                dekSpec
            }
        } catch (e: DekDecryptionException) {
            throw e
        } catch (e: Exception) {
            logE("LockedDocCrypto", "Failed decrypting DEK: ${e.message}", e)
            throw DekDecryptionException("Failed to decrypt document encryption key (DEK): ${e.message}", e)
        }
    }

    /**
     * Verifies that an encrypted file can be authenticated and decrypted at least for its initial chunk.
     * Prevents deleting plaintext source files if encryption produced invalid or corrupted data.
     */
    fun verifyEncryptedChunk(encFile: File, key: SecretKey): Boolean {
        if (!encFile.exists() || encFile.length() < (FILE_MAGIC.size + IV_LENGTH)) {
            return false
        }
        return try {
            FileInputStream(encFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    val magic = ByteArray(FILE_MAGIC.size)
                    if (bis.read(magic) != FILE_MAGIC.size || !magic.contentEquals(FILE_MAGIC)) {
                        return false
                    }
                    val baseIv = ByteArray(IV_LENGTH)
                    if (bis.read(baseIv) != IV_LENGTH) {
                        return false
                    }
                    val gcmIn = LocalEncryptedBackupManager.ChunkedGcmInputStream(
                        input = bis,
                        keySpec = key,
                        baseIv = baseIv,
                        aad = FILE_MAGIC
                    )
                    val testBuf = ByteArray(1024)
                    gcmIn.read(testBuf)
                    true
                }
            }
        } catch (e: Exception) {
            logE("LockedDocCrypto", "Verification of encrypted file chunk failed: ${e.message}")
            false
        }
    }

    /**
     * Encrypts a single file to destination using streaming chunked AES-256-GCM.
     * Prevents OutOfMemoryErrors on large page scans.
     */
    fun encryptFile(
        srcFile: File,
        destFile: File,
        secretKey: SecretKey = getMasterKey()
    ) {
        val effectiveKey = if (secretKey == getMasterKey() || (testSecretKey != null && secretKey == testSecretKey)) {
            val parent = destFile.parentFile ?: srcFile.parentFile
            if (parent != null) getOrDecryptDek(parent, secretKey) else secretKey
        } else {
            secretKey
        }

        val secureRandom = SecureRandom()
        val baseIv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(baseIv)

        FileOutputStream(destFile).use { fos ->
            BufferedOutputStream(fos).use { bos ->
                // Write 8-byte file magic + 12-byte base IV
                bos.write(FILE_MAGIC)
                bos.write(baseIv)
                bos.flush()

                // Stream chunks
                val gcmOut = LocalEncryptedBackupManager.ChunkedGcmOutputStream(
                    out = bos,
                    keySpec = effectiveKey,
                    baseIv = baseIv,
                    aad = FILE_MAGIC,
                    chunkSize = CHUNK_SIZE
                )
                FileInputStream(srcFile).use { fis ->
                    BufferedInputStream(fis).use { bis ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (bis.read(buffer).also { read = it } != -1) {
                            gcmOut.write(buffer, 0, read)
                        }
                        gcmOut.flush()
                    }
                }
                gcmOut.close()
            }
        }
    }

    /**
     * Decrypts a chunked AES-256-GCM encrypted file to destination.
     */
    fun decryptFile(
        srcFile: File,
        destFile: File,
        secretKey: SecretKey = getMasterKey()
    ) {
        val effectiveKey = if (secretKey == getMasterKey() || (testSecretKey != null && secretKey == testSecretKey)) {
            val parent = srcFile.parentFile ?: destFile.parentFile
            if (parent != null) getOrDecryptDek(parent, secretKey) else secretKey
        } else {
            secretKey
        }

        FileInputStream(srcFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                val magic = ByteArray(FILE_MAGIC.size)
                if (bis.read(magic) != FILE_MAGIC.size || !magic.contentEquals(FILE_MAGIC)) {
                    throw IOException("Invalid locked document file header: magic mismatch")
                }
                val baseIv = ByteArray(IV_LENGTH)
                if (bis.read(baseIv) != IV_LENGTH) {
                    throw IOException("Invalid locked document file header: truncated IV")
                }

                val gcmIn = LocalEncryptedBackupManager.ChunkedGcmInputStream(
                    input = bis,
                    keySpec = effectiveKey,
                    baseIv = baseIv,
                    aad = FILE_MAGIC
                )
                FileOutputStream(destFile).use { fos ->
                    BufferedOutputStream(fos).use { bos ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (gcmIn.read(buffer).also { read = it } != -1) {
                            bos.write(buffer, 0, read)
                        }
                        bos.flush()
                    }
                }
            }
        }
    }

    /**
     * Decrypts an encrypted file directly into in-memory bytes without writing plaintext to disk.
     */
    fun decryptFileToBytes(
        srcFile: File,
        secretKey: SecretKey = getMasterKey()
    ): ByteArray {
        val effectiveKey = if (secretKey == getMasterKey() || (testSecretKey != null && secretKey == testSecretKey)) {
            val parent = srcFile.parentFile
            if (parent != null) getOrDecryptDek(parent, secretKey) else secretKey
        } else {
            secretKey
        }

        val baos = ByteArrayOutputStream()
        FileInputStream(srcFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                val magic = ByteArray(FILE_MAGIC.size)
                if (bis.read(magic) != FILE_MAGIC.size || !magic.contentEquals(FILE_MAGIC)) {
                    throw IOException("Invalid locked document file header: magic mismatch")
                }
                val baseIv = ByteArray(IV_LENGTH)
                if (bis.read(baseIv) != IV_LENGTH) {
                    throw IOException("Invalid locked document file header: truncated IV")
                }

                val gcmIn = LocalEncryptedBackupManager.ChunkedGcmInputStream(
                    input = bis,
                    keySpec = effectiveKey,
                    baseIv = baseIv,
                    aad = FILE_MAGIC
                )
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (gcmIn.read(buffer).also { read = it } != -1) {
                    baos.write(buffer, 0, read)
                }
            }
        }
        return baos.toByteArray()
    }

    /**
     * Encrypts in-memory bytes directly to an encrypted file on disk.
     */
    fun encryptBytesToFile(
        bytes: ByteArray,
        destFile: File,
        secretKey: SecretKey = getMasterKey()
    ) {
        val effectiveKey = if (secretKey == getMasterKey() || (testSecretKey != null && secretKey == testSecretKey)) {
            val parent = destFile.parentFile
            if (parent != null) getOrDecryptDek(parent, secretKey) else secretKey
        } else {
            secretKey
        }

        val secureRandom = SecureRandom()
        val baseIv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(baseIv)

        FileOutputStream(destFile).use { fos ->
            BufferedOutputStream(fos).use { bos ->
                bos.write(FILE_MAGIC)
                bos.write(baseIv)
                bos.flush()

                val gcmOut = LocalEncryptedBackupManager.ChunkedGcmOutputStream(
                    out = bos,
                    keySpec = effectiveKey,
                    baseIv = baseIv,
                    aad = FILE_MAGIC,
                    chunkSize = CHUNK_SIZE
                )
                gcmOut.write(bytes)
                gcmOut.flush()
                gcmOut.close()
            }
        }
    }

    /**
     * Two-way migration: Locks an existing document directory at rest.
     * Uses hardware-backed Envelope Encryption:
     * 1. Reuses existing DEK if .dek.enc already exists; generates a random 256-bit AES DEK if not.
     * 2. Encrypts DEK using Android Keystore Master Key (Keystore generates random IV).
     * 3. Writes .dek.enc inside the document folder atomically.
     * 4. Streams AES-256-GCM encryption of all page images, ocr.txt, thumbnails, and .originals using DEK.
     * 5. Verifies encrypted files before deleting plaintext to prevent data loss.
     */
    suspend fun lockDocument(
        context: Context?,
        docDir: File,
        secretKey: SecretKey = getMasterKey()
    ): Boolean = withContext(Dispatchers.IO) {
        if (!docDir.exists() || !docDir.isDirectory) return@withContext false

        try {
            recoverInterruptedLockOperations(docDir)

            val dekFile = File(docDir, ".dek.enc")
            val dekSpec = if (dekFile.exists() && dekFile.length() > 0L) {
                // Reuse existing DEK if present instead of overwriting it
                getOrDecryptDek(docDir, secretKey)
            } else {
                // 1. Generate unique 256-bit random DEK
                val dekBytes = ByteArray(32)
                SecureRandom().nextBytes(dekBytes)
                val newSpec = SecretKeySpec(dekBytes, "AES")

                // 2. Encrypt DEK with master key (Android Keystore hardware key)
                val masterCipher = Cipher.getInstance("AES/GCM/NoPadding")
                masterCipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val dekIv = masterCipher.iv // Hardware-generated 12-byte IV
                val encryptedDek = masterCipher.doFinal(dekBytes)

                // 3. Write .dek.enc to docDir atomically
                val dekTempFile = File(docDir, ".dek.enc.lock_tmp")
                FileOutputStream(dekTempFile).use { fos ->
                    fos.write(FILE_MAGIC)
                    fos.write(dekIv)
                    val lenBuf = ByteBuffer.allocate(4).putInt(encryptedDek.size).array()
                    fos.write(lenBuf)
                    fos.write(encryptedDek)
                    fos.flush()
                }
                if (!dekTempFile.renameTo(dekFile)) {
                    dekTempFile.copyTo(dekFile, overwrite = true)
                    dekTempFile.delete()
                }

                Arrays.fill(dekBytes, 0.toByte())
                newSpec
            }

            fun processDir(dir: File) {
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory) {
                        if (file.name == ".originals") {
                            processDir(file)
                        }
                    } else if (file.isFile) {
                        val name = file.name
                        if (name == "meta.json" || name == ".dek.enc" || name.endsWith(".enc") || name.endsWith(".lock_tmp") || name.endsWith(".unlock_tmp")) {
                            continue
                        }
                        val isTarget = name.endsWith(".jpg", ignoreCase = true) ||
                                name.endsWith(".jpeg", ignoreCase = true) ||
                                name.endsWith(".png", ignoreCase = true) ||
                                name == "ocr.txt"

                        if (isTarget) {
                            val tempEncFile = File(dir, "$name.lock_tmp")
                            val finalEncFile = File(dir, "$name.enc")

                            encryptFile(file, tempEncFile, dekSpec)

                            // Verify encrypted chunk before deleting plaintext!
                            if (tempEncFile.exists() && verifyEncryptedChunk(tempEncFile, dekSpec)) {
                                val renamed = if (tempEncFile.renameTo(finalEncFile)) {
                                    true
                                } else {
                                    tempEncFile.copyTo(finalEncFile, overwrite = true)
                                    tempEncFile.delete()
                                    true
                                }
                                if (renamed && finalEncFile.exists()) {
                                    file.delete()
                                } else {
                                    throw IOException("Failed to finalize encrypted file for ${file.name}")
                                }
                            } else {
                                tempEncFile.delete()
                                throw IOException("Verification failed for encrypted file ${file.name}; plaintext preserved")
                            }
                        }
                    }
                }
            }

            processDir(docDir)

            // Update meta.json
            val meta = DocMeta.readFromDir(docDir).copy(isLocked = true)
            DocMeta.writeToDir(docDir, meta)

            // Wipe any active session cache and memory thumbnails
            if (context != null) {
                wipeDecryptedSession(context, docDir.name)
            }
            try {
                thumbnailMemoryCache.evictAll()
            } catch (_: Throwable) {}

            true
        } catch (e: DekDecryptionException) {
            logE("LockedDocCrypto", "Failed locking document ${docDir.name}: ${e.message}", e)
            if (context != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Cannot lock document: existing encryption key is corrupted.", Toast.LENGTH_LONG).show()
                }
            }
            false
        } catch (e: Exception) {
            logE("LockedDocCrypto", "Failed locking document ${docDir.name}: ${e.message}", e)
            if (context != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to lock document: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            false
        }
    }

    /**
     * Two-way migration: Permanently unlocks a document directory, decrypting its files back to plaintext.
     * Crash-safe: writes to .unlock_tmp, verifies, then renames, and deletes .enc.
     */
    suspend fun unlockDocumentPermanently(
        context: Context?,
        docDir: File,
        secretKey: SecretKey = getMasterKey()
    ): Boolean = withContext(Dispatchers.IO) {
        if (!docDir.exists() || !docDir.isDirectory) return@withContext false

        try {
            recoverInterruptedLockOperations(docDir)

            val dekSpec = getOrDecryptDek(docDir, secretKey)

            fun processDir(dir: File) {
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory) {
                        if (file.name == ".originals") {
                            processDir(file)
                        }
                    } else if (file.isFile && file.name.endsWith(".enc", ignoreCase = true)) {
                        if (file.name == ".dek.enc") continue
                        var plainName = file.name.removeSuffix(".enc")
                        // If plainName has no extension, normalize to .jpg so media scanners & ScanVault recognize it
                        if (!plainName.contains(".")) {
                            plainName = "$plainName.jpg"
                        }
                        val tempPlainFile = File(dir, "$plainName.unlock_tmp")
                        val finalPlainFile = File(dir, plainName)

                        decryptFile(file, tempPlainFile, dekSpec)
                        if (tempPlainFile.exists()) {
                            if (tempPlainFile.renameTo(finalPlainFile)) {
                                file.delete()
                            } else {
                                tempPlainFile.copyTo(finalPlainFile, overwrite = true)
                                tempPlainFile.delete()
                                file.delete()
                            }
                        }
                    }
                }
            }

            processDir(docDir)

            // Remove .dek.enc
            try { File(docDir, ".dek.enc").delete() } catch (_: Exception) {}

            // Update meta.json
            val meta = DocMeta.readFromDir(docDir).copy(isLocked = false)
            DocMeta.writeToDir(docDir, meta)

            if (context != null) {
                wipeDecryptedSession(context, docDir.name)
            }
            try {
                thumbnailMemoryCache.evictAll()
            } catch (_: Throwable) {}

            true
        } catch (e: DekDecryptionException) {
            logE("LockedDocCrypto", "Failed permanently unlocking document ${docDir.name}: ${e.message}", e)
            if (context != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Cannot unlock document: encryption key could not be decrypted.", Toast.LENGTH_LONG).show()
                }
            }
            false
        } catch (e: Exception) {
            logE("LockedDocCrypto", "Failed permanently unlocking document ${docDir.name}: ${e.message}", e)
            if (context != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to unlock document: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            false
        }
    }

    /**
     * Crash-recovery: Inspects directory for any interrupted lock/unlock operations.
     */
    fun recoverInterruptedLockOperations(docDir: File) {
        if (!docDir.exists() || !docDir.isDirectory) return

        val meta = DocMeta.readFromDir(docDir)
        fun cleanDir(dir: File) {
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.isDirectory && f.name == ".originals") {
                    cleanDir(f)
                } else if (f.isFile) {
                    if (f.name.endsWith(".lock_tmp") || f.name.endsWith(".unlock_tmp")) {
                        f.delete()
                    }
                }
            }

            // Consolidate duplicate or extensionless .enc files created by previous image editor bug
            val remaining = dir.listFiles() ?: return
            val encFiles = remaining.filter { it.isFile && it.name.endsWith(".enc", ignoreCase = true) && it.name != ".dek.enc" && it.name != "ocr.txt.enc" }
            for (enc in encFiles) {
                val plain = enc.name.removeSuffix(".enc")
                if (!plain.contains(".")) {
                    val candidateJpg = File(dir, "$plain.jpg.enc")
                    val candidatePng = File(dir, "$plain.png.enc")
                    val candidateJpeg = File(dir, "$plain.jpeg.enc")
                    val target = if (candidateJpg.exists()) candidateJpg
                    else if (candidatePng.exists()) candidatePng
                    else if (candidateJpeg.exists()) candidateJpeg
                    else null

                    if (target != null && target.exists() && target.absolutePath != enc.absolutePath) {
                        if (enc.lastModified() >= target.lastModified() || enc.length() > 0) {
                            enc.copyTo(target, overwrite = true)
                        }
                        enc.delete()
                    } else if (target == null) {
                        enc.renameTo(File(dir, "$plain.jpg.enc"))
                    }
                }
            }

            // Check duplicate pairs
            val updatedRemaining = dir.listFiles() ?: return
            val fileNames = updatedRemaining.map { it.name }.toSet()
            for (name in fileNames) {
                if (name.endsWith(".enc")) {
                    if (name == ".dek.enc") continue
                    val plainName = name.removeSuffix(".enc")
                    if (fileNames.contains(plainName)) {
                        // If meta says locked, delete plaintext; if meta says unlocked, delete .enc
                        val plainFile = File(dir, plainName)
                        val encFile = File(dir, name)
                        if (meta.isLocked) {
                            plainFile.delete()
                        } else {
                            encFile.delete()
                        }
                    }
                }
            }
        }

        cleanDir(docDir)
    }

    // ---------------------------------------------------------
    // EPHEMERAL UNLOCKED SESSION MANAGEMENT
    // ---------------------------------------------------------

    private fun getSessionBaseDir(context: Context): File {
        val dir = File(context.cacheDir, "session_unlocked")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Decrypts locked files on-demand into an ephemeral private cache folder for the active unlocked session.
     */
    suspend fun prepareUnlockedSession(
        context: Context,
        docDir: File,
        secretKey: SecretKey = getMasterKey()
    ): File = withContext(Dispatchers.IO) {
        val folderName = docDir.name
        val sessionDocDir = File(getSessionBaseDir(context), folderName)
        if (!sessionDocDir.exists()) sessionDocDir.mkdirs()

        // Create .nomedia to protect from media scanners
        File(sessionDocDir, ".nomedia").createNewFile()

        val dekSpec = try {
            getOrDecryptDek(docDir, secretKey)
        } catch (e: DekDecryptionException) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to decrypt locked document: key check failed.", Toast.LENGTH_LONG).show()
            }
            throw e
        }

        val files = docDir.listFiles() ?: emptyArray()
        for (f in files) {
            if (f.isFile && f.name.endsWith(".enc", ignoreCase = true)) {
                if (f.name == ".dek.enc") continue
                val plainName = f.name.removeSuffix(".enc")
                val targetFile = File(sessionDocDir, plainName)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    decryptFile(f, targetFile, dekSpec)
                }
            } else if (f.isFile && (f.extension.equals("jpg", true) || f.extension.equals("jpeg", true) || f.extension.equals("png", true) || f.name == "ocr.txt")) {
                // Handle new or unencrypted files in locked directory
                val targetFile = File(sessionDocDir, f.name)
                if (!targetFile.exists() || targetFile.length() == 0L) {
                    f.copyTo(targetFile, overwrite = true)
                }
                // Automatically ensure it is encrypted at rest in docDir
                val encFile = File(docDir, "${f.name}.enc")
                if (!encFile.exists() || encFile.length() == 0L) {
                    try {
                        val tempEnc = File(docDir, "${f.name}.lock_tmp")
                        encryptFile(f, tempEnc, dekSpec)
                        if (tempEnc.exists() && verifyEncryptedChunk(tempEnc, dekSpec)) {
                            if (tempEnc.renameTo(encFile) || (tempEnc.copyTo(encFile, overwrite = true).also { tempEnc.delete() } != null)) {
                                f.delete()
                            }
                        } else {
                            tempEnc.delete()
                        }
                    } catch (e: Exception) {
                        logE("LockedDocCrypto", "Failed auto-encrypting appended file ${f.name}: ${e.message}")
                    }
                }
            }
        }

        sessionDocDir
    }

    fun getUnlockedSessionDir(context: Context, folderName: String): File? {
        val dir = File(getSessionBaseDir(context), folderName)
        return if (dir.exists() && dir.isDirectory) dir else null
    }

    /**
     * Completely wipes decrypted session files on relock, backgrounding, or timeout.
     */
    fun wipeDecryptedSession(context: Context?, folderName: String? = null) {
        if (context == null) return
        try {
            val baseDir = getSessionBaseDir(context)
            if (folderName != null) {
                val dir = File(baseDir, folderName)
                if (dir.exists()) {
                    dir.listFiles()?.forEach { file ->
                        try {
                            if (file.isFile) {
                                // Overwrite with zeros before deleting
                                val len = file.length()
                                if (len in 1..10485760) {
                                    FileOutputStream(file).use { fos ->
                                        fos.write(ByteArray(len.toInt()))
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    dir.deleteRecursively()
                }
            } else {
                if (baseDir.exists()) {
                    baseDir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            logE("LockedDocCrypto", "Error wiping session: ${e.message}")
        } finally {
            try {
                thumbnailMemoryCache.evictAll()
            } catch (_: Throwable) {}
        }
    }


}

// -------------------------------------------------------------
// APP-LEVEL LOCK & INACTIVITY TIMEOUT MANAGER
// -------------------------------------------------------------

object AppLockManager {
    private const val PREFS_NAME = "scanvault_security_settings"
    private const val KEY_APP_LOCK_ENABLED = "pref_app_lock_enabled"
    private const val KEY_INACTIVITY_TIMEOUT_SEC = "pref_inactivity_timeout_sec"
    const val DEFAULT_INACTIVITY_TIMEOUT_SEC = 120 // 2 minutes
    const val APP_LOCK_GRACE_PERIOD_MS = 5000L // 5 seconds grace period before re-locking

    @Volatile
    private var lastInteractionTimestamp: Long = System.currentTimeMillis()

    @Volatile
    private var backgroundedTimestamp: Long = 0L

    fun isAppLockEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
    }

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
    }

    fun getInactivityTimeoutSeconds(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_INACTIVITY_TIMEOUT_SEC, DEFAULT_INACTIVITY_TIMEOUT_SEC)
    }

    fun setInactivityTimeoutSeconds(context: Context, seconds: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_INACTIVITY_TIMEOUT_SEC, seconds).apply()
    }

    fun recordUserInteraction() {
        lastInteractionTimestamp = System.currentTimeMillis()
    }

    fun onAppBackgrounded() {
        backgroundedTimestamp = System.currentTimeMillis()
    }

    fun onAppForegrounded(context: Context): Boolean {
        if (!isAppLockEnabled(context)) {
            backgroundedTimestamp = 0L
            return false
        }
        if (backgroundedTimestamp == 0L) {
            return false
        }
        val elapsed = System.currentTimeMillis() - backgroundedTimestamp
        val shouldLock = elapsed >= APP_LOCK_GRACE_PERIOD_MS
        backgroundedTimestamp = 0L
        return shouldLock
    }

    fun onAppUnlocked() {
        backgroundedTimestamp = 0L
        recordUserInteraction()
    }

    fun isInactive(timeoutSeconds: Int): Boolean {
        if (timeoutSeconds <= 0) return false
        val elapsed = (System.currentTimeMillis() - lastInteractionTimestamp) / 1000
        return elapsed >= timeoutSeconds
    }
}
