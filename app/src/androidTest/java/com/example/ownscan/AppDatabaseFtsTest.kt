package com.example.ownscan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppDatabaseFtsTest {

    private lateinit var db: AppDatabase
    private lateinit var ftsDao: DocumentFtsDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ftsDao = db.documentFtsDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun testFtsInsertAndSearch() {
        val doc1 = DocumentFtsEntity(
            rowid = 0,
            folderName = "folder_1",
            displayName = "Passport Scan",
            tagsText = "official identity passport",
            pageText = "Republic of India Passport Authority"
        )
        val doc2 = DocumentFtsEntity(
            rowid = 0,
            folderName = "folder_2",
            displayName = "Electricity Bill",
            tagsText = "utilities bill power",
            pageText = "Monthly power consumption 250 units"
        )

        ftsDao.insertOrUpdate(doc1)
        ftsDao.insertOrUpdate(doc2)

        val passportResults = ftsDao.search(sanitizeFtsQuery("passport"))
        assertEquals(1, passportResults.size)
        assertEquals("folder_1", passportResults[0].folderName)

        val powerResults = ftsDao.search(sanitizeFtsQuery("power"))
        assertEquals(1, powerResults.size)
        assertEquals("folder_2", powerResults[0].folderName)
    }

    @Test
    fun testFtsMultipleInsertsDoNotOverwriteDifferentFolders() {
        // Ensures rowid auto-generation preserves multiple documents in the FTS table
        val doc1 = DocumentFtsEntity(rowid = 0, folderName = "doc_1", displayName = "Doc 1", tagsText = "tag1", pageText = "text 1")
        val doc2 = DocumentFtsEntity(rowid = 0, folderName = "doc_2", displayName = "Doc 2", tagsText = "tag2", pageText = "text 2")
        val doc3 = DocumentFtsEntity(rowid = 0, folderName = "doc_3", displayName = "Doc 3", tagsText = "tag3", pageText = "text 3")

        ftsDao.insertOrUpdate(doc1)
        ftsDao.insertOrUpdate(doc2)
        ftsDao.insertOrUpdate(doc3)

        assertNotNull(ftsDao.getByFolderName("doc_1"))
        assertNotNull(ftsDao.getByFolderName("doc_2"))
        assertNotNull(ftsDao.getByFolderName("doc_3"))
    }

    @Test
    fun testFtsDeleteByFolderName() {
        val doc = DocumentFtsEntity(rowid = 0, folderName = "doc_del", displayName = "To Delete", tagsText = "temp", pageText = "disposable")
        ftsDao.insertOrUpdate(doc)
        assertNotNull(ftsDao.getByFolderName("doc_del"))

        ftsDao.deleteByFolderName("doc_del")
        assertNull(ftsDao.getByFolderName("doc_del"))
    }
}
