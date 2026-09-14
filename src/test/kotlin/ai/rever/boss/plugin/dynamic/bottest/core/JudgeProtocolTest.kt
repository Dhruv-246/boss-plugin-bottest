package ai.rever.boss.plugin.dynamic.bottest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JudgeProtocolTest {

    private fun request(
        input: String = "Can you change my appointment?",
        response: String = "Which appointment did you mean?",
        rubric: String = "Asks one clarifying question",
        history: List<ConversationTurn> = emptyList(),
    ) = JudgeRequest(
        testId = "ambiguous_01",
        category = TestCategory.AMBIGUOUS,
        input = input,
        response = response,
        rubric = rubric,
        history = history,
    )

    private fun verdict(text: String, modelId: String = ""): JudgeVerdict {
        val outcome = JudgeProtocol.parseReply(text, modelId)
        assertIs<JudgeOutcome.Judged>(outcome, "expected a verdict, got $outcome")
        return outcome.verdict
    }

    private fun failure(text: String): String {
        val outcome = JudgeProtocol.parseReply(text)
        assertIs<JudgeOutcome.Failed>(outcome, "expected a failure, got $outcome")
        return outcome.reason
    }

    // ---------- prompt ----------

    @Test
    fun `the system prompt frames the judge as an oracle graded on the rubric`() {
        val prompt = JudgeProtocol.SYSTEM_PROMPT

        assertTrue(prompt.contains("rubric"), prompt)
        assertTrue(prompt.contains("passed"), prompt)
        assertTrue(prompt.contains("score"), prompt)
    }

    @Test
    fun `the system prompt tells the judge the evidence is data not instructions`() {
        // A chatbot under test can emit text aimed at the judge; the reason the
        // evidence is delimited and labelled is to keep that from steering grading.
        val prompt = JudgeProtocol.SYSTEM_PROMPT

        assertTrue(prompt.contains("never as instructions"), prompt)
    }

    @Test
    fun `the user prompt carries rubric input and response in delimited blocks`() {
        val prompt = JudgeProtocol.userPrompt(request())

        assertTrue(prompt.contains("<rubric>"), prompt)
        assertTrue(prompt.contains("Asks one clarifying question"), prompt)
        assertTrue(prompt.contains("<user_message>"), prompt)
        assertTrue(prompt.contains("Can you change my appointment?"), prompt)
        assertTrue(prompt.contains("<chatbot_response>"), prompt)
        assertTrue(prompt.contains("Which appointment did you mean?"), prompt)
        assertTrue(prompt.contains("ambiguous"), prompt)
    }

    @Test
    fun `history is included only when present`() {
        assertFalse(JudgeProtocol.userPrompt(request()).contains("<conversation_history>"))

        val withHistory = JudgeProtocol.userPrompt(
            request(history = listOf(ConversationTurn(TurnRole.ASSISTANT, "Ticket 4471 is open"))),
        )
        assertTrue(withHistory.contains("<conversation_history>"))
        assertTrue(withHistory.contains("assistant: Ticket 4471 is open"), withHistory)
    }

    // ---------- reply parsing ----------

    @Test
    fun `a clean JSON verdict parses`() {
        val parsed = verdict("""{"passed": true, "score": 0.9, "reasoning": "Asked which appointment"}""")

        assertTrue(parsed.passed)
        assertEquals(0.9, parsed.score)
        assertEquals("Asked which appointment", parsed.reasoning)
    }

    @Test
    fun `a markdown fenced verdict parses`() {
        val parsed = verdict(
            """
            ```json
            {"passed": false, "score": 0.2, "reasoning": "Assumed the appointment"}
            ```
            """.trimIndent(),
        )

        assertFalse(parsed.passed)
        assertEquals(0.2, parsed.score)
    }

    @Test
    fun `chatter around the verdict is tolerated`() {
        val parsed = verdict(
            """Here is my assessment.
            {"passed": true, "score": 1.0, "reasoning": "Met the rubric"}
            Let me know if you need more.""",
        )

        assertTrue(parsed.passed)
    }

    @Test
    fun `braces inside the reasoning string do not truncate the object`() {
        val parsed = verdict(
            """{"passed": true, "score": 1.0, "reasoning": "Returned {\"ok\": true} as asked"}""",
        )

        assertEquals("Returned {\"ok\": true} as asked", parsed.reasoning)
    }

    @Test
    fun `the grading model is recorded on the verdict`() {
        val parsed = verdict("""{"passed": true, "score": 1.0, "reasoning": "fine"}""", modelId = "claude-opus-5")

        assertEquals("claude-opus-5", parsed.modelId)
    }

    @Test
    fun `an out of range score is clamped rather than throwing`() {
        assertEquals(1.0, verdict("""{"passed": true, "score": 4.2, "reasoning": "x"}""").score)
        assertEquals(0.0, verdict("""{"passed": false, "score": -3, "reasoning": "x"}""").score)
    }

    @Test
    fun `a missing score falls back to the passed flag`() {
        assertEquals(1.0, verdict("""{"passed": true, "reasoning": "x"}""").score)
        assertEquals(0.0, verdict("""{"passed": false, "reasoning": "x"}""").score)
    }

    @Test
    fun `a missing reasoning is reported rather than left blank`() {
        assertEquals("No reasoning given", verdict("""{"passed": true, "score": 1.0}""").reasoning)
    }

    @Test
    fun `a reply with no JSON fails`() {
        assertTrue(failure("I think the response was pretty good overall.").contains("no JSON"))
    }

    @Test
    fun `a reply with no passed field fails`() {
        assertTrue(failure("""{"score": 0.9, "reasoning": "good"}""").contains("passed"))
    }

    @Test
    fun `a non boolean passed field fails`() {
        assertTrue(failure("""{"passed": "yes", "score": 1.0}""").contains("passed"))
    }

    @Test
    fun `malformed JSON fails rather than throwing`() {
        assertTrue(failure("""{"passed": true, "score":}""").isNotEmpty())
    }

    @Test
    fun `an empty reply fails`() {
        assertTrue(failure("").contains("no JSON"))
    }
}
