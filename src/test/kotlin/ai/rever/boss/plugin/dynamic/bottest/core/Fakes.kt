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

/** In-memory suite store, so MCP tool tests touch no filesystem. */
class FakeSuiteRepository(
    private val suites: Map<String, SuiteLoadResult> = emptyMap(),
    private val summaries: List<SuiteSummary> = emptyList(),
    private val exists: Boolean = true,
    private val onList: (() -> Unit)? = null,
) : SuiteRepository {

    override suspend fun list(): List<SuiteSummary> {
        onList?.invoke()
        return summaries
    }

    override suspend fun load(id: String): SuiteLoadResult =
        suites[id] ?: SuiteLoadResult.NotFound(id, suites.keys.sorted())

    override suspend fun storageExists(): Boolean = exists

    companion object {
        fun holding(suite: TestSuite, exists: Boolean = true) = FakeSuiteRepository(
            suites = mapOf(suite.id to SuiteLoadResult.Loaded(suite)),
            summaries = listOf(
                SuiteSummary(
                    id = suite.id,
                    name = suite.name,
                    description = suite.description,
                    testCount = suite.cases.size,
                    categories = suite.cases.map { it.category }.distinct(),
                ),
            ),
            exists = exists,
        )
    }
}

internal fun suite(
    id: String = "customer-support",
    name: String = "Customer Support Tests",
    description: String = "Basic tests for the support chatbot",
    url: String = "http://localhost:8000/chat",
    cases: List<BotTestCase> = listOf(testCase()),
) = TestSuite(
    id = id,
    name = name,
    description = description,
    target = BotTarget(url = url),
    cases = cases,
)
