package com.ytgrab.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun duration_isFormatted() {
        assertEquals("1:05", fmtDur(65))
    }
}
