package ai.rever.boss.plugin.dynamic.bottest.core

/** Why an exchange with the bot ended the way it did. */
enum class ExchangeOutcome {
    OK,
    HTTP_ERROR,
    TIMEOUT,
    CONNECTION_ERROR,
    MALFORMED_RESPONSE,
}

/** One request/response round trip with the bot under test. */
data class BotExchange(
    val outcome: ExchangeOutcome,
    val latencyMs: Long,
    val statusCode: Int? = null,
    val rawBody: String? = null,
    val responseText: String? = null,
    val errorMessage: String? = null,
) {
    val isSuccess: Boolean get() = outcome == ExchangeOutcome.OK
}

/**
 * ERROR means we never got a usable answer (timeout, unreachable, non-2xx,
 * unparseable). FAILED means the bot answered and the answer fell short. The
 * split matters: a suite full of ERRORs is a broken target, not a bad bot.
 */
enum class TestStatus {
    PASSED,
    FAILED,
    ERROR,
}

data class TestResult(
    val testCase: BotTestCase,
    val exchange: BotExchange,
    val evaluations: List<EvaluationResult>,
    val status: TestStatus,
    val score: Double,
) {
    val category: TestCategory get() = testCase.category

    /** Evaluations that actually counted - skipped ones do not drag the score. */
    val scoredEvaluations: List<EvaluationResult> get() = evaluations.filterNot { it.skipped }

    val failedEvaluations: List<EvaluationResult>
        get() = scoredEvaluations.filterNot { it.passed }
}

data class CategorySummary(
    val category: TestCategory,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val errors: Int,
    val meanScore: Double,
    val meanLatencyMs: Long,
) {
    /** Share of cases in this category that passed outright, 0.0..1.0. */
    val passRate: Double get() = if (total == 0) 0.0 else passed.toDouble() / total
}

/** The outcome of running a whole suite against one target. */
data class SuiteResult(
    val results: List<TestResult>,
    val totalDurationMs: Long,
) {
    val total: Int get() = results.size
    val passed: Int get() = results.count { it.status == TestStatus.PASSED }
    val failed: Int get() = results.count { it.status == TestStatus.FAILED }
    val errors: Int get() = results.count { it.status == TestStatus.ERROR }

    /** Mean of the per-case scores, 0.0..1.0. */
    val score: Double get() = if (results.isEmpty()) 0.0 else results.sumOf { it.score } / results.size

    val passRate: Double get() = if (results.isEmpty()) 0.0 else passed.toDouble() / total

    /**
     * Per-category breakdown, in declaration order so reports read consistently.
     * Categories with no cases are omitted.
     */
    val byCategory: Map<TestCategory, CategorySummary>
        get() = TestCategory.entries
            .mapNotNull { category ->
                val inCategory = results.filter { it.category == category }
                if (inCategory.isEmpty()) return@mapNotNull null
                category to CategorySummary(
                    category = category,
                    total = inCategory.size,
                    passed = inCategory.count { it.status == TestStatus.PASSED },
                    failed = inCategory.count { it.status == TestStatus.FAILED },
                    errors = inCategory.count { it.status == TestStatus.ERROR },
                    meanScore = inCategory.sumOf { it.score } / inCategory.size,
                    meanLatencyMs = inCategory.sumOf { it.exchange.latencyMs } / inCategory.size,
                )
            }
            .toMap()
}
