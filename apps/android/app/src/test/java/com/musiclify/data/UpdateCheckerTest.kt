package com.musiclify.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private fun cmp(a: String, b: String) = UpdateChecker.compareVersions(a, b)

    @Test fun comparesNumericallyNotAsText() {
        assertTrue(cmp("0.10", "0.9") > 0)
        assertTrue(cmp("0.4", "0.5") < 0)
    }

    @Test fun missingPartsCountAsZero() {
        assertEquals(0, cmp("0.4", "0.4.0"))
    }

    @Test fun ignoresBuildSuffixes() {
        assertEquals(0, cmp("0.4-debug", "0.4"))
    }
}
