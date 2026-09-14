package ai.rever.boss.plugin.dynamic.bottest.core

/**
 * Scriptable transport so the suite never needs a live chatbot.
 *
 * [requests] records what the runner actually sent, which is how the request
 * encoding tests assert without inspecting private helpers.
 */
class FakeHttpTransport(
    private val handler: suspend (HttpRequestSpec) -> HttpResponseSpec,
) : HttpTransport {

    val requests = mutableListOf<HttpRequestSpec>()

    override suspend fun send(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        return handler(request)
    }

    companion object {
        fun responding(body: String, status: Int = 200) =
            FakeHttpTransport { HttpResponseSpec(status, body) }

        fun throwing(error: Throwable) =
            FakeHttpTransport { throw error }

        /** Answers the first call, then throws - for "one failure does not stop the suite". */
        fun failingAfter(successes: Int, body: String, error: Throwable): FakeHttpTransport {
            var seen = 0
            return FakeHttpTransport {
                if (seen++ < successes) HttpResponseSpec(200, body) else throw error
            }
        }
    }
}

/** Monotonic fake clock: every reading advances by [stepMs]. */
class FakeClock(private val stepMs: Long = 0) {
    private var nowNanos = 0L

    fun nanoTime(): Long {
        val current = nowNanos
        nowNanos += stepMs * 1_000_000
        return current
    }
}

internal fun testCase(
    id: String = "case-1",
    category: TestCategory = TestCategory.HAPPY_PATH,
    input: String = "Hello",
    criteria: Criteria = Criteria(),
    history: List<ConversationTurn> = emptyList(),
) = BotTestCase(
    id = id,
    category = category,
    input = input,
    criteria = criteria,
    history = history,
)

internal fun target(
    url: String = "https://bot.example/chat",
    request: RequestConfig = RequestConfig(),
    response: ResponseConfig = ResponseConfig(),
) = BotTarget(url = url, request = request, response = response)

internal fun exchange(
    outcome: ExchangeOutcome = ExchangeOutcome.OK,
    responseText: String? = "Hello! How can I help?",
    latencyMs: Long = 100,
) = BotExchange(
    outcome = outcome,
    latencyMs = latencyMs,
    statusCode = if (outcome == ExchangeOutcome.OK) 200 else null,
    responseText = responseText,
)
