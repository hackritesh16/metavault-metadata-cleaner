package com.metavault.cleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveNamingTest {
    @Test fun addsCleanBeforeTheExtension() {
        assertEquals("photo_clean.jpg", withCleanSuffix("photo.jpg"))
        assertEquals("image_clean.png", withCleanSuffix("image.png"))
        assertEquals("document_clean.jpg", withCleanSuffix("document.jpg"))
    }

    @Test fun keepsDotsInTheBaseName() {
        assertEquals("my.holiday.photo_clean.webp", withCleanSuffix("my.holiday.photo.webp"))
    }

    @Test fun handlesNamesWithoutAnExtension() {
        assertEquals("README_clean", withCleanSuffix("README"))
        assertEquals(".hidden_clean", withCleanSuffix(".hidden"))
    }

    @Test fun secondClickDoesNotStackTheSuffix() {
        assertEquals("photo_clean.jpg", withCleanSuffix(withCleanSuffix("photo.jpg")))
    }

    @Test fun typedNamesLoseUnsafeCharacters() {
        assertEquals("a_b_c.jpg", cleanTypedName("a/b\\c.jpg", "x"))
        assertEquals("fallback", cleanTypedName("   ", "fallback"))
        assertEquals("_evil.jpg", cleanTypedName("../evil.jpg", "x"))
        assertTrue(!cleanTypedName("..\\..\\x.jpg", "f").contains("\\"))
    }

    @Test fun uniqueNameCountsUpUntilFree() {
        val taken = setOf("photo_clean.jpg", "photo_clean (1).jpg")
        assertEquals("photo_clean (2).jpg", uniqueName("photo_clean.jpg") { it in taken })
        assertEquals("fresh.jpg", uniqueName("fresh.jpg") { it in taken })
        assertEquals("a (1)", uniqueName("a") { it == "a" })
    }

    @Test fun noConflictMeansPlainWrite() {
        assertEquals(SaveTarget.Write("a.jpg", false), decideTarget("a.jpg", exists = false, choice = null) { false })
    }

    @Test fun conflictFollowsTheChoice() {
        val taken = { n: String -> n == "a.jpg" }
        assertEquals(SaveTarget.Write("a.jpg", true), decideTarget("a.jpg", true, ConflictChoice.OVERWRITE, taken))
        assertEquals(SaveTarget.Write("a (1).jpg", false), decideTarget("a.jpg", true, ConflictChoice.RENAME, taken))
        assertEquals(SaveTarget.Skip, decideTarget("a.jpg", true, ConflictChoice.SKIP, taken))
    }

    @Test fun unansweredConflictNeverOverwrites() {
        val taken = { n: String -> n == "a.jpg" }
        assertEquals(SaveTarget.Write("a (1).jpg", false), decideTarget("a.jpg", true, null, taken))
    }

    @Test fun cleanBaseIsIdempotent() {
        assertEquals("photo_clean", withCleanBase("photo"))
        assertEquals("photo_clean", withCleanBase("photo_clean"))
    }

    @Test fun nameKeyIgnoresCase() {
        assertEquals(nameKey("Photo_Clean.JPG"), nameKey("photo_clean.jpg"))
    }

    @Test fun defaultBatchNameAddsCleanAndSanitizes() {
        assertEquals("photo_clean.jpg", defaultOutputName("photo.jpg"))
        assertEquals("a_b_clean.png", defaultOutputName("a:b.png"))
    }
}
