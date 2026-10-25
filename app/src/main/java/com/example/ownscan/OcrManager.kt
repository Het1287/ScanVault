package com.example.ownscan

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.work.*
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// Note: ML Kit does not provide a Gujarati text recognition model. Gujarati OCR is not supported by ML Kit.
// For Hindi and Sanskrit scripts, the Devanagari recognizer is utilized alongside Latin options.

/**
 * Extension to await Google Play Services Task in coroutines without extra dependencies.
 */
suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

object OcrManager {
    private const val TAG = "OcrManager"

    // Bound OCR concurrency to avoid CPU starvation and out-of-memory spikes on multi-page processing
    private val ocrConcurrencySemaphore = Semaphore(2)

    /**
     * Runs ML Kit Text Recognition on a single image URI for fast auto-naming suggestion.
     * Merges Latin and Devanagari models to ensure multilingual documents (e.g. Hindi/English)
     * are accurately recognized. Recognizers are always cleanly closed in finally.
     */
    suspend fun quickAnalyzeFirstPage(context: Context, imageUri: Uri): String? = withContext(Dispatchers.IO) {
        ocrConcurrencySemaphore.withPermit {
            var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
            var devanagariRecognizer: com.google.mlkit.vision.text.TextRecognizer? = null
            try {
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                devanagariRecognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                val inputImage = InputImage.fromFilePath(context, imageUri)

                val latinText = try { recognizer.process(inputImage).awaitTask().text.trim() } catch (e: Exception) { "" }
                val devText = try { devanagariRecognizer.process(inputImage).awaitTask().text.trim() } catch (e: Exception) { "" }

                val mergedText = mergeOcrTexts(latinText, devText)
                if (mergedText.isNotBlank()) {
                    generateTitleSuggestion(mergedText)
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Quick OCR failed: ${e.message}")
                null
            } finally {
                recognizer?.close()
                devanagariRecognizer?.close()
            }
        }
    }

    /**
     * Runs full OCR per page on a document directory.
     * Privacy & Security:
     * - If document is locked and not currently in an unlocked session: refuses OCR and returns empty.
     * - If document is locked and unlocked in session: runs in-memory, writes ciphertext to ocr.txt.enc,
     *   stores plaintext only in the ephemeral session cache, and keeps Room/FTS pageText strictly empty.
     * - For unlocked documents: persists ocr.txt and indexes in Room/FTS.
     */
    suspend fun processDocumentOcr(context: Context, docFolder: File): String = withContext(Dispatchers.IO) {
        if (!docFolder.exists() || !docFolder.isDirectory) return@withContext ""

        val meta = DocMeta.readFromDir(docFolder)
        val isLocked = meta.isLocked
        val logDocName = if (isLocked) "[PROTECTED_DOCUMENT]" else docFolder.name

        val sessionDir = if (isLocked) {
            LockedDocCryptoManager.getUnlockedSessionDir(context, docFolder.name)
        } else {
            null
        }

        if (isLocked && sessionDir == null) {
            Log.w(TAG, "Skipping OCR on locked document $logDocName: session not unlocked")
            return@withContext ""
        }

        // Determine source files
        val sourceFiles: List<File> = if (isLocked) {
            val sFiles = sessionDir?.listFiles { f ->
                val ext = f.extension.lowercase(Locale.ROOT)
                f.isFile && (ext == "jpg" || ext == "jpeg" || ext == "png")
            }?.sortedBy { it.name } ?: emptyList()

            if (sFiles.isNotEmpty()) {
                sFiles
            } else {
                docFolder.listFiles { f ->
                    f.isFile && f.name.endsWith(".enc", ignoreCase = true) &&
                            (f.name.contains(".jpg.", ignoreCase = true) || f.name.contains(".png.", ignoreCase = true) || f.name.contains(".jpeg.", ignoreCase = true))
                }?.sortedBy { it.name } ?: emptyList()
            }
        } else {
            docFolder.listFiles { file ->
                val ext = file.extension.lowercase(Locale.ROOT)
                file.isFile && (ext == "jpg" || ext == "jpeg" || ext == "png")
            }?.sortedBy { it.name } ?: emptyList()
        }

        if (sourceFiles.isEmpty()) return@withContext ""

        val fullTextBuilder = StringBuilder()

        ocrConcurrencySemaphore.withPermit {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val devanagariRecognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

            try {
                sourceFiles.forEachIndexed { index, file ->
                    try {
                        val inputImage = if (isLocked && file.name.endsWith(".enc", ignoreCase = true)) {
                            val decryptedBytes = LockedDocCryptoManager.decryptFileToBytes(file)
                            val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                                ?: return@forEachIndexed
                            InputImage.fromBitmap(bitmap, 0)
                        } else {
                            InputImage.fromFilePath(context, Uri.fromFile(file))
                        }

                        val latinText = try { recognizer.process(inputImage).awaitTask().text.trim() } catch (e: Exception) { Log.w(TAG, "Failed latin OCR processing", e); "" }
                        val devText = try { devanagariRecognizer.process(inputImage).awaitTask().text.trim() } catch (e: Exception) { Log.w(TAG, "Failed devanagari OCR processing", e); "" }
                        val pageText = mergeOcrTexts(latinText, devText)

                        if (pageText.isNotEmpty()) {
                            fullTextBuilder.append("[Page ${index + 1}]\n")
                            fullTextBuilder.append(pageText)
                            fullTextBuilder.append("\n\n")
                        }
                    } catch (e: Exception) {
                        val pageLabel = if (isLocked) "[Page ${index + 1}]" else file.name
                        Log.e(TAG, "Failed OCR on $pageLabel in $logDocName: ${e.message}")
                    }
                }
            } finally {
                recognizer.close()
                devanagariRecognizer.close()
            }
        }

        val fullText = fullTextBuilder.toString().trim()

        // 1. Update meta.json on disk
        val updatedMeta = meta.copy(isOcrDone = true)
        DocMeta.writeToDir(docFolder, updatedMeta)

        // 2. Persist OCR text safely
        if (isLocked) {
            try {
                val encFile = File(docFolder, "ocr.txt.enc")
                if (fullText.isNotBlank()) {
                    val textBytes = fullText.toByteArray(Charsets.UTF_8)
                    LockedDocCryptoManager.encryptBytesToFile(textBytes, encFile)
                } else if (encFile.exists()) {
                    encFile.delete()
                }
                sessionDir?.let { sDir ->
                    val sessionOcr = File(sDir, "ocr.txt")
                    if (fullText.isNotBlank()) sessionOcr.writeText(fullText) else sessionOcr.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing encrypted OCR text for $logDocName: ${e.message}")
            }
        } else {
            try {
                val ocrFile = File(docFolder, "ocr.txt")
                if (fullText.isNotBlank()) {
                    ocrFile.writeText(fullText)
                } else if (ocrFile.exists()) {
                    ocrFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing ocr.txt to $logDocName: ${e.message}")
            }
        }

        // 3. Update Room database & FTS
        try {
            val db = AppDatabase.getInstance(context)
            val dao = db.documentDao()
            val existing = dao.getByFolderName(docFolder.name)
            val storedRoomText = if (isLocked) "" else fullText
            if (existing != null) {
                dao.insertOrUpdate(existing.copy(isOcrDone = true, ocrText = storedRoomText))
            }

            val displayName = docFolder.name.substringAfter("__", docFolder.name)
            val tagsText = meta.tags.joinToString(" ")
            db.documentFtsDao().deleteByFolderName(docFolder.name)
            db.documentFtsDao().insertOrUpdate(
                DocumentFtsEntity(
                    rowid = 0,
                    folderName = docFolder.name,
                    displayName = displayName,
                    tagsText = tagsText,
                    pageText = if (isLocked) "" else fullText
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving OCR text to Room for $logDocName: ${e.message}")
        }

        fullText
    }

    /**
     * Bulk processing of pending OCR via WorkManager.
     */
    suspend fun processAllPendingDocs(context: Context) = withContext(Dispatchers.IO) {
        try {
            val allDocs = loadAllInAppDocuments(context)
            for (doc in allDocs) {
                val meta = DocMeta.readFromDir(doc.directory)
                if (meta.isLocked) continue

                if (!meta.isOcrDone && doc.imageFiles.isNotEmpty()) {
                    processDocumentOcr(context, doc.directory)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in bulk pending OCR: ${e.message}")
        }
    }

    /**
     * Merges Latin and Devanagari OCR text blocks.
     * Preserves Latin text layout while integrating distinct Devanagari lines (Hindi/Sanskrit)
     * without generating duplicate entries.
     */
    fun mergeOcrTexts(latinText: String, devText: String): String {
        if (latinText.isBlank() && devText.isBlank()) return ""
        if (latinText.isBlank()) return devText.trim()
        if (devText.isBlank()) return latinText.trim()

        val latinLines = latinText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val devLines = devText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val merged = mutableListOf<String>()
        merged.addAll(latinLines)

        for (dLine in devLines) {
            val hasDevanagari = dLine.any { it in '\u0900'..'\u097F' }
            if (hasDevanagari || !merged.any { it.equals(dLine, ignoreCase = true) }) {
                merged.add(dLine)
            }
        }
        return merged.joinToString("\n")
    }

    // -------------------------------------------------------------
    // AUTO-NAMING ENGINE (OCR text + detected date)
    // Pure, unit-testable functions
    // -------------------------------------------------------------

    fun generateTitleSuggestion(rawText: String): String? {
        if (rawText.isBlank()) return null

        val docType = detectDocType(rawText)
        val dateStr = detectDate(rawText) ?: SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date())

        return if (docType != null) {
            "$docType – $dateStr"
        } else {
            val lines = rawText.lines().map { it.trim() }.filter { it.length in 4..35 }
            val firstLine = lines.firstOrNull { line ->
                !line.contains("Page", ignoreCase = true) &&
                        !line.matches(Regex("^[0-9\\W]+$")) &&
                        !isPiiOrNumberLine(line)
            }
            if (firstLine != null) {
                "$firstLine – $dateStr"
            } else {
                null
            }
        }
    }

    /**
     * Detects if a text line contains personal identifiable numbers, DOB, phone, or demographic headers
     * so they are excluded from automatic document naming.
     */
    fun isPiiOrNumberLine(line: String): Boolean {
        val trimmed = line.trim()
        if (AADHAAR_NUMBER_REGEX.matcher(trimmed).find()) return true
        if (PAN_REGEX.matcher(trimmed).find()) return true
        if (PASSPORT_MRZ_REGEX.matcher(trimmed).find() || PASSPORT_NO_REGEX.matcher(trimmed).find()) return true
        if (DL_NUMBER_REGEX.matcher(trimmed).find()) return true

        // Phone / Mobile numbers
        if (Regex("""(?i)\b(?:mob(?:ile)?|phone|tel|contact)\b|(?:\+?91[\s-]?)?[6-9]\d{9}\b""").containsMatchIn(trimmed)) return true

        // DOB / YOB
        if (Regex("""(?i)\b(?:dob|d\.o\.b|date\s+of\s+birth|birth\s+date|yob|year\s+of\s+birth)\b""").containsMatchIn(trimmed)) return true

        // Common PII and demographic headers
        if (Regex("""(?i)\b(?:gender|male|female|s/o|d/o|w/o|father'?s?\s+name|mother'?s?\s+name|husband'?s?\s+name|pin\s*code)\b""").containsMatchIn(trimmed)) return true

        return false
    }

    // Strong patterns for official identity & tax documents
    private val AADHAAR_NUMBER_REGEX = Pattern.compile("\\b\\d{4}\\s\\d{4}\\s\\d{4}\\b")
    private val PAN_REGEX = Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b")
    private val PASSPORT_MRZ_REGEX = Pattern.compile("P<[A-Z]{3}[A-Z0-9<]+")
    private val PASSPORT_NO_REGEX = Pattern.compile("\\bPassport\\s*(?:No|Number|#)?\\s*[:.]?\\s*[A-Z][0-9]{7}\\b", Pattern.CASE_INSENSITIVE)
    private val DL_NUMBER_REGEX = Pattern.compile("\\b[A-Z]{2}[0-9]{2}[0-9A-Z]{11,12}\\b")

    /**
     * Pure, unit-testable document classifier.
     * Evaluates strong patterns first (Aadhaar number + UIDAI, PAN format, Passport MRZ/Passport No),
     * then word-boundary keywords ordered from most specific to least.
     * Avoids over-matching on "Republic of India" (which appears on nearly all official Indian documents)
     * and "dr." (which occurs in addresses, drive, drop, etc.).
     */
    fun detectDocType(text: String): String? {
        if (text.isBlank()) return null
        val lower = text.lowercase(Locale.ROOT)

        // 1. Aadhaar Card (Strong Pattern: 12-digit grouped format or explicit Aadhaar keywords)
        val hasAadhaarNumber = AADHAAR_NUMBER_REGEX.matcher(text).find()
        val hasUidaiKeywords = lower.contains("unique identification") || lower.contains("uidai") || lower.contains("mera aadhaar")
        val hasAadhaarWord = Regex("\\b(?:aadhaar|aadhar)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if ((hasAadhaarNumber && hasUidaiKeywords) || (hasAadhaarWord && (hasAadhaarNumber || hasUidaiKeywords || lower.contains("government of india")))) {
            return "Aadhaar Card"
        }

        // 2. PAN Card (Strong Pattern: 10-character alphanumeric Indian PAN format)
        val hasPanPattern = PAN_REGEX.matcher(text).find()
        val hasIncomeTaxDept = lower.contains("income tax department") || lower.contains("permanent account number")
        val hasPanCardWord = Regex("\\bpan\\s+card\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (hasPanPattern && (hasIncomeTaxDept || hasPanCardWord || lower.contains("govt. of india") || lower.contains("father's name"))) {
            return "PAN Card"
        }
        if (hasIncomeTaxDept && hasPanCardWord) {
            return "PAN Card"
        }

        // 3. Passport (Strong Pattern: MRZ lines or explicit "Passport No")
        // NEVER classify solely on "Republic of India", as it is present on PAN, Aadhaar, birth certificates, etc.
        val hasPassportMrz = PASSPORT_MRZ_REGEX.matcher(text).find()
        val hasPassportNo = PASSPORT_NO_REGEX.matcher(text).find()
        val hasPassportWord = Regex("\\bpassport\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (hasPassportMrz || hasPassportNo || (hasPassportWord && (lower.contains("republic of india") || lower.contains("type/type") || lower.contains("code/code")))) {
            return "Passport"
        }

        // 4. Driving License
        val hasDlNumber = DL_NUMBER_REGEX.matcher(text).find()
        val hasDlKeywords = Regex("\\b(?:driving\\s+licen[sc]e|driver\\s+licen[sc]e|motor\\s+driving|transport\\s+department)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (hasDlKeywords || (hasDlNumber && lower.contains("licence"))) {
            return "Driving License"
        }

        // 5. Invoices & Receipts (Specific before generic)
        if (Regex("\\b(?:tax\\s+invoice|gstin\\b|gst\\s+invoice)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Tax Invoice"
        }
        if (Regex("\\b(?:invoice\\s*(?:no|number|#)?|bill\\s+to\\b)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Invoice"
        }
        if (Regex("\\b(?:payment\\s+receipt|receipt\\s*(?:no|number|#)?|payment\\s+confirmation|acknowledgement\\s+receipt)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Receipt"
        }

        // 6. Utility Bills
        if (Regex("\\b(?:electricity\\s+bill|power\\s+corporation|bescom|mseb|tneb|discom|electric\\s+bill)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Electricity Bill"
        }
        if (Regex("\\b(?:water\\s+bill|jal\\s+board|water\\s+supply)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Water Bill"
        }

        // 7. Medical Prescription (Require medical context; do NOT match "dr." alone!)
        val hasMedicalContext = Regex("\\b(?:patient|hospital|clinic|tablets?|capsules?|dosage|mg\\b|pharmacy|diagnostics?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val hasPrescriptionWord = Regex("\\b(?:prescription|rx\\b|medical\\s+prescription|diagnostic\\s+report)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val hasDoctorTitle = Regex("\\bdr\\.\\s+[a-z]{3,}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (hasPrescriptionWord || (hasDoctorTitle && hasMedicalContext)) {
            return "Medical Prescription"
        }

        // 8. Banking & Financial
        if (Regex("\\b(?:salary\\s+slip|payslip|pay\\s+slip|earnings\\s+and\\s+deductions)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Salary Slip"
        }
        if (Regex("\\b(?:bank\\s+statement|account\\s+statement|opening\\s+balance|closing\\s+balance)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Bank Statement"
        }
        if (Regex("\\b(?:insurance\\s+policy|policy\\s+schedule|premium\\s+paid|sum\\s+insured)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Insurance Policy"
        }

        // 9. Travel & Agreements
        if (Regex("\\b(?:boarding\\s+pass|flight\\s+ticket|e-ticket|irctc|railway\\s+ticket)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Ticket"
        }
        if (Regex("\\b(?:rent\\s+agreement|lease\\s+agreement|rental\\s+agreement|tenancy\\s+agreement)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Rent Agreement"
        }
        if (Regex("\\b(?:certificate\\s+of|certify\\s+that|completion\\s+certificate|birth\\s+certificate|death\\s+certificate)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return "Certificate"
        }

        return null
    }

    fun detectDate(text: String): String? {
        if (text.isBlank()) return null
        // Ignore dates accompanied by DOB / birth indicators so birthday is never chosen as document date
        val dobRegex = Regex("""(?i)\b(?:dob|d\.o\.b|date\s+of\s+birth|birth\s+date|yob|year\s+of\s+birth)\b[:\s-]*[0-9a-zA-Z/,\s-]+""")
        val cleaned = text.replace(dobRegex, " ")

        val patterns = listOf(
            Pattern.compile("(\\b\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4}\\b)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4}\\b)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}\\b)"),
            Pattern.compile("(\\b\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}\\b)")
        )

        for (p in patterns) {
            val matcher = p.matcher(cleaned)
            if (matcher.find()) {
                val rawDate = matcher.group(1) ?: continue
                val formatted = parseAndFormatDate(rawDate)
                if (formatted != null) return formatted
            }
        }
        return null
    }

    private fun parseAndFormatDate(raw: String): String? {
        val formats = listOf(
            "d MMM yyyy", "d MMMM yyyy", "MMM yyyy", "MMMM yyyy",
            "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy",
            "yyyy-MM-dd", "yyyy/MM/dd"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH).apply { isLenient = false }
                val parsed = sdf.parse(raw.trim())
                if (parsed != null) {
                    return SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(parsed)
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Phase 10f: Detects if OCR text contains sensitive Indian ID documents (Aadhaar, PAN, Passport, DL, Voter ID).
     */
    fun isSensitiveIdDoc(text: String): Boolean {
        if (text.isBlank()) return false
        val docType = detectDocType(text)
        if (docType in listOf("Aadhaar Card", "PAN Card", "Passport", "Driving License", "Voter ID")) return true
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("voter id") || lower.contains("elector photo identity") || lower.contains("election commission of india") || lower.contains("epic no")) return true
        if (lower.contains("driving licence") || lower.contains("driving license") || lower.contains("driver license")) return true
        if (AADHAAR_NUMBER_REGEX.matcher(text).find() && (lower.contains("uidai") || lower.contains("aadhaar") || lower.contains("aadhar") || lower.contains("mera aadhaar"))) return true
        if (PAN_REGEX.matcher(text).find() && (lower.contains("income tax") || lower.contains("permanent account") || lower.contains("pan"))) return true
        if (PASSPORT_MRZ_REGEX.matcher(text).find() || (PASSPORT_NO_REGEX.matcher(text).find() && lower.contains("passport")) || lower.contains("passport")) return true
        return false
    }

    /**
     * Phase 10f: Detects expiry dates associated with expiration keywords (e.g., Valid Thru, Expiry Date, Expires, Exp).
     * Returns date formatted as ISO yyyy-MM-dd if parsable, or the cleaned raw date string.
     */
    fun detectExpiryDate(text: String): String? {
        if (text.isBlank()) return null
        val expiryKeywords = Regex(
            """(?i)\b(?:expiry\s+date|expiry|expires|valid\s*(?:thru|through|till|until|up\s*to)|exp(?:\.|\s+date)?)\b[:\s-]*([0-9]{4}[/-][0-9]{1,2}[/-][0-9]{1,2}|[0-9]{1,2}[/-][0-9]{1,2}[/-][0-9]{2,4}|[0-9]{1,2}[/-][0-9]{2,4}|[0-9]{1,2}\s+[a-zA-Z]{3,9}\s+[0-9]{4}|[a-zA-Z]{3,9}\s+[0-9]{4})"""
        )
        val match = expiryKeywords.find(text)
        if (match != null) {
            val rawDate = match.groups[1]?.value?.trim() ?: return null
            val isTwoDigitYear = rawDate.contains(Regex("""[/ -]\d{2}$"""))
            val formats = if (isTwoDigitYear) {
                listOf(
                    "dd/MM/yy", "d/M/yy", "dd-MM-yy", "d-M-yy",
                    "MM/yy", "MM-yy", "MMM yy"
                )
            } else {
                listOf(
                    "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy",
                    "yyyy-MM-dd", "yyyy/MM/dd", "d MMM yyyy", "d MMMM yyyy", "MMM yyyy",
                    "MM/yyyy", "MM-yyyy"
                )
            }
            for (fmt in formats) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.ENGLISH).apply { isLenient = false }
                    val d = sdf.parse(rawDate)
                    if (d != null) {
                        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(d)
                    }
                } catch (_: Exception) {}
            }
            return rawDate
        }
        return null
    }

    fun detectExpiryDateEpoch(text: String): Long? {
        val dateStr = detectExpiryDate(text) ?: return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            sdf.parse(dateStr)?.time
        } catch (_: Exception) {
            null
        }
    }
}

// -------------------------------------------------------------
// WORKMANAGER BULK OCR WORKER
// -------------------------------------------------------------

class BulkOcrWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            OcrManager.processAllPendingDocs(appContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("BulkOcrWorker", "Bulk OCR execution error: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "ScanVaultBulkOcrWork"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<BulkOcrWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
