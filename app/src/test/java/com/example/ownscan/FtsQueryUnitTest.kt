package com.example.ownscan

import org.junit.Assert.*
import org.junit.Test

class FtsQueryUnitTest {

    @Test
    fun testSanitizeFtsQuery_basicWord() {
        val sanitized = sanitizeFtsQuery("receipt")
        assertEquals("\"receipt\"*", sanitized)
    }

    @Test
    fun testSanitizeFtsQuery_multipleWords() {
        val sanitized = sanitizeFtsQuery("tax invoice 2026")
        assertEquals("\"tax\"* \"invoice\"* \"2026\"*", sanitized)
    }

    @Test
    fun testSanitizeFtsQuery_quotesAndSpecialChars() {
        // Special characters that crash unescaped SQLite FTS4: ", *, :, ^, -, (, ), {, }, [, ]
        val sanitized = sanitizeFtsQuery(""" "invoice": *draft* -test (urgent) """)
        assertEquals("\"invoice\"* \"draft\"* \"test\"* \"urgent\"*", sanitized)
    }

    @Test
    fun testSanitizeFtsQuery_emptyOrWhitespaceOnly() {
        assertEquals("", sanitizeFtsQuery(""))
        assertEquals("", sanitizeFtsQuery("   "))
        assertEquals("", sanitizeFtsQuery("*** \"\" ::: ---"))
    }

    @Test
    fun testSanitizeFtsQuery_sqlInjectionAttempt() {
        val sanitized = sanitizeFtsQuery("'; DROP TABLE documents_fts; --")
        // Semi-colons and SQL keywords should be safely transformed into tokens without syntax explosion
        assertTrue(sanitized.contains("\"DROP\"*"))
        assertFalse(sanitized.contains(";"))
        assertFalse(sanitized.contains("--"))
    }
}
