package ai.rever.boss.plugin.dynamic.bottest.core

/** A named collection of cases plus the target they run against. */
data class TestSuite(
    val id: String,
    val name: String,
    val description: String,
    val target: BotTarget,
    val cases: List<BotTestCase>,
) {
    /** Cases whose criteria only a future LLM judge could check. */
    val casesAwaitingJudge: List<BotTestCase>
        get() = cases.filter { it.criteria.isRubricOnly }
}

/**
 * What [SuiteRepository.list] reports for one suite file.
 *
 * Deliberately carries no path, headers, or target details: this crosses the MCP
 * boundary to an agent, and a suite's headers may hold an API key.
 */
data class SuiteSummary(
    val id: String,
    val name: String,
    val description: String,
    val testCount: Int,
    val categories: List<TestCategory>,
    /** Set when the file could not be parsed; the suite cannot be run. */
    val problem: String? = null,
) {
    val isRunnable: Boolean get() = problem == null
}

sealed interface SuiteParseResult {
    data class Valid(val suite: TestSuite) : SuiteParseResult

    data class Invalid(val errors: List<String>) : SuiteParseResult
}

sealed interface SuiteLoadResult {
    data class Loaded(val suite: TestSuite) : SuiteLoadResult

    data class NotFound(val id: String, val available: List<String>) : SuiteLoadResult

    data class Invalid(val id: String, val errors: List<String>) : SuiteLoadResult
}
