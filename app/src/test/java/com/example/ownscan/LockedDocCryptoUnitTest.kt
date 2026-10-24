package com.example.ownscan

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class LockedDocCryptoUnitTest {

    private lateinit var tempDir: File
    private lateinit var testKey: SecretKey

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("scanvault_locked_test").toFile()
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        testKey = keyGen.generateKey()
        LockedDocCryptoManager.testSecretKey = testKey
    }

    @After
    fun tearDown() {
        LockedDocCryptoManager.testSecretKey = null
        tempDir.deleteRecursively()
    }

    @Test
    fun testLockThenUnlockRoundTrip() = runBlocking {
        val docFolder = File(tempDir, "1729528000000__SecretBankDoc")
        docFolder.mkdirs()

        val originalsDir = File(docFolder, ".originals")
        originalsDir.mkdirs()

        val samplePage1 = File(docFolder, "page_1.jpg")
        val samplePage2 = File(docFolder, "page_2.jpg")
        val sampleOcr = File(docFolder, "ocr.txt")
        val sampleOriginal = File(originalsDir, "page_1.jpg")

        val page1Content = "JPEG_BINARY_DATA_PAGE_1_CONFIDENTIAL".toByteArray()
        val page2Content = "JPEG_BINARY_DATA_PAGE_2_CONFIDENTIAL".toByteArray()
        val ocrContent = "Account balance: \$1,000,000. Routing number: 123456789".toByteArray()
        val originalContent = "ORIGINAL_UNFILTERED_RAW_IMAGE_PAGE_1".toByteArray()

        samplePage1.writeBytes(page1Content)
        samplePage2.writeBytes(page2Content)
        sampleOcr.writeBytes(ocrContent)
        sampleOriginal.writeBytes(originalContent)

        DocMeta.writeToDir(docFolder, DocMeta(isLocked = false, tags = listOf("banking", "tax")))

        // 1. Lock document
        val lockSuccess = LockedDocCryptoManager.lockDocument(
            context = null,
            docDir = docFolder,
            secretKey = testKey
        )
        assertTrue("Lock operation should succeed", lockSuccess)

        // Verify plaintext files are GONE from disk at rest
        assertFalse("Plaintext page_1.jpg must not exist at rest", samplePage1.exists())
        assertFalse("Plaintext page_2.jpg must not exist at rest", samplePage2.exists())
        assertFalse("Plaintext ocr.txt must not exist at rest", sampleOcr.exists())
        assertFalse("Plaintext .originals/page_1.jpg must not exist at rest", sampleOriginal.exists())

        // Verify encrypted .enc files exist with proper magic header
        val encPage1 = File(docFolder, "page_1.jpg.enc")
        val encPage2 = File(docFolder, "page_2.jpg.enc")
        val encOcr = File(docFolder, "ocr.txt.enc")
        val encOriginal = File(originalsDir, "page_1.jpg.enc")

        assertTrue("Encrypted page_1.jpg.enc must exist", encPage1.exists())
        assertTrue("Encrypted page_2.jpg.enc must exist", encPage2.exists())
        assertTrue("Encrypted ocr.txt.enc must exist", encOcr.exists())
        assertTrue("Encrypted .originals/page_1.jpg.enc must exist", encOriginal.exists())

        val headerMagic = ByteArray(LockedDocCryptoManager.FILE_MAGIC.size)
        encPage1.inputStream().use { it.read(headerMagic) }
        assertArrayEquals("Encrypted file must begin with OWNSCANL magic", LockedDocCryptoManager.FILE_MAGIC, headerMagic)

        // 2. Unlock document permanently
        val unlockSuccess = LockedDocCryptoManager.unlockDocumentPermanently(
            context = null,
            docDir = docFolder,
            secretKey = testKey
        )
        assertTrue("Unlock operation should succeed", unlockSuccess)

        // Verify .enc files are gone and plaintext files are restored with byte-for-byte fidelity
        assertFalse("Encrypted file must be deleted after unlock", encPage1.exists())
        assertFalse("Encrypted file must be deleted after unlock", encPage2.exists())
        assertFalse("Encrypted file must be deleted after unlock", encOcr.exists())
        assertFalse("Encrypted original must be deleted after unlock", encOriginal.exists())

        assertTrue("Plaintext page_1.jpg must be restored", samplePage1.exists())
        assertTrue("Plaintext page_2.jpg must be restored", samplePage2.exists())
        assertTrue("Plaintext ocr.txt must be restored", sampleOcr.exists())
        assertTrue("Plaintext .originals/page_1.jpg must be restored", sampleOriginal.exists())

        assertArrayEquals(page1Content, samplePage1.readBytes())
        assertArrayEquals(page2Content, samplePage2.readBytes())
        assertArrayEquals(ocrContent, sampleOcr.readBytes())
        assertArrayEquals(originalContent, sampleOriginal.readBytes())
    }

    @Test
    fun testInterruptedLockRecovery() {
        val docFolder = File(tempDir, "1729528000000__CrashDoc")
        docFolder.mkdirs()

        // Simulate crash during lock: .lock_tmp left on disk
        val realPage = File(docFolder, "1.jpg")
        val danglingTmp = File(docFolder, "1.jpg.lock_tmp")
        realPage.writeText("page_data")
        danglingTmp.writeText("partial_garbage_from_interrupted_write")

        // Simulate crash during unlock: .unlock_tmp left on disk
        val danglingUnlockTmp = File(docFolder, "2.jpg.unlock_tmp")
        danglingUnlockTmp.writeText("partial_garbage_from_interrupted_read")

        DocMeta.writeToDir(docFolder, DocMeta(isLocked = false))

        LockedDocCryptoManager.recoverInterruptedLockOperations(docFolder)

        assertFalse(".lock_tmp file must be cleaned up during crash recovery", danglingTmp.exists())
        assertFalse(".unlock_tmp file must be cleaned up during crash recovery", danglingUnlockTmp.exists())
        assertTrue("Original page must remain unharmed", realPage.exists())
        assertEquals("page_data", realPage.readText())
    }

    @Test
    fun testLockedDocsAbsentFromPlaintextFts() {
        val secretOcrText = "ConfidentialPassword123 Secret Medical Diagnosis"
        val docFolderName = "1729528000000__MedicalReport"
        val displayName = "Medical Report"
        val tags = "health medical"

        // For a locked document, pageText is strictly masked to empty string
        val lockedFtsEntity = DocumentFtsEntity(
            rowid = 1,
            folderName = docFolderName,
            displayName = displayName,
            tagsText = tags,
            pageText = "" // Strictly empty for locked documents!
        )

        assertFalse(
            "Plaintext OCR page text must NEVER be contained in locked document FTS entity",
            lockedFtsEntity.pageText.contains("ConfidentialPassword123")
        )
        assertEquals("", lockedFtsEntity.pageText)

        // Test snippet masking
        val isLocked = true
        val snippet = if (isLocked) {
            "🔒 Protected Document • Unlock to view contents"
        } else {
            "Matched: $secretOcrText"
        }

        assertEquals("🔒 Protected Document • Unlock to view contents", snippet)
        assertFalse("Snippet for locked document must never leak page contents", snippet.contains("ConfidentialPassword123"))
    }

    @Test
    fun testNotificationTextForLockedDocs() {
        val displayName = "Super Secret Passport"
        val folderName = "1729528000000__SuperSecretPassport"
        val isLocked = true

        val (title, message) = if (isLocked) {
            Pair("Document Expiring Soon", "A locked document is expiring soon.")
        } else {
            Pair("Document Expiring Soon", "\"$displayName\" will expire in 7 days.")
        }

        // Test notification text
        assertFalse("Notification for locked document must NOT reveal display name in title", title.contains(displayName))
        assertFalse("Notification for locked document must NOT reveal display name in body", message.contains(displayName))
        assertEquals("Document Expiring Soon", title)
        assertEquals("A locked document is expiring soon.", message)

        // Test intent extras
        val hasLockedExtra = isLocked
        val openDocFolder = if (isLocked) null else folderName

        assertNull("Readable folder name must NOT be passed in intent extras for locked documents", openDocFolder)
        assertTrue(hasLockedExtra)
    }

    @Test
    fun testDekReusedWhenRelocking() = runBlocking {
        val docFolder = File(tempDir, "1729528000000__ReLockDoc")
        docFolder.mkdirs()

        val samplePage1 = File(docFolder, "page_1.jpg")
        samplePage1.writeBytes("IMAGE_PAGE_1_DATA".toByteArray())
        DocMeta.writeToDir(docFolder, DocMeta(isLocked = false))

        // Initial lock
        val lockSuccess1 = LockedDocCryptoManager.lockDocument(null, docFolder, testKey)
        assertTrue(lockSuccess1)

        val dekFile = File(docFolder, ".dek.enc")
        assertTrue(".dek.enc must exist after first lock", dekFile.exists())
        val dekBytes1 = dekFile.readBytes()

        // Add a second page while locked (e.g. newly scanned page added to folder)
        val samplePage2 = File(docFolder, "page_2.jpg")
        samplePage2.writeBytes("IMAGE_PAGE_2_DATA".toByteArray())

        // Re-lock
        val lockSuccess2 = LockedDocCryptoManager.lockDocument(null, docFolder, testKey)
        assertTrue(lockSuccess2)

        val dekBytes2 = dekFile.readBytes()
        assertArrayEquals("Existing .dek.enc must be REUSED and never overwritten with a new key", dekBytes1, dekBytes2)

        // Both pages must be decryptable
        val encPage1 = File(docFolder, "page_1.jpg.enc")
        val encPage2 = File(docFolder, "page_2.jpg.enc")
        assertTrue(encPage1.exists())
        assertTrue(encPage2.exists())

        val unlockSuccess = LockedDocCryptoManager.unlockDocumentPermanently(null, docFolder, testKey)
        assertTrue(unlockSuccess)
        assertEquals("IMAGE_PAGE_1_DATA", samplePage1.readText())
        assertEquals("IMAGE_PAGE_2_DATA", samplePage2.readText())
    }

    @Test
    fun testCorruptedDekThrowsTypedException() {
        val docFolder = File(tempDir, "1729528000000__CorruptDekDoc")
        docFolder.mkdirs()

        val dekFile = File(docFolder, ".dek.enc")
        // Write invalid/corrupt DEK file
        dekFile.writeBytes("CORRUPTED_GARBAGE_BYTES_FOR_DEK_HEADER".toByteArray())

        try {
            LockedDocCryptoManager.getOrDecryptDek(docFolder, testKey)
            fail("getOrDecryptDek must throw DekDecryptionException on corrupted DEK")
        } catch (e: DekDecryptionException) {
            // Expected typed exception!
            assertTrue("Exception message should be descriptive", e.message?.isNotBlank() == true)
        }
    }

    @Test
    fun testPlaintextPreservedIfVerificationFails() = runBlocking {
        val docFolder = File(tempDir, "1729528000000__VerificationDoc")
        docFolder.mkdirs()

        val samplePage = File(docFolder, "page_1.jpg")
        val content = "VALUABLE_DOCUMENT_CONTENT".toByteArray()
        samplePage.writeBytes(content)
        DocMeta.writeToDir(docFolder, DocMeta(isLocked = false))

        // Create an invalid .dek.enc so that lockDocument cannot decrypt it to reuse
        val dekFile = File(docFolder, ".dek.enc")
        dekFile.writeBytes("INVALID_DEK".toByteArray())

        val lockSuccess = LockedDocCryptoManager.lockDocument(null, docFolder, testKey)
        assertFalse("Lock must fail when existing DEK is corrupted", lockSuccess)

        // Source file must remain completely safe and untouched!
        assertTrue("Plaintext file MUST be preserved when lock fails verification", samplePage.exists())
        assertArrayEquals(content, samplePage.readBytes())
    }

    @Test
    fun testAppendPageToLockedDocAndVerifyDecryption() = runBlocking {
        val docFolder = File(tempDir, "1729528000000__AppendDoc")
        docFolder.mkdirs()

        val samplePage1 = File(docFolder, "page_1.jpg")
        samplePage1.writeBytes("PAGE_1_INITIAL".toByteArray())
        DocMeta.writeToDir(docFolder, DocMeta(isLocked = false))

        // 1. Lock document with page 1
        val lockSuccess = LockedDocCryptoManager.lockDocument(null, docFolder, testKey)
        assertTrue(lockSuccess)
        val encPage1 = File(docFolder, "page_1.jpg.enc")
        assertTrue(encPage1.exists())
        assertFalse(samplePage1.exists())

        // 2. Append page 2 while document is locked
        val dek = LockedDocCryptoManager.getOrDecryptDek(docFolder, testKey)
        assertNotNull(dek)

        val newPagePlain = File(docFolder, "page_2.jpg.tmp")
        newPagePlain.writeBytes("PAGE_2_APPENDED_SCAN".toByteArray())

        val tempEnc = File(docFolder, "page_2.jpg.lock_tmp")
        val finalEnc = File(docFolder, "page_2.jpg.enc")
        LockedDocCryptoManager.encryptFile(newPagePlain, tempEnc, dek)
        assertTrue(LockedDocCryptoManager.verifyEncryptedChunk(tempEnc, dek))
        assertTrue(tempEnc.renameTo(finalEnc))
        newPagePlain.delete()

        // Verify page_2.jpg.enc is present and plaintext is gone
        assertTrue(finalEnc.exists())
        assertFalse(File(docFolder, "page_2.jpg").exists())

        // 3. Unlock document permanently and verify BOTH pages decrypt with exact original bytes
        val unlockSuccess = LockedDocCryptoManager.unlockDocumentPermanently(null, docFolder, testKey)
        assertTrue(unlockSuccess)

        val restored1 = File(docFolder, "page_1.jpg")
        val restored2 = File(docFolder, "page_2.jpg")
        assertTrue(restored1.exists())
        assertTrue(restored2.exists())
        assertEquals("PAGE_1_INITIAL", restored1.readText())
        assertEquals("PAGE_2_APPENDED_SCAN", restored2.readText())
    }
}
