package com.shizukutrackpad.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun newerPatchMinorMajor() {
        assertTrue(VersionComparator.isNewer("1.1.1", "1.1.0"))
        assertTrue(VersionComparator.isNewer("1.2.0", "1.1.9"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun comparesNumericallyNotLexically() {
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.0"))
        assertFalse(VersionComparator.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun equalOrOlderIsNotNewer() {
        assertFalse(VersionComparator.isNewer("1.1.0", "1.1.0"))
        assertFalse(VersionComparator.isNewer("1.0.9", "1.1.0"))
    }

    @Test
    fun ignoresPrefixSuffixAndMissingParts() {
        assertFalse(VersionComparator.isNewer("v1.1.0", "1.1.0-debug"))
        assertFalse(VersionComparator.isNewer("1.1", "1.1.0"))
        assertTrue(VersionComparator.isNewer("v1.1.1+42", "1.1"))
        assertEquals(listOf(1, 2, 3), VersionComparator.parse("v1.2.3-rc1"))
    }

    @Test
    fun unparseableVersionsNeverTriggerUpdates() {
        assertNull(VersionComparator.parse("latest"))
        assertFalse(VersionComparator.isNewer("nightly", "1.0.0"))
        assertFalse(VersionComparator.isNewer("2.0.0", "unknown"))
    }
}
