package ru.moex.tradinglab

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionPolicyTest {
    @Test
    fun initialReleaseVersionIsOne() {
        assertEquals(1, BuildConfig.VERSION_CODE)
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
    }
}
