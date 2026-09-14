package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotTestRunnerTest {

    private val okBody = """{"response":"Hello! How can I help?"}"""

    @Test
    fun `successful request is captured and passes`() = runTest {
        val transport = FakeHttpTransport.responding(okBody)
        val runner = BotTestRunner(transport)

        val result = runner.runCase(target(), testCase())

        assertEquals(TestStatus.PASSED, result.status)
        assertEquals(ExchangeOutcome.OK, result.exchange.outcome)
        assertEquals(200, result.exchange.statusCode)
        assertEquals("Hello! How can I help?", result.exchange.responseText)
        assertEquals(1.0, result.score)
    }

    @Test
    fun `runner sends the configured url method and body`() = runTest {
        val transport = FakeHttpTransport.responding(okBody)
        val runner = BotTestRunner(transport)

        runner.runCase(target(url = "https://bot.example/chat"), testCase(input = "Hello"))

        val sent = transport.requests.single()
        assertEquals("https://bot.example/chat", sent.url)
        assertEquals("POST", sent.method)
        assertTrue(sent.body.contains("\"message\""), sent.body)
        assertTrue(sent.body.contains("Hello"), sent.body)
    }

    @Test
    fun `timeout is recorded as an error not a crash`() = runTest {
        val transport = FakeHttpTransport.throwing(TransportTimeoutException("timed out after 30000 ms"))
        val runner = BotTestRunner(transport)

        val result = runner.runCase(target(), testCase())

        assertEquals(TestStatus.ERROR, result.status)
        assertEquals(ExchangeOutcome.TIMEOUT, result.exchange.outcome)
        assertNull(result.exchange.responseText)
        val message = assertNotNull(result.exchange.errorMessage)
        assertTrue(message.contains("timed out"), message)
    }

    @Test
    fun `connection failure is recorded as an error`() = runTest {
        val transport = FakeHttpTransport.throwing(TransportConnectionException("connection refused"))
        val runner = BotTestRunner(transport)

        val result = runner.runCase(target(), testCase())

        assertEquals(TestStatus.ERROR, result.status)
        assertEquals(ExchangeOutcome.CONNECTION_ERROR, result.exchange.outcome)
    }

    @Test
    fun `unexpected transport exception is contained`() = runTest {
        val transport = FakeHttpTransport.throwing(IllegalStateException("boom"))
        val runner = BotTestRunner(transport)

        val result = runner.runCase(target(), testCase())

        assertEquals(TestStatus.ERROR, result.status)
        val message = assertNotNull(result.exchange.errorMessage)
        assertTrue(message.contains("boom"), message)
    }

    @Test
    fun `http error status is an error and keeps the body for debugging`() = runTest {
        val transport = FakeHttpTransport.responding("""{"error":"server exploded"}""", status = 500)
        val runner = BotTestRunner(transport)

        val result = runner.runCase(target(), testCase())

        assertEquals(TestStatus.ERROR, result.status)
        assertEquals(ExchangeOutcome.HTTP_ERROR, result.exchange.outcome)
        assertEquals(500, result.exchange.statusCode)
        assertTrue(result.exchange.rawBody!!.contains("server exploded"))
    }

    @Test
    fun `malformed response body is an error`() = runTest {
        val transport = FakeHttpTransport.responding("<html>not json</html>")
        val runner = BotTestRunner(transport)

        val result = runner.runCase(target(), testCase())

        assertEquals(TestStatus.ERROR, result.status)
        assertEquals(ExchangeOutcome.MALFORMED_RESPONSE, result.exchange.outcome)
    }

    @Test
    fun `missing response field is an error`() = runTest {
        val transport = FakeHttpTransport.responding("""{"reply":"wrong field name"}""")
        val runner = BotTestRunner(transport)

        val result = runner.runCase(target(), testCase())

        assertEquals(ExchangeOutcome.MALFORMED_RESPONSE, result.exchange.outcome)
    }

    @Test
    fun `empty response reaches the bot but fails rather than erroring`() = runTest {
        val transport = FakeHttpTransport.responding("""{"response":""}""")
        val runner = BotTestRunner(transport)

        val result = runner.runCase(target(), testCase())

        // The exchange succeeded - the bot simply said nothing. That is a bot
        // defect (FAILED), not a transport defect (ERROR).
        assertEquals(ExchangeOutcome.OK, result.exchange.outcome)
        assertEquals(TestStatus.FAILED, result.status)
        assertTrue(result.failedEvaluations.any { it.evaluatorId == "response_non_empty" })
    }

    @Test
    fun `latency is measured from the injected clock`() = runTest {
        val transport = FakeHttpTransport.responding(okBody)
        val runner = BotTestRunner(transport, nanoTime = FakeClock(stepMs = 250)::nanoTime)

        val result = runner.runCase(target(), testCase())

        assertEquals(250, result.exchange.latencyMs)
    }

    @Test
    fun `latency budget failure marks the case failed not errored`() = runTest {
        val transport = FakeHttpTransport.responding(okBody)
        val runner = BotTestRunner(transport, nanoTime = FakeClock(stepMs = 900)::nanoTime)

        val result = runner.runCase(
            target(),
            testCase(criteria = Criteria(maxLatencyMs = 500)),
        )

        assertEquals(TestStatus.FAILED, result.status)
        assertTrue(result.failedEvaluations.any { it.evaluatorId == "latency" })
    }

    @Test
    fun `a throwing evaluator fails only its own check`() = runTest {
        val exploding = object : Evaluator {
            override val id = "exploding"
            override suspend fun evaluate(context: EvaluationContext): EvaluationResult =
                throw IllegalStateException("evaluator bug")
        }
        val runner = BotTestRunner(
            FakeHttpTransport.responding(okBody),
            evaluators = listOf(HttpSuccessEvaluator, exploding),
        )

        val result = runner.runCase(target(), testCase())

        assertEquals(TestStatus.FAILED, result.status)
        assertTrue(result.evaluations.first { it.evaluatorId == "http_success" }.passed)
        val failure = result.evaluations.first { it.evaluatorId == "exploding" }
        assertTrue(failure.detail.contains("evaluator bug"), failure.detail)
    }

    @Test
    fun `custom response path is honoured`() = runTest {
        val transport = FakeHttpTransport.responding(
            """{"choices":[{"message":{"content":"From a nested shape"}}]}""",
        )
        val runner = BotTestRunner(transport)

        val result = runner.runCase(
            target(response = ResponseConfig(responsePath = "choices.0.message.content")),
            testCase(),
        )

        assertEquals("From a nested shape", result.exchange.responseText)
        assertNotNull(result.exchange.rawBody)
    }
}
