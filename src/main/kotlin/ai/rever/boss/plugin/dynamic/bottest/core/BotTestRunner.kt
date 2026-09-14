package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.coroutines.CancellationException

/**
 * Runs test cases against a bot and scores the answers.
 *
 * Isolation is the point: a case that times out, a target that 500s, and an
 * evaluator that throws all produce a result rather than aborting the suite.
 * The only thing that stops a run is coroutine cancellation.
 *
 * Cases run sequentially. Conversational targets are usually rate limited or
 * stateful, and a predictable, ordered run is easier to read than a fast one.
 */
class BotTestRunner(
    private val transport: HttpTransport,
    private val evaluators: List<Evaluator> = DefaultEvaluators.all,
    /** Injectable so tests can assert on latency without real waiting. */
    private val nanoTime: () -> Long = System::nanoTime,
) {

    suspend fun run(target: BotTarget, cases: List<BotTestCase>): SuiteResult {
        val startedAt = nanoTime()
        val results = cases.map { case ->
            try {
                runCase(target, case)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Belt and braces: runCase already isolates its own failures.
                harnessError(case, e)
            }
        }
        return SuiteResult(results = results, totalDurationMs = elapsedMsSince(startedAt))
    }

    suspend fun runCase(target: BotTarget, testCase: BotTestCase): TestResult {
        val exchange = exchange(target, testCase)
        val context = EvaluationContext(testCase, exchange)

        val evaluations = evaluators.map { evaluator ->
            try {
                evaluator.evaluate(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                EvaluationResult.fail(
                    evaluator.id,
                    "Evaluator threw ${e::class.simpleName}: ${e.message}",
                )
            }
        }

        val scored = evaluations.filterNot { it.skipped }
        val score = when {
            scored.isNotEmpty() -> scored.sumOf { it.score } / scored.size
            exchange.isSuccess -> 1.0
            else -> 0.0
        }
        val status = when {
            !exchange.isSuccess -> TestStatus.ERROR
            scored.any { !it.passed } -> TestStatus.FAILED
            else -> TestStatus.PASSED
        }

        return TestResult(
            testCase = testCase,
            exchange = exchange,
            evaluations = evaluations,
            status = status,
            score = score,
        )
    }

    private suspend fun exchange(target: BotTarget, testCase: BotTestCase): BotExchange {
        val request = HttpRequestSpec(
            url = target.url,
            method = target.method,
            headers = target.headers,
            body = Payload.buildRequestBody(testCase, target.request),
            timeoutMs = target.timeoutMs,
        )

        val startedAt = nanoTime()
        return try {
            val response = transport.send(request)
            val latencyMs = elapsedMsSince(startedAt)

            if (response.statusCode !in 200..299) {
                return BotExchange(
                    outcome = ExchangeOutcome.HTTP_ERROR,
                    latencyMs = latencyMs,
                    statusCode = response.statusCode,
                    rawBody = response.body,
                    errorMessage = "HTTP ${response.statusCode}",
                )
            }

            when (val extracted = Payload.extractResponse(response.body, target.response.responsePath)) {
                is Extraction.Found -> BotExchange(
                    outcome = ExchangeOutcome.OK,
                    latencyMs = latencyMs,
                    statusCode = response.statusCode,
                    rawBody = response.body,
                    responseText = extracted.text,
                )

                is Extraction.Missing -> BotExchange(
                    outcome = ExchangeOutcome.MALFORMED_RESPONSE,
                    latencyMs = latencyMs,
                    statusCode = response.statusCode,
                    rawBody = response.body,
                    errorMessage = extracted.reason,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransportTimeoutException) {
            BotExchange(
                outcome = ExchangeOutcome.TIMEOUT,
                latencyMs = elapsedMsSince(startedAt),
                errorMessage = e.message,
            )
        } catch (e: TransportException) {
            BotExchange(
                outcome = ExchangeOutcome.CONNECTION_ERROR,
                latencyMs = elapsedMsSince(startedAt),
                errorMessage = e.message,
            )
        } catch (e: Throwable) {
            BotExchange(
                outcome = ExchangeOutcome.CONNECTION_ERROR,
                latencyMs = elapsedMsSince(startedAt),
                errorMessage = "Unexpected ${e::class.simpleName}: ${e.message}",
            )
        }
    }

    private fun harnessError(testCase: BotTestCase, cause: Throwable): TestResult {
        val exchange = BotExchange(
            outcome = ExchangeOutcome.CONNECTION_ERROR,
            latencyMs = 0,
            errorMessage = "Runner failure ${cause::class.simpleName}: ${cause.message}",
        )
        return TestResult(
            testCase = testCase,
            exchange = exchange,
            evaluations = emptyList(),
            status = TestStatus.ERROR,
            score = 0.0,
        )
    }

    private fun elapsedMsSince(startNanos: Long): Long = (nanoTime() - startNanos) / 1_000_000
}
