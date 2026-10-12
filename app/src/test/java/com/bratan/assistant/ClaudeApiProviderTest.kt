package com.bratan.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ClaudeApiProviderTest {
    @Test
    fun parsesTextBlockWhenThinkingComesFirst() {
        val response = """
            {
              "content": [
                {"type":"thinking","thinking":"","signature":"sig"},
                {"type":"text","text":"{\"intent\":\"FLASHLIGHT_ON\",\"confidence\":0.91,\"actions\":[\"flashlight_on\"],\"params\":{}}"}
              ]
            }
        """.trimIndent()

        val provider = ClaudeApiProvider("test-key")
        val parse = ClaudeApiProvider::class.java.getDeclaredMethod("parse", String::class.java)
        parse.isAccessible = true
        val result = parse.invoke(provider, response) as CommandResult?

        assertNotNull(result)
        assertEquals("FLASHLIGHT_ON", result!!.intent)
        assertEquals(listOf(Actions.FLASHLIGHT_ON), result.actions)
    }
}
