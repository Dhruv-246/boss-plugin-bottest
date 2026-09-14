package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PayloadTest {

    @Test
    fun `request body carries the input under the configured field`() {
        val body = Payload.buildRequestBody(testCase(input = "Hello"), RequestConfig())
        val parsed = Json.parseToJsonElement(body).jsonObject

        assertEquals("Hello", parsed["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `message field name is configurable`() {
        val body = Payload.buildRequestBody(
            testCase(input = "Hi"),
            RequestConfig(messageField = "query"),
        )
        val parsed = Json.parseToJsonElement(body).jsonObject

        assertEquals("Hi", parsed["query"]?.jsonPrimitive?.content)
        assertTrue("message" !in parsed)
    }

    @Test
    fun `input containing quotes and newlines stays valid JSON`() {
        // A template-interpolated body would corrupt here; a built object cannot.
        val nasty = """He said "hi"
        then left \ """
        val body = Payload.buildRequestBody(testCase(input = nasty), RequestConfig())

        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals(nasty, parsed["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `history is sent only when the target declares a history field`() {
        val history = listOf(
            ConversationTurn(TurnRole.USER, "My name is Dhruv"),
            ConversationTurn(TurnRole.ASSISTANT, "Nice to meet you"),
        )

        val without = Json.parseToJsonElement(
            Payload.buildRequestBody(testCase(history = history), RequestConfig()),
        ).jsonObject
        assertEquals(1, without.size, "No history field configured, so only the message is sent")

        val with = Json.parseToJsonElement(
            Payload.buildRequestBody(testCase(history = history), RequestConfig(historyField = "messages")),
        ).jsonObject
        val turns = with["messages"]?.jsonArray
        assertEquals(2, turns?.size)
        assertEquals("user", turns?.get(0)?.jsonObject?.get("role")?.jsonPrimitive?.content)
        assertEquals("Nice to meet you", turns?.get(1)?.jsonObject?.get("content")?.jsonPrimitive?.content)
    }

    @Test
    fun `extra fields are merged into the request`() {
        val body = Payload.buildRequestBody(
            testCase(),
            RequestConfig(extraFields = mapOf("session_id" to "abc123")),
        )
        val parsed = Json.parseToJsonElement(body).jsonObject

        assertEquals("abc123", parsed["session_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `extraction reads a top level field`() {
        val found = Payload.extractResponse("""{"response":"Hello there"}""", "response")

        assertIs<Extraction.Found>(found)
        assertEquals("Hello there", found.text)
    }

    @Test
    fun `extraction walks a nested path and array indices`() {
        val body = """{"choices":[{"message":{"content":"Nested reply"}}]}"""

        val found = Payload.extractResponse(body, "choices.0.message.content")

        assertIs<Extraction.Found>(found)
        assertEquals("Nested reply", found.text)
    }

    @Test
    fun `blank path returns the raw body for plain text bots`() {
        val found = Payload.extractResponse("  just text  ", "")

        assertIs<Extraction.Found>(found)
        assertEquals("just text", found.text)
    }

    @Test
    fun `extraction reports non JSON bodies`() {
        val missing = Payload.extractResponse("<html>502 Bad Gateway</html>", "response")

        assertIs<Extraction.Missing>(missing)
        assertTrue(missing.reason.contains("not valid JSON"), missing.reason)
    }

    @Test
    fun `extraction reports a missing field`() {
        val missing = Payload.extractResponse("""{"reply":"wrong field"}""", "response")

        assertIs<Extraction.Missing>(missing)
        assertTrue(missing.reason.contains("response"), missing.reason)
    }

    @Test
    fun `extraction reports a null field`() {
        val missing = Payload.extractResponse("""{"response":null}""", "response")

        assertIs<Extraction.Missing>(missing)
        assertTrue(missing.reason.contains("null"), missing.reason)
    }

    @Test
    fun `extraction reports an out of bounds index`() {
        val missing = Payload.extractResponse("""{"choices":[]}""", "choices.0")

        assertIs<Extraction.Missing>(missing)
        assertTrue(missing.reason.contains("out of bounds"), missing.reason)
    }

    @Test
    fun `extraction rejects a non scalar target`() {
        val missing = Payload.extractResponse("""{"response":{"nested":true}}""", "response")

        assertIs<Extraction.Missing>(missing)
        assertTrue(missing.reason.contains("not a scalar"), missing.reason)
    }
}
