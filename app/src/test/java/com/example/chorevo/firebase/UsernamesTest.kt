package com.example.chorevo.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernamesTest {

    @Test
    fun normalize_lowercasesAndKeepsOnlyAllowedCharacters() {
        assertEquals("ryan_wong", Usernames.normalize("@Ryan Wong"))
        assertEquals("ryan_wong", Usernames.normalize("ryan-wong"))
        assertEquals("ryan_w", Usernames.normalize("ryan.w"))
        assertEquals("ana", Usernames.normalize("Ana!#"))
        assertEquals(20, Usernames.normalize("a".repeat(40)).length)
    }

    @Test
    fun isValid_andProblem_matchTheRules() {
        assertTrue(Usernames.isValid("abc"))
        assertTrue(Usernames.isValid("dance_crew_2024"))
        assertFalse(Usernames.isValid("ab"))
        assertFalse(Usernames.isValid("Abc"))
        assertFalse(Usernames.isValid("a".repeat(21)))
        assertNotNull(Usernames.problem("ab"))
        assertNull(Usernames.problem("abc"))
    }

    @Test
    fun suggestionFor_usesDisplayName_orFallsBack() {
        assertEquals("ryan_wong", Usernames.suggestionFor("Ryan  Wong"))
        assertEquals("dancer", Usernames.suggestionFor("李"))
        assertEquals("dancer", Usernames.suggestionFor(null))
        assertTrue(Usernames.isValid(Usernames.suggestionFor("A very long display name indeed")))
    }

    @Test
    fun withNumber_staysWithinTheLimit() {
        assertEquals("ryan42", Usernames.withNumber("ryan", 42))
        val long = Usernames.withNumber("a".repeat(20), 1234)
        assertEquals(20, long.length)
        assertTrue(long.endsWith("1234"))
    }
}
