package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmJudgeEvaluatorTest {

    private fun context(
        rubric: String? = "Asks one clarifying question",
        response: String? = "Which appointment did you mean?",
        outcome: ExchangeOutcome = ExchangeOutcome.OK,
    ) = EvaluationContext(
        testCase = testCase(criteria = Criteria(rubric = rubric)),
        exchange = exchange(outcome = outcome, responseText = response),
    )

    @Test
    fun `a passing verdict passes with the judge's score and reasoning`() = runTest {
        val evaluator = LlmJudgeEvaluator(FakeJudgeClient.verdict(passed = true, score = 0.9, reasoning = "Asked which one"))

        val result = evaluator.evaluate(context())

        assertTrue(result.passed)
        assertFalse(result.skipped)
        assertEquals(0.9, result.score)
        assertTrue(result.detail.contains("Asked which one"), result.detail)
    }

    @Test
    fun `a failing verdict fails and keeps partial credit`() = runTest {
        val evaluator = LlmJudgeEvaluator(FakeJudgeClient.verdict(passed = false, score = 0.3, reasoning = "Assumed the appointment"))

        val result = evaluator.evaluate(context())

        assertFalse(result.passed)
        assertEquals(0.3, result.score)
    }

    @Test
    fun `the grading model is attributed in the detail`() = runTest {
        val evaluator = LlmJudgeEvaluator(
            FakeJudgeClient.verdict(passed = true, score = 1.0, modelId = "claude-opus-5"),
        )

        val result = evaluator.evaluate(context())

        assertTrue(result.detail.contains("claude-opus-5"), result.detail)
    }

    @Test
    fun `no rubric means nothing to judge`() = runTest {
        val evaluator = LlmJudgeEvaluator(FakeJudgeClient())

        val result = evaluator.evaluate(context(rubric = null))

        assertTrue(result.skipped)
    }

    @Test
    fun `an unavailable judge is skipped, never counted against the bot`() = runTest {
        val evaluator = LlmJudgeEvaluator(FakeJudgeClient.unavailable("No AI Gateway plugin is installed"))

        val result = evaluator.evaluate(context())

        assertTrue(result.skipped, "an unconfigured machine must not fail someone's chatbot")
        assertTrue(result.detail.contains("No AI Gateway plugin"), result.detail)
    }

    @Test
    fun `a broken judge is skipped, never counted against the bot`() = runTest {
        val evaluator = LlmJudgeEvaluator(FakeJudgeClient.failing("quota exceeded"))

        val result = evaluator.evaluate(context())

        assertTrue(result.skipped)
        assertTrue(result.detail.contains("quota exceeded"), result.detail)
    }

    @Test
    fun `a failed exchange is not sent to the judge`() = runTest {
        val client = FakeJudgeClient()
        val evaluator = LlmJudgeEvaluator(client)

        val result = evaluator.evaluate(context(outcome = ExchangeOutcome.TIMEOUT, response = null))

        assertTrue(result.skipped)
        assertTrue(client.requests.isEmpty(), "no point paying for a judgement on a timeout")
    }

    @Test
    fun `an empty response is not sent to the judge`() = runTest {
        val client = FakeJudgeClient()

        LlmJudgeEvaluator(client).evaluate(context(response = "   "))

        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `the judge receives the case input response rubric and history`() = runTest {
        val client = FakeJudgeClient()
        val history = listOf(ConversationTurn(TurnRole.USER, "I have two tickets"))
        val evaluator = LlmJudgeEvaluator(client)

        evaluator.evaluate(
            EvaluationContext(
                testCase = BotTestCase(
                    id = "mt_01",
                    category = TestCategory.MULTI_TURN,
                    input = "And the second?",
                    history = history,
                    criteria = Criteria(rubric = "Resolves the reference"),
                ),
                exchange = exchange(responseText = "Ticket 4472 is closed"),
            ),
        )

        val request = client.requests.single()
        assertEquals("mt_01", request.testId)
        assertEquals(TestCategory.MULTI_TURN, request.category)
        assertEquals("And the second?", request.input)
        assertEquals("Ticket 4472 is closed", request.response)
        assertEquals("Resolves the reference", request.rubric)
        assertEquals(history, request.history)
    }

    @Test
    fun `judge results join the deterministic evaluators in a run`() = runTest {
        val runner = BotTestRunner(
            FakeHttpTransport.responding("""{"response":"Which appointment did you mean?"}"""),
            evaluators = DefaultEvaluators.all + LlmJudgeEvaluator(
                FakeJudgeClient.verdict(passed = false, score = 0.2, reasoning = "Did not clarify"),
            ),
        )

        val result = runner.runCase(
            target(),
            testCase(criteria = Criteria(rubric = "Asks one clarifying question")),
        )

        assertEquals(TestStatus.FAILED, result.status)
        assertTrue(result.failedEvaluations.any { it.evaluatorId == LlmJudgeEvaluator.ID })
    }

    @Test
    fun `a run without a judge still scores the deterministic checks`() = runTest {
        val runner = BotTestRunner(
            FakeHttpTransport.responding("""{"response":"Which appointment did you mean?"}"""),
            evaluators = DefaultEvaluators.all + LlmJudgeEvaluator(NoJudgeClient),
        )

        val result = runner.runCase(
            target(),
            testCase(criteria = Criteria(rubric = "Asks one clarifying question")),
        )

        assertEquals(TestStatus.PASSED, result.status, "an absent judge must not fail the case")
        assertTrue(result.evaluations.single { it.evaluatorId == LlmJudgeEvaluator.ID }.skipped)
    }
}
