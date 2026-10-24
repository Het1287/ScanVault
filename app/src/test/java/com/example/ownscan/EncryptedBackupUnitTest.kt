package com.example.ownscan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptedBackupUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var docDir1: File
    private lateinit var baseDocsDir: File
    private lateinit var userFoldersDir: File
    private val password = "StrongPassword123!"

    @Before
    fun setUp() {
        baseDocsDir = tempFolder.newFolder("documents")
        userFoldersDir = tempFolder.newFolder("folders")

        docDir1 = File(baseDocsDir, "1700000000000__TestDoc").apply { mkdirs() }
        File(docDir1, "page_1.jpg").writeText("JPEG_DATA_1")
        File(docDir1, "ocr.txt").writeText("Sample OCR text for invoice")
        DocMeta.writeToDir(docDir1, DocMeta(tags = listOf("invoice", "2026")))

        val originalsDir = File(docDir1, ".originals").apply { mkdirs() }
        File(originalsDir, "page_1.jpg").writeText("ORIGINAL_JPEG_DATA_1")
    }

    @Test
    fun testEncryptedBackupRoundTrip_correctPassword() = runBlocking {
        val backupFile = File(tempFolder.root, "backup.zip.enc")
        val manifestEntries = listOf(
            DocManifestEntry(
                folderName = docDir1.name,
                displayName = "TestDoc",
                parentFolder = null,
                meta = DocMeta.readFromDir(docDir1)
            )
        )

        // Backup
        val backupSuccess = FileOutputStream(backupFile).use { fos ->
            LocalEncryptedBackupManager.createEncryptedBackupStream(
                docFolders = listOf(docDir1),
                passwordChars = password.toCharArray(),
                destinationOut = fos,
                manifestEntries = manifestEntries
            )
        }
        assertTrue(backupSuccess)
        assertTrue(backupFile.length() > 0)

        // Restore to fresh target folder
        val restoreTargetBase = tempFolder.newFolder("restored_docs")
        val restoreUserFolders = tempFolder.newFolder("restored_user_folders")

        val restoreResult = FileInputStream(backupFile).use { fis ->
            LocalEncryptedBackupManager.restoreEncryptedBackupStream(
                sourceIn = fis,
                passwordChars = password.toCharArray(),
                baseDocsDir = restoreTargetBase,
                userFoldersDir = restoreUserFolders,
                conflictResolver = { _, _, _ -> ConflictResolution.KEEP_BOTH }
            )
        }

        assertTrue("Expected Success, got $restoreResult", restoreResult is EncryptedRestoreResult.Success)
        val success = restoreResult as EncryptedRestoreResult.Success
        assertEquals(1, success.restoredCount)
        assertEquals(0, success.badEntriesCount)

        val restoredDocDir = File(restoreTargetBase, docDir1.name)
        assertTrue("Restored doc dir must exist", restoredDocDir.exists())
        assertEquals("JPEG_DATA_1", File(restoredDocDir, "page_1.jpg").readText())
        assertEquals("Sample OCR text for invoice", File(restoredDocDir, "ocr.txt").readText())
        assertTrue("Originals dir must be preserved", File(restoredDocDir, ".originals/page_1.jpg").exists())
        assertEquals("ORIGINAL_JPEG_DATA_1", File(restoredDocDir, ".originals/page_1.jpg").readText())
    }

    @Test
    fun testEncryptedBackup_wrongPassword() = runBlocking {
        val backupFile = File(tempFolder.root, "backup_wrong_pwd.zip.enc")
        val manifestEntries = listOf(
            DocManifestEntry(docDir1.name, "TestDoc", null, DocMeta.readFromDir(docDir1))
        )

        FileOutputStream(backupFile).use { fos ->
            LocalEncryptedBackupManager.createEncryptedBackupStream(
                listOf(docDir1), password.toCharArray(), fos, manifestEntries
            )
        }

        val restoreTargetBase = tempFolder.newFolder("restore_wrong_pwd")
        val restoreResult = FileInputStream(backupFile).use { fis ->
            LocalEncryptedBackupManager.restoreEncryptedBackupStream(
                sourceIn = fis,
                passwordChars = "WrongPassword999!".toCharArray(),
                baseDocsDir = restoreTargetBase,
                userFoldersDir = tempFolder.newFolder("user_folders_wrong"),
                conflictResolver = { _, _, _ -> ConflictResolution.KEEP_BOTH }
            )
        }

        assertEquals(EncryptedRestoreResult.IncorrectPasswordOrCorrupted, restoreResult)
    }

    @Test
    fun testEncryptedBackup_truncatedFile() = runBlocking {
        val backupFile = File(tempFolder.root, "backup_trunc.zip.enc")
        val manifestEntries = listOf(
            DocManifestEntry(docDir1.name, "TestDoc", null, DocMeta.readFromDir(docDir1))
        )

        FileOutputStream(backupFile).use { fos ->
            LocalEncryptedBackupManager.createEncryptedBackupStream(
                listOf(docDir1), password.toCharArray(), fos, manifestEntries
            )
        }

        // Truncate file midway
        val rawBytes = backupFile.readBytes()
        val truncatedFile = File(tempFolder.root, "truncated.zip.enc")
        truncatedFile.writeBytes(rawBytes.copyOf(rawBytes.size / 2))

        val restoreTargetBase = tempFolder.newFolder("restore_trunc")
        val restoreResult = FileInputStream(truncatedFile).use { fis ->
            LocalEncryptedBackupManager.restoreEncryptedBackupStream(
                sourceIn = fis,
                passwordChars = password.toCharArray(),
                baseDocsDir = restoreTargetBase,
                userFoldersDir = tempFolder.newFolder("user_folders_trunc"),
                conflictResolver = { _, _, _ -> ConflictResolution.KEEP_BOTH }
            )
        }

        assertTrue(
            "Truncated file must fail closed as IncorrectPasswordOrCorrupted or Error, got: $restoreResult",
            restoreResult is EncryptedRestoreResult.IncorrectPasswordOrCorrupted || restoreResult is EncryptedRestoreResult.Error
        )
    }

    @Test
    fun testEncryptedBackup_tamperedHeader() = runBlocking {
        val backupFile = File(tempFolder.root, "backup_tamper.zip.enc")
        val manifestEntries = listOf(
            DocManifestEntry(docDir1.name, "TestDoc", null, DocMeta.readFromDir(docDir1))
        )

        FileOutputStream(backupFile).use { fos ->
            LocalEncryptedBackupManager.createEncryptedBackupStream(
                listOf(docDir1), password.toCharArray(), fos, manifestEntries
            )
        }

        // Tamper with salt byte (inside header/AAD)
        val rawBytes = backupFile.readBytes()
        // Header: [MAGIC: 8][VERSION: 1][SALT: 16]...
        rawBytes[10] = (rawBytes[10].toInt() xor 0xFF).toByte()
        val tamperedFile = File(tempFolder.root, "tampered.zip.enc")
        tamperedFile.writeBytes(rawBytes)

        val restoreTargetBase = tempFolder.newFolder("restore_tamper")
        val restoreResult = FileInputStream(tamperedFile).use { fis ->
            LocalEncryptedBackupManager.restoreEncryptedBackupStream(
                sourceIn = fis,
                passwordChars = password.toCharArray(),
                baseDocsDir = restoreTargetBase,
                userFoldersDir = tempFolder.newFolder("user_folders_tamper"),
                conflictResolver = { _, _, _ -> ConflictResolution.KEEP_BOTH }
            )
        }

        assertEquals(EncryptedRestoreResult.IncorrectPasswordOrCorrupted, restoreResult)
    }

    @Test
    fun testEncryptedBackup_zipSlipBlocked() = runBlocking {
        // Construct an encrypted backup containing a malicious zip-slip entry
        val zipSlipBackupFile = File(tempFolder.root, "zipslip.zip.enc")

        val salt = ByteArray(16) { 0x01 }
        val baseIv = ByteArray(12) { 0x02 }
        val iterations = LocalEncryptedBackupManager.PBKDF2_ITERATIONS
        val aad = ByteBuffer.allocate(8 + 1 + 16 + 4)
            .put("OWNSCANE".toByteArray(Charsets.US_ASCII))
            .put(LocalEncryptedBackupManager.FORMAT_VERSION)
            .put(salt)
            .putInt(iterations)
            .array()

        val passwordChars = password.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, iterations, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val keySpec = SecretKeySpec(keyBytes, "AES")

        FileOutputStream(zipSlipBackupFile).use { fos ->
            fos.write("OWNSCANE".toByteArray(Charsets.US_ASCII))
            fos.write(LocalEncryptedBackupManager.FORMAT_VERSION.toInt())
            fos.write(salt)
            fos.write(baseIv)
            fos.write(ByteBuffer.allocate(4).putInt(iterations).array())

            val chunkedOut = LocalEncryptedBackupManager.ChunkedGcmOutputStream(fos, keySpec, baseIv, aad)
            ZipOutputStream(chunkedOut).use { zos ->
                // Write malicious entry escaping folder
                val slipEntry = ZipEntry("doc_test/../../evil.txt")
                zos.putNextEntry(slipEntry)
                zos.write("malicious payload".toByteArray())
                zos.closeEntry()

                // Write valid entry
                val validEntry = ZipEntry("doc_test/page_1.jpg")
                zos.putNextEntry(validEntry)
                zos.write("valid image".toByteArray())
                zos.closeEntry()
            }
        }

        val restoreTargetBase = tempFolder.newFolder("restore_slip")
        val restoreResult = FileInputStream(zipSlipBackupFile).use { fis ->
            LocalEncryptedBackupManager.restoreEncryptedBackupStream(
                sourceIn = fis,
                passwordChars = password.toCharArray(),
                baseDocsDir = restoreTargetBase,
                userFoldersDir = tempFolder.newFolder("user_folders_slip"),
                conflictResolver = { _, _, _ -> ConflictResolution.KEEP_BOTH }
            )
        }

        println("Restore result: $restoreResult")
        if (restoreResult is EncryptedRestoreResult.Success) {
            println("badEntriesCount = ${restoreResult.badEntriesCount}, restoredCount = ${restoreResult.restoredCount}")
        }
        assertTrue(restoreResult is EncryptedRestoreResult.Success)
        val success = restoreResult as EncryptedRestoreResult.Success
        assertTrue("Zip-slip entry must be detected and counted as bad", success.badEntriesCount > 0)

        // Verify evil.txt was NOT written outside
        val outsideEvilFile = File(restoreTargetBase.parentFile, "evil.txt")
        assertFalse("Zip slip file must not exist on filesystem", outsideEvilFile.exists())
    }

    @Test
    fun testEncryptedBackup_v2BackwardCompatibility() = runBlocking {
        // Construct a v2 format backup archive (FORMAT_VERSION = 2)
        val v2BackupFile = File(tempFolder.root, "backup_v2.zip.enc")
        val salt = ByteArray(16) { 0x10 }
        val baseIv = ByteArray(12) { 0x20 }
        val iterations = 210000
        val aad = ByteBuffer.allocate(8 + 1 + 16 + 4)
            .put("OWNSCANE".toByteArray(Charsets.US_ASCII))
            .put(2.toByte())
            .put(salt)
            .putInt(iterations)
            .array()

        val passwordChars = password.toCharArray()
        val keySpec = LocalEncryptedBackupManager.deriveKey(passwordChars, salt, iterations)

        FileOutputStream(v2BackupFile).use { fos ->
            fos.write("OWNSCANE".toByteArray(Charsets.US_ASCII))
            fos.write(2) // Version 2
            fos.write(salt)
            fos.write(baseIv)
            fos.write(ByteBuffer.allocate(4).putInt(iterations).array())

            val chunkedOut = LocalEncryptedBackupManager.ChunkedGcmOutputStream(
                out = fos,
                keySpec = keySpec,
                baseIv = baseIv,
                aad = aad,
                formatVersion = 2.toByte()
            )
            ZipOutputStream(chunkedOut).use { zos ->
                val entry = ZipEntry("doc_v2/page_1.jpg")
                zos.putNextEntry(entry)
                zos.write("legacy v2 content".toByteArray())
                zos.closeEntry()
            }
        }

        // Restore v2 backup in current app
        val restoreTargetBase = tempFolder.newFolder("restore_v2")
        val restoreResult = FileInputStream(v2BackupFile).use { fis ->
            LocalEncryptedBackupManager.restoreEncryptedBackupStream(
                sourceIn = fis,
                passwordChars = password.toCharArray(),
                baseDocsDir = restoreTargetBase,
                userFoldersDir = tempFolder.newFolder("user_folders_v2"),
                conflictResolver = { _, _, _ -> ConflictResolution.KEEP_BOTH }
            )
        }

        assertTrue("v2 backup must restore successfully", restoreResult is EncryptedRestoreResult.Success)
        val restoredDocDir = File(restoreTargetBase, "doc_v2")
        assertTrue(File(restoredDocDir, "page_1.jpg").exists())
        assertEquals("legacy v2 content", File(restoredDocDir, "page_1.jpg").readText())
    }

    @Test
    fun testEncryptedBackup_dekRewrapOnNewDevice() = runBlocking {
        // Phone 1: setup master key 1
        val keyGen = javax.crypto.KeyGenerator.getInstance("AES")
        keyGen.init(256)
        val phone1Key = keyGen.generateKey()
        val phone2Key = keyGen.generateKey()

        LockedDocCryptoManager.testSecretKey = phone1Key

        val lockedDocDir = File(baseDocsDir, "1700000000001__LockedDoc").apply { mkdirs() }
        File(lockedDocDir, "page_1.jpg").writeText("SECRET_PAGE_CONTENT")
        DocMeta.writeToDir(lockedDocDir, DocMeta(isLocked = true))

        // Lock doc under Phone 1's master key
        val lockSuccess = LockedDocCryptoManager.lockDocument(null, lockedDocDir, phone1Key)
        assertTrue(lockSuccess)
        val dekFile = File(lockedDocDir, ".dek.enc")
        assertTrue(dekFile.exists() && dekFile.length() > 0)

        // Create encrypted backup under Phone 1
        val backupFile = File(tempFolder.root, "backup_with_locked.zip.enc")
        val manifestEntries = listOf(
            DocManifestEntry(lockedDocDir.name, "LockedDoc", null, DocMeta.readFromDir(lockedDocDir))
        )
        val backupSuccess = FileOutputStream(backupFile).use { fos ->
            LocalEncryptedBackupManager.createEncryptedBackupStream(
                docFolders = listOf(lockedDocDir),
                passwordChars = password.toCharArray(),
                destinationOut = fos,
                manifestEntries = manifestEntries
            )
        }
        assertTrue(backupSuccess)

        // Phone 2: switch to new master key (Phone 2 has DIFFERENT master key)
        LockedDocCryptoManager.testSecretKey = phone2Key
        val restoreTargetBase = tempFolder.newFolder("restore_phone2")
        val restoreResult = FileInputStream(backupFile).use { fis ->
            LocalEncryptedBackupManager.restoreEncryptedBackupStream(
                sourceIn = fis,
                passwordChars = password.toCharArray(),
                baseDocsDir = restoreTargetBase,
                userFoldersDir = tempFolder.newFolder("user_folders_phone2"),
                conflictResolver = { _, _, _ -> ConflictResolution.KEEP_BOTH }
            )
        }
        assertTrue(restoreResult is EncryptedRestoreResult.Success)

        // Verify Phone 2 can decrypt restored doc using Phone 2's master key!
        val restoredDocDir = File(restoreTargetBase, lockedDocDir.name)
        assertTrue(restoredDocDir.exists())
        val restoredDek = LockedDocCryptoManager.getOrDecryptDek(restoredDocDir, phone2Key)
        assertNotNull(restoredDek)

        // Unlock doc on Phone 2
        val unlockSuccess = LockedDocCryptoManager.unlockDocumentPermanently(null, restoredDocDir, phone2Key)
        assertTrue(unlockSuccess)
        val decryptedPage = File(restoredDocDir, "page_1.jpg")
        assertTrue(decryptedPage.exists())
        assertEquals("SECRET_PAGE_CONTENT", decryptedPage.readText())

        LockedDocCryptoManager.testSecretKey = null
    }

    @Test
    fun testDriveRestore_zipBombEntryDefense() {
        val zipBombFile = File(tempFolder.root, "zipbomb_entries.zip")
        ZipOutputStream(FileOutputStream(zipBombFile)).use { zos ->
            for (i in 1..5005) {
                val entry = ZipEntry("entry_$i.txt")
                zos.putNextEntry(entry)
                zos.write("data".toByteArray())
                zos.closeEntry()
            }
        }

        val targetDir = tempFolder.newFolder("zipbomb_target")
        try {
            DriveBackupManager.unzip(zipBombFile, targetDir)
            fail("Expected SecurityException for zip bomb with >5000 entries")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Zip bomb defense") == true)
        }
    }
}
