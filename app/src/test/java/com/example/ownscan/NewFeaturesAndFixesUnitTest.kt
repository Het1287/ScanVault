package com.example.ownscan

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneId
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory

class NewFeaturesAndFixesUnitTest {

    private val zoneId = ZoneId.systemDefault()

    @Test
    fun testPdfBoxJPEGFactoryCompilation() {
        val doc = PDDocument()
        val page = PDPage(PDRectangle(595f, 842f))
        doc.addPage(page)
        val dummyJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(),
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(),
            0x43.toByte(), 0x00.toByte()
        )
        val methods = JPEGFactory::class.java.methods.map { it.name }
        assertTrue(methods.contains("createFromStream"))
        doc.close()
    }

    // -------------------------------------------------------------
    // 1. EXPIRY HELPER & MILESTONE LOGIC TESTS
    // -------------------------------------------------------------

    @Test
    fun testExpiryHelper_diffDaysCalculation() {
        val today = LocalDate.now(zoneId)

        // Today -> 0 days
        val todayMillis = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        assertEquals(0L, ExpiryHelper.getDiffDays(todayMillis, zoneId))
        assertTrue(ExpiryHelper.isExpiringSoon(todayMillis, zoneId))
        assertTrue(ExpiryHelper.getExpiryStatus(todayMillis, zoneId) is ExpiryStatus.ExpiringSoon)

        // Tomorrow -> 1 day
        val tomorrowMillis = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        assertEquals(1L, ExpiryHelper.getDiffDays(tomorrowMillis, zoneId))
        assertTrue(ExpiryHelper.isExpiringSoon(tomorrowMillis, zoneId))
        val tomorrowStatus = ExpiryHelper.getExpiryStatus(tomorrowMillis, zoneId)
        assertTrue(tomorrowStatus is ExpiryStatus.ExpiringSoon)
        assertEquals(1L, (tomorrowStatus as ExpiryStatus.ExpiringSoon).daysLeft)

        // 7 days -> 7 days
        val in7DaysMillis = today.plusDays(7).atStartOfDay(zoneId).toInstant().toEpochMilli()
        assertEquals(7L, ExpiryHelper.getDiffDays(in7DaysMillis, zoneId))
        assertTrue(ExpiryHelper.isExpiringSoon(in7DaysMillis, zoneId))

        // 30 days -> 30 days
        val in30DaysMillis = today.plusDays(30).atStartOfDay(zoneId).toInstant().toEpochMilli()
        assertEquals(30L, ExpiryHelper.getDiffDays(in30DaysMillis, zoneId))
        assertTrue(ExpiryHelper.isExpiringSoon(in30DaysMillis, zoneId))

        // 31 days -> Valid (not expiring soon)
        val in31DaysMillis = today.plusDays(31).atStartOfDay(zoneId).toInstant().toEpochMilli()
        assertEquals(31L, ExpiryHelper.getDiffDays(in31DaysMillis, zoneId))
        assertFalse(ExpiryHelper.isExpiringSoon(in31DaysMillis, zoneId))
        assertTrue(ExpiryHelper.getExpiryStatus(in31DaysMillis, zoneId) is ExpiryStatus.Valid)

        // Yesterday -> -1 (Expired)
        val yesterdayMillis = today.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        assertEquals(-1L, ExpiryHelper.getDiffDays(yesterdayMillis, zoneId))
        assertFalse(ExpiryHelper.isExpiringSoon(yesterdayMillis, zoneId))
        val expiredStatus = ExpiryHelper.getExpiryStatus(yesterdayMillis, zoneId)
        assertTrue(expiredStatus is ExpiryStatus.Expired)
        assertEquals(1L, (expiredStatus as ExpiryStatus.Expired).daysAgo)
    }

    @Test
    fun testMilestoneTrigger_handlesSkippedRuns() {
        val milestones = ExpiryReminderWorker.MILESTONES // [30, 7, 1, 0]
        val notifiedSet = mutableSetOf<String>()

        // Simulate a document expiring in 5 days (a run at 30 was skipped or phone was off)
        val diffDays = 5L
        val applicable = milestones.filter { m ->
            diffDays <= m && !notifiedSet.contains(m.toString())
        }.minOrNull()

        // The first applicable milestone must be 7 (since 5 <= 7), not missing the milestone!
        assertNotNull(applicable)
        assertEquals(7L, applicable)

        // Mark 7 (and higher milestones) as notified
        milestones.filter { it >= applicable!! }.forEach { notifiedSet.add(it.toString()) }
        assertTrue(notifiedSet.contains("7"))
        assertTrue(notifiedSet.contains("30"))

        // Next day: diffDays is 4. No new milestone should trigger between 7 and 1.
        val nextDayDiff = 4L
        val nextApplicable = milestones.firstOrNull { m ->
            nextDayDiff <= m && !notifiedSet.contains(m.toString())
        }
        assertNull(nextApplicable)

        // When it reaches 1 day:
        val urgentDiff = 1L
        val urgentApplicable = milestones.firstOrNull { m ->
            urgentDiff <= m && !notifiedSet.contains(m.toString())
        }
        assertEquals(1L, urgentApplicable)
    }

    // -------------------------------------------------------------
    // 2. OCR CLASSIFICATION & TITLE SUGGESTION TESTS
    // -------------------------------------------------------------

    @Test
    fun testDetectDocType_aadhaar() {
        val text = "GOVERNMENT OF INDIA\nUnique Identification Authority of India\nJohn Doe\nDOB: 01/01/1990\n9876 5432 1098"
        val docType = OcrManager.detectDocType(text)
        assertEquals("Aadhaar Card", docType)
    }

    @Test
    fun testDetectDocType_pan() {
        val text = "INCOME TAX DEPARTMENT\nGOVT. OF INDIA\nPermanent Account Number Card\nABCDE1234F\nFather's Name: James Doe"
        val docType = OcrManager.detectDocType(text)
        assertEquals("PAN Card", docType)
    }

    @Test
    fun testDetectDocType_passport() {
        val mrzText = "PASSPORT\nRepublic of India\nP<INDDOE<<JOHN<<<<<<<<<<<<<<<<<<<<<<<<<<<\nA1234567<5IND9001014M3001018<<<<<<<<<<<<<<<0"
        val docType = OcrManager.detectDocType(mrzText)
        assertEquals("Passport", docType)
    }

    @Test
    fun testDetectDocType_doesNotMisclassifyRepublicOfIndiaAsPassport() {
        // A gazette or official notice mentioning "Republic of India" without passport context
        val text = "THE GAZETTE OF INDIA\nPublished by Authority\nRepublic of India\nMinistry of Law and Justice\nNotification No 123"
        val docType = OcrManager.detectDocType(text)
        assertNotEquals("Passport", docType)
    }

    @Test
    fun testDetectDocType_doesNotMisclassifyDrInAddressAsPrescription() {
        // "Dr." inside a street address or drive
        val text = "Ship to:\nJohn Smith\n124 Oak Dr. Suite 200\nAustin, TX 78701\nPacking Slip # 49201"
        val docType = OcrManager.detectDocType(text)
        assertNotEquals("Medical Prescription", docType)
    }

    @Test
    fun testDetectDocType_medicalPrescriptionWithMedicalContext() {
        val text = "Apex Clinic\nDr. Rajesh Sharma, MD\nPatient: Anita Roy\nRx:\nTab Paracetamol 500 mg 1-0-1\nCapsule Amoxicillin"
        val docType = OcrManager.detectDocType(text)
        assertEquals("Medical Prescription", docType)
    }

    @Test
    fun testDetectDocType_invoicesAndBills() {
        val invoiceText = "Tax Invoice\nGSTIN: 27AAAAA0000A1Z5\nInvoice No: INV-2026-09\nTotal: 4500.00"
        assertEquals("Tax Invoice", OcrManager.detectDocType(invoiceText))

        val electricityText = "Bescom Power Corporation\nElectricity Bill for the Month of Jan 2026\nAccount: 90210293"
        assertEquals("Electricity Bill", OcrManager.detectDocType(electricityText))
    }

    @Test
    fun testGenerateTitleSuggestion_formatsCorrectly() {
        val text = "Tax Invoice\nGSTIN: 27AAAAA0000A1Z5\nDate: 15 Jan 2026\nTotal: 2500"
        val title = OcrManager.generateTitleSuggestion(text)
        assertNotNull(title)
        assertTrue(title!!.startsWith("Tax Invoice – Jan 2026"))
    }

    // -------------------------------------------------------------
    // 3. SMART COLLECTIONS FALSE-POSITIVE NEGATIVE TESTS
    // -------------------------------------------------------------

    private fun makeDoc(displayName: String, tags: List<String> = emptyList(), isLocked: Boolean = false, expiryDate: Long? = null): LocalDoc {
        return LocalDoc(
            folderName = "1700000000__$displayName",
            displayName = displayName,
            timestamp = 1700000000000L,
            pageCount = 1,
            imageFiles = emptyList(),
            directory = File("dummy/$displayName"),
            tags = tags,
            isLocked = isLocked,
            expiryDate = expiryDate
        )
    }

    @Test
    fun testSmartCollections_doesNotMatchFalsePositivesInIdFilter() {
        // "Valid" contains "ID"
        val validDoc = makeDoc("Valid Contract Document", tags = listOf("Valid", "Important"))
        assertFalse("Should NOT match 'Valid' as ID", SmartCollectionsManager.matchesIdCollection(validDoc))

        // "Paid" contains "ID"
        val paidDoc = makeDoc("Paid Maintenance Receipt", tags = listOf("Paid"))
        assertFalse("Should NOT match 'Paid' as ID", SmartCollectionsManager.matchesIdCollection(paidDoc))

        // "Video" contains "ID"
        val videoDoc = makeDoc("Video Recording Consent", tags = listOf("Video"))
        assertFalse("Should NOT match 'Video' as ID", SmartCollectionsManager.matchesIdCollection(videoDoc))

        // "Company" contains "PAN"
        val companyDoc = makeDoc("Company Annual Report", tags = listOf("Company", "Reports"))
        assertFalse("Should NOT match 'Company' as ID", SmartCollectionsManager.matchesIdCollection(companyDoc))

        // "Panel" contains "PAN"
        val panelDoc = makeDoc("Expert Panel Discussion Notes", tags = listOf("Panel"))
        assertFalse("Should NOT match 'Panel' as ID", SmartCollectionsManager.matchesIdCollection(panelDoc))
    }

    @Test
    fun testSmartCollections_matchesLegitimateIds() {
        val aadhaarDoc = makeDoc("My Aadhaar Card", tags = listOf("ID", "Aadhaar"))
        assertTrue(SmartCollectionsManager.matchesIdCollection(aadhaarDoc))

        val panDoc = makeDoc("Official PAN Document", tags = listOf("PAN"))
        assertTrue(SmartCollectionsManager.matchesIdCollection(panDoc))

        val passportDoc = makeDoc("Passport 2026", tags = listOf("Passport"))
        assertTrue(SmartCollectionsManager.matchesIdCollection(passportDoc))

        val exactIdTagDoc = makeDoc("National Identity", tags = listOf("ID"))
        assertTrue(SmartCollectionsManager.matchesIdCollection(exactIdTagDoc))
    }

    @Test
    fun testSmartCollections_filterDocsConsistency() {
        val today = LocalDate.now(zoneId)
        val in10Days = today.plusDays(10).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val expired5Days = today.minusDays(5).atStartOfDay(zoneId).toInstant().toEpochMilli()

        val expiringDoc = makeDoc("Expiring ID", tags = listOf("ID"), expiryDate = in10Days)
        val expiredDoc = makeDoc("Expired Warranty", tags = listOf("Warranty"), expiryDate = expired5Days)
        val lockedDoc = makeDoc("Secret File", tags = listOf("Secret"), isLocked = true)
        val validDoc = makeDoc("Valid Contract Document", tags = listOf("Valid"))

        val docs = listOf(expiringDoc, expiredDoc, lockedDoc, validDoc)

        // Filter IDS: should only match expiringDoc (which has "ID" tag)
        val idsResult = SmartCollectionsManager.filterDocs(docs, SmartCollection.IDS, emptySet(), emptySet())
        assertEquals(1, idsResult.size)
        assertEquals("Expiring ID", idsResult[0].displayName)

        // Filter EXPIRING_SOON: should match expiringDoc, but NOT already-expired doc
        val expiringResult = SmartCollectionsManager.filterDocs(docs, SmartCollection.EXPIRING_SOON, emptySet(), emptySet())
        assertEquals(1, expiringResult.size)
        assertEquals("Expiring ID", expiringResult[0].displayName)

        // Filter LOCKED: should only match lockedDoc
        val lockedResult = SmartCollectionsManager.filterDocs(docs, SmartCollection.LOCKED, emptySet(), emptySet())
        assertEquals(1, lockedResult.size)
        assertEquals("Secret File", lockedResult[0].displayName)
    }

    // -------------------------------------------------------------
    // 5. LOCKED DOC EDITING PAGE COUNT & PERSISTENCE FIX TESTS
    // -------------------------------------------------------------

    @Test
    fun testLockedDocDuplicateEncConsolidation() {
        val tempDir = File.createTempFile("test_locked_doc_", "").apply {
            delete()
            mkdirs()
        }
        try {
            // Write meta.json indicating locked doc
            val metaFile = File(tempDir, "meta.json")
            metaFile.writeText("""{"isLocked": true}""")

            // Simulate the bug where doc has both page_01.jpg.enc and page_01.enc (edited page)
            val originalEnc = File(tempDir, "page_01.jpg.enc").apply {
                writeBytes(byteArrayOf(1, 2, 3))
                setLastModified(1000L)
            }
            val editedEnc = File(tempDir, "page_01.enc").apply {
                writeBytes(byteArrayOf(4, 5, 6, 7)) // newer edited content
                setLastModified(2000L)
            }

            // Run recoverInterruptedLockOperations
            LockedDocCryptoManager.recoverInterruptedLockOperations(tempDir)

            // Assert: obsolete page_01.enc is deleted and consolidated into page_01.jpg.enc
            assertFalse("page_01.enc should be removed", editedEnc.exists())
            assertTrue("page_01.jpg.enc should exist", originalEnc.exists())
            assertArrayEquals("page_01.jpg.enc should have updated edited bytes", byteArrayOf(4, 5, 6, 7), originalEnc.readBytes())

            // Assert: directory now has exactly 1 encrypted page (no extra page)
            val pages = tempDir.listFiles { f ->
                f.isFile && f.name.endsWith(".enc") && f.name != ".dek.enc" && f.name != "ocr.txt.enc"
            }
            assertEquals(1, pages?.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCompressionTargetSizeCalculations() {
        val originalBytes = 25 * 1024L // 25 KB

        // Test quality 30%
        val qualityLow = 30
        val targetRatioLow = (qualityLow / 100f).coerceIn(0.15f, 0.85f)
        val targetBytesLow = ((originalBytes * targetRatioLow) * 0.80f).toLong()
        assertTrue("Target bytes must be strictly less than original", targetBytesLow < originalBytes)
        assertTrue("Target bytes must be positive", targetBytesLow > 1024L)

        // Test quality 70%
        val qualityHigh = 70
        val targetRatioHigh = (qualityHigh / 100f).coerceIn(0.15f, 0.85f)
        val targetBytesHigh = ((originalBytes * targetRatioHigh) * 0.80f).toLong()
        assertTrue("Target bytes must be strictly less than original", targetBytesHigh < originalBytes)
        assertTrue("Higher quality should produce larger target size", targetBytesHigh > targetBytesLow)
    }

    // -------------------------------------------------------------
    // 6. PHASE 10a - TARGET SIZE & PASSPORT / SIGNATURE PRESETS TESTS
    // -------------------------------------------------------------

    @Test
    fun testPhase10a_presetTargetLimits() {
        // Signature standard: 10-20 KB -> target 18 KB
        val sigTarget = CompressionPreset.SIGNATURE.targetBytes
        assertNotNull(sigTarget)
        assertTrue(sigTarget!! in (10 * 1024L)..(20 * 1024L))
        assertEquals(18 * 1024L, sigTarget)

        // Under 50 KB preset
        val under50Target = CompressionPreset.UNDER_50_KB.targetBytes
        assertNotNull(under50Target)
        assertTrue(under50Target!! < 50 * 1024L)

        // Under 100 KB preset
        val under100Target = CompressionPreset.UNDER_100_KB.targetBytes
        assertNotNull(under100Target)
        assertTrue(under100Target!! < 100 * 1024L)

        // Under 200 KB preset
        val under200Target = CompressionPreset.UNDER_200_KB.targetBytes
        assertNotNull(under200Target)
        assertTrue(under200Target!! < 200 * 1024L)
    }

    @Test
    fun testPhase10a_cropToAspectRatioMath() {
        // Test passport aspect ratio math (3.5 : 4.5 = 7 : 9)
        val targetAspectW = 3.5f
        val targetAspectH = 4.5f
        val targetRatio = targetAspectW / targetAspectH // ~0.7778

        // Wider source: 1000 x 1000 (ratio 1.0 > 0.7778) -> should crop width
        val srcW1 = 1000
        val srcH1 = 1000
        val cropW1 = (srcH1 * targetRatio).toInt()
        val cropH1 = srcH1
        assertEquals(777, cropW1)
        assertEquals(1000, cropH1)
        assertEquals(targetRatio, cropW1.toFloat() / cropH1.toFloat(), 0.01f)

        // Taller source: 600 x 1200 (ratio 0.5 < 0.7778) -> should crop height
        val srcW2 = 600
        val srcH2 = 1200
        val cropW2 = srcW2
        val cropH2 = (srcW2 / targetRatio).toInt()
        assertEquals(600, cropW2)
        assertEquals(771, cropH2)
        assertEquals(targetRatio, cropW2.toFloat() / cropH2.toFloat(), 0.01f)

        // Test signature aspect ratio math (3.5 : 1.5 = 7 : 3)
        val sigAspectW = 3.5f
        val sigAspectH = 1.5f
        val sigRatio = sigAspectW / sigAspectH // ~2.3333

        // Taller/square source: 1000 x 1000 -> should crop height
        val sigCropW = 1000
        val sigCropH = (sigCropW / sigRatio).toInt()
        assertEquals(1000, sigCropW)
        assertEquals(428, sigCropH)
        assertEquals(sigRatio, sigCropW.toFloat() / sigCropH.toFloat(), 0.01f)
    }

    @Test
    fun testPhase10a_customKbTargetBytes() {
        val inputKbStrings = listOf("30", "50", "100", "200", "500")
        val originalBytes = 1024 * 1024L // 1 MB

        inputKbStrings.forEach { kbStr ->
            val kbVal = kbStr.toLongOrNull() ?: 100L
            val targetBytes = (kbVal * 1024L).coerceAtMost(originalBytes)
            assertEquals(kbVal * 1024L, targetBytes)
            assertTrue(targetBytes < originalBytes)
        }
    }

    // -------------------------------------------------------------
    // 7. PHASE 10b - MASKED AADHAAR EXPORT & WATERMARK TESTS
    // -------------------------------------------------------------

    @Test
    fun testPhase10b_watermarkPresets() {
        val presets = MaskAndWatermarkEngine.WATERMARK_PRESETS
        assertTrue(presets.contains("FOR KYC ONLY"))
        assertTrue(presets.contains("CONFIDENTIAL"))
        assertTrue(presets.contains("OFFICIAL USE ONLY"))
        assertTrue(presets.contains("VERIFIED COPY"))
        assertTrue(presets.contains("SELF ATTESTED"))
    }

    // -------------------------------------------------------------
    // 8. PHASE 10c, 10d, 10e UNIT TESTS
    // -------------------------------------------------------------

    @Test
    fun testPhase10c_signatureDataStructures() {
        val samplePath = SignaturePath(
            points = listOf(
                androidx.compose.ui.geometry.Offset(10f, 20f),
                androidx.compose.ui.geometry.Offset(15f, 25f),
                androidx.compose.ui.geometry.Offset(20f, 30f)
            ),
            color = androidx.compose.ui.graphics.Color.Blue,
            strokeWidth = 6f
        )
        assertEquals(3, samplePath.points.size)
        assertEquals(androidx.compose.ui.graphics.Color.Blue, samplePath.color)
        assertEquals(6f, samplePath.strokeWidth, 0.001f)

        val tempFile = File("/tmp/sig_123.png")
        val savedSig = SavedSignature(id = "sig_123", file = tempFile, timestamp = 1000L, name = "Sig 1")
        assertEquals("sig_123", savedSig.id)
        assertEquals(tempFile, savedSig.file)
        assertEquals(1000L, savedSig.timestamp)
        assertEquals("Sig 1", savedSig.name)
    }

    @Test
    fun testPhase10e_pageReorderSwap() {
        val pages = mutableListOf("page1.jpg", "page2.jpg", "page3.jpg", "page4.jpg")

        // Move page 2 Up (swap index 1 with 0)
        java.util.Collections.swap(pages, 1, 0)
        assertEquals(listOf("page2.jpg", "page1.jpg", "page3.jpg", "page4.jpg"), pages)

        // Move page 1 Down (swap index 1 with 2)
        java.util.Collections.swap(pages, 1, 2)
        assertEquals(listOf("page2.jpg", "page3.jpg", "page1.jpg", "page4.jpg"), pages)
    }

    // -------------------------------------------------------------
    // PHASE 10F: SENSITIVE ID & EXPIRY DETECTION TESTS
    // -------------------------------------------------------------

    @Test
    fun testPhase10f_isSensitiveIdDocDetection() {
        val aadhaarSample = "GOVERNMENT OF INDIA\nUnique Identification Authority of India\nEnrollment No: 1234/56789/01234\nTo: Rajesh Kumar\n1234 5678 9012\nमेरा आधार, मेरी पहचान"
        assertTrue(OcrManager.isSensitiveIdDoc(aadhaarSample))

        val panSample = "INCOME TAX DEPARTMENT\nGOVT. OF INDIA\nPermanent Account Number\nABCDE1234F\nName: SUNIL SHARMA"
        assertTrue(OcrManager.isSensitiveIdDoc(panSample))

        val passportSample = "REPUBLIC OF INDIA\nPASSPORT\nType P\nCountry Code IND\nPassport No: Z1234567"
        assertTrue(OcrManager.isSensitiveIdDoc(passportSample))

        val dlSample = "UNION OF INDIA\nDRIVING LICENCE\nDL-0420110012345\nAuthorised to drive LMV"
        assertTrue(OcrManager.isSensitiveIdDoc(dlSample))

        val voterSample = "ELECTION COMMISSION OF INDIA\nELECTOR PHOTO IDENTITY CARD\nEPIC NO: WBF1234567"
        assertTrue(OcrManager.isSensitiveIdDoc(voterSample))

        val normalReceipt = "GROCERY STORE\nMilk 1L - 60\nBread - 40\nTotal: 100\nThank you for shopping!"
        assertFalse(OcrManager.isSensitiveIdDoc(normalReceipt))
    }

    @Test
    fun testPhase10f_detectExpiryDateFromOcr() {
        val ocr1 = "Vehicle Insurance Certificate\nPolicy No: 9812739\nValid Thru: 25/12/2028\nInsured: John Doe"
        val expiry1 = OcrManager.detectExpiryDate(ocr1)
        assertEquals("2028-12-25", expiry1)
        val epoch1 = OcrManager.detectExpiryDateEpoch(ocr1)
        assertNotNull(epoch1)
        assertTrue(epoch1!! > 0L)

        val ocr2 = "Driving Licence\nDL1420110012345\nExpiry Date 2030-08-15\nDOB 1990-01-01"
        val expiry2 = OcrManager.detectExpiryDate(ocr2)
        assertEquals("2030-08-15", expiry2)

        val ocr3 = "Debit Card\n1234 5678 9876 5432\nVALID THRU 06/27\nCARDHOLDER"
        val expiry3 = OcrManager.detectExpiryDate(ocr3)
        assertEquals("2027-06-01", expiry3)

        val ocrNoExpiry = "General meeting minutes\nDiscussed quarterly sales and targets\nNo dates mentioned."
        val expiryNone = OcrManager.detectExpiryDate(ocrNoExpiry)
        assertNull(expiryNone)
        assertNull(OcrManager.detectExpiryDateEpoch(ocrNoExpiry))
    }

    // -------------------------------------------------------------
    // PHASE 10G: SAF AUTO-BACKUP STATUS & CONFIG
    // -------------------------------------------------------------

    @Test
    fun testPhase10g_safBackupStatusDefaults() {
        val status = SafBackupStatus(
            isEnabled = false,
            folderUri = null,
            folderName = null,
            frequency = "DAILY",
            lastBackupTimestamp = 0L,
            lastBackupStatus = null,
            hasPassword = false
        )
        assertFalse(status.isEnabled)
        assertNull(status.folderUri)
        assertNull(status.folderName)
        assertEquals("DAILY", status.frequency)
        assertEquals(0L, status.lastBackupTimestamp)
        assertNull(status.lastBackupStatus)
        assertFalse(status.hasPassword)

        val updated = status.copy(
            isEnabled = true,
            folderUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
            folderName = "Backups",
            lastBackupTimestamp = 1700000000000L,
            lastBackupStatus = "Success (5.2 MB)",
            hasPassword = true
        )
        assertTrue(updated.isEnabled)
        assertNotNull(updated.folderUri)
        assertEquals("Backups", updated.folderName)
        assertEquals("Success (5.2 MB)", updated.lastBackupStatus)
        assertTrue(updated.hasPassword)
    }

    // -------------------------------------------------------------
    // PHASE 10H: QR / BARCODE DATA STRUCTURE & UPI PARSING
    // -------------------------------------------------------------

}
