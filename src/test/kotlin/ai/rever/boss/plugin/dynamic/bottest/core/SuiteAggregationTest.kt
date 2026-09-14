package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuiteAggregationTest {

    private val okBody = """{"response":"Your order ships on Tuesday."}"""

    @Test
    fun `suite counts passes failures and errors separately`() = runTest {
        val runner = BotTestRunner(FakeHttpTransport.responding(okBody))

        val suite = runner.run(
            target(),
            listOf(
                // passes
                testCase(id = "pass-1"),
                // answered, but the required phrase is absent -> FAILED
                testCase(id = "fail-1", criteria = Criteria(requiredPhrases = listOf("refund"))),
            ),
        )

        assertEquals(2, suite.total)
        assertEquals(1, suite.passed)
        assertEquals(1, suite.failed)
        assertEquals(0, suite.errors)
    }

    @Test
    fun `one failing case does not stop the suite`() = runTest {
        // First call answers, every later call blows up.
        val transport = FakeHttpTransport.failingAfter(
            successes = 1,
            body = okBody,
            error = TransportConnectionException("connection refused"),
        )
        val runner = BotTestRunner(transport)

        val suite = runner.run(
            target(),
            listOf(
                testCase(id = "first"),
                testCase(id = "second"),
                testCase(id = "third"),
            ),
        )

        assertEquals(3, suite.total, "Every case must produce a result")
        assertEquals(1, suite.passed)
        assertEquals(2, suite.errors)
        assertEquals(listOf("first", "second", "third"), suite.results.map { it.testCase.id })
    }

    @Test
    fun `suite score is the mean of case scores`() = runTest {
        val runner = BotTestRunner(
            FakeHttpTransport.responding(okBody),
            evaluators = listOf(RequiredPhrasesEvaluator),
        )

        val suite = runner.run(
            target(),
            listOf(
                // all phrases present -> 1.0
                testCase(id = "full", criteria = Criteria(requiredPhrases = listOf("Tuesday"))),
                // one of two present -> 0.5
                testCase(id = "half", criteria = Criteria(requiredPhrases = listOf("Tuesday", "refund"))),
            ),
        )

        assertEquals(0.75, suite.score)
        assertEquals(0.5, suite.passRate)
    }

    @Test
    fun `category aggregation reports per category pass rates`() = runTest {
        val runner = BotTestRunner(FakeHttpTransport.responding(okBody))

        val suite = runner.run(
            target(),
            listOf(
                testCase(id = "h1", category = TestCategory.HAPPY_PATH),
                testCase(id = "h2", category = TestCategory.HAPPY_PATH),
                testCase(
                    id = "a1",
                    category = TestCategory.ADVERSARIAL,
                    criteria = Criteria(forbiddenPhrases = listOf("Tuesday")),
                ),
                testCase(id = "a2", category = TestCategory.ADVERSARIAL),
            ),
        )

        val happy = suite.byCategory.getValue(TestCategory.HAPPY_PATH)
        assertEquals(2, happy.total)
        assertEquals(2, happy.passed)
        assertEquals(1.0, happy.passRate)

        val adversarial = suite.byCategory.getValue(TestCategory.ADVERSARIAL)
        assertEquals(2, adversarial.total)
        assertEquals(1, adversarial.passed)
        assertEquals(1, adversarial.failed)
        assertEquals(0.5, adversarial.passRate)
    }

    @Test
    fun `categories with no cases are omitted`() = runTest {
        val runner = BotTestRunner(FakeHttpTransport.responding(okBody))

        val suite = runner.run(target(), listOf(testCase(category = TestCategory.PERSONA)))

        assertEquals(setOf(TestCategory.PERSONA), suite.byCategory.keys)
    }

    @Test
    fun `category summary averages latency`() = runTest {
        val runner = BotTestRunner(
            FakeHttpTransport.responding(okBody),
            nanoTime = FakeClock(stepMs = 100)::nanoTime,
        )

        val suite = runner.run(
            target(),
            listOf(testCase(id = "c1"), testCase(id = "c2")),
        )

        assertEquals(100, suite.byCategory.getValue(TestCategory.HAPPY_PATH).meanLatencyMs)
    }

    @Test
    fun `an empty suite is well defined rather than dividing by zero`() = runTest {
        val runner = BotTestRunner(FakeHttpTransport.responding(okBody))

        val suite = runner.run(target(), emptyList())

        assertEquals(0, suite.total)
        assertEquals(0.0, suite.score)
        assertEquals(0.0, suite.passRate)
        assertTrue(suite.byCategory.isEmpty())
    }

    @Test
    fun `category wire names round trip for suite authoring`() {
        TestCategory.entries.forEach { category ->
            assertEquals(category, TestCategory.fromWireName(category.wireName))
        }
        assertEquals(TestCategory.OUT_OF_SCOPE, TestCategory.fromWireName(" Out_Of_Scope "))
        assertEquals(null, TestCategory.fromWireName("nonsense"))
    }
}
