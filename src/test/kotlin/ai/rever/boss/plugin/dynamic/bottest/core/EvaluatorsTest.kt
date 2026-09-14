package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvaluatorsTest {

    private fun context(
        criteria: Criteria = Criteria(),
        response: String? = "Your order ships on Tuesday.",
        outcome: ExchangeOutcome = ExchangeOutcome.OK,
        latencyMs: Long = 120,
    ) = EvaluationContext(
        testCase = testCase(criteria = criteria),
        exchange = exchange(outcome = outcome, responseText = response, latencyMs = latencyMs),
    )

    @Test
    fun `non empty evaluator passes on real text`() = runTest {
        val result = ResponseNonEmptyEvaluator.evaluate(context())
        assertTrue(result.passed)
    }

    @Test
    fun `non empty evaluator fails on blank and null`() = runTest {
        assertFalse(ResponseNonEmptyEvaluator.evaluate(context(response = "   ")).passed)
        assertFalse(ResponseNonEmptyEvaluator.evaluate(context(response = null)).passed)
    }

    @Test
    fun `http success evaluator reflects the outcome`() = runTest {
        assertTrue(HttpSuccessEvaluator.evaluate(context()).passed)
        assertFalse(HttpSuccessEvaluator.evaluate(context(outcome = ExchangeOutcome.TIMEOUT)).passed)
    }

    @Test
    fun `latency evaluator is skipped without a budget`() = runTest {
        val result = LatencyEvaluator.evaluate(context())

        assertTrue(result.skipped, "No budget set, so the check must not score")
    }

    @Test
    fun `latency evaluator passes inside the budget and fails outside`() = runTest {
        val inside = LatencyEvaluator.evaluate(
            context(criteria = Criteria(maxLatencyMs = 500), latencyMs = 120),
        )
        assertTrue(inside.passed)
        assertFalse(inside.skipped)

        val outside = LatencyEvaluator.evaluate(
            context(criteria = Criteria(maxLatencyMs = 100), latencyMs = 450),
        )
        assertFalse(outside.passed)
        assertTrue(outside.detail.contains("450"), outside.detail)
    }

    @Test
    fun `latency evaluator treats the budget as inclusive`() = runTest {
        val result = LatencyEvaluator.evaluate(
            context(criteria = Criteria(maxLatencyMs = 200), latencyMs = 200),
        )
        assertTrue(result.passed, "Exactly at budget should pass")
    }

    @Test
    fun `required phrases pass when all present and are case insensitive by default`() = runTest {
        val result = RequiredPhrasesEvaluator.evaluate(
            context(criteria = Criteria(requiredPhrases = listOf("TUESDAY", "ships"))),
        )
        assertTrue(result.passed, result.detail)
    }

    @Test
    fun `required phrases give partial credit and still fail`() = runTest {
        val result = RequiredPhrasesEvaluator.evaluate(
            context(criteria = Criteria(requiredPhrases = listOf("Tuesday", "refund"))),
        )

        assertFalse(result.passed)
        assertEquals(0.5, result.score, "One of two phrases found")
        assertTrue(result.detail.contains("refund"), result.detail)
    }

    @Test
    fun `required phrases honour case sensitivity when asked`() = runTest {
        val result = RequiredPhrasesEvaluator.evaluate(
            context(criteria = Criteria(requiredPhrases = listOf("tuesday"), caseSensitive = true)),
        )
        assertFalse(result.passed, "Response says 'Tuesday', not 'tuesday'")
    }

    @Test
    fun `required phrases are skipped when none configured`() = runTest {
        assertTrue(RequiredPhrasesEvaluator.evaluate(context()).skipped)
    }

    @Test
    fun `forbidden phrases fail when one appears`() = runTest {
        val result = ForbiddenPhrasesEvaluator.evaluate(
            context(
                criteria = Criteria(forbiddenPhrases = listOf("as an AI language model")),
                response = "As an AI language model, I cannot help.",
            ),
        )

        assertFalse(result.passed)
        assertTrue(result.detail.contains("as an AI language model"), result.detail)
    }

    @Test
    fun `forbidden phrases pass when none appear`() = runTest {
        val result = ForbiddenPhrasesEvaluator.evaluate(
            context(criteria = Criteria(forbiddenPhrases = listOf("password", "refund"))),
        )
        assertTrue(result.passed)
    }

    @Test
    fun `forbidden phrases are skipped when none configured`() = runTest {
        assertTrue(ForbiddenPhrasesEvaluator.evaluate(context()).skipped)
    }

    @Test
    fun `evaluator ids are unique across the default set`() {
        val ids = DefaultEvaluators.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Duplicate evaluator ids in $ids")
    }
}
