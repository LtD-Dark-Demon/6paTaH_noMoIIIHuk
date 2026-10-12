package com.bratan.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedIntentProviderTest {
    @Test
    fun reminderStaysBelowCloudEscalationThreshold() = runBlocking {
        val result = RuleBasedIntentProvider().process("напомни завтра в 18:30 позвонить маме")
        assertNotNull(result)
        assertTrue(
            "Напоминание со временем должно доходить до облака для извлечения epoch_ms",
            result!!.confidence < 0.85f
        )
    }
}
