package ai.rever.boss.plugin.dynamic.bottest.core

/** What the judge is asked to rule on. */
data class JudgeRequest(
    val testId: String,
    val category: TestCategory,
    val input: String,
    val response: String,
    val rubric: String,
    val history: List<ConversationTurn> = emptyList(),
)

/** The judge's ruling on one response. */
data class JudgeVerdict(
    val passed: Boolean,
    val score: Double,
    val reasoning: String,
    /** Which model actually answered, so a run can show who graded it. */
    val modelId: String = "",
) {
    init {
        require(score in 0.0..1.0) { "score must be in 0.0..1.0, was $score" }
    }
}

/**
 * Three outcomes, kept apart because they mean different things to a run.
 *
 * [Unavailable] is a configuration state - nothing is wired up to judge with.
 * [Failed] is a judge that was reachable and did not produce a usable answer.
 * Neither is evidence about the bot, so neither may count against its score.
 */
sealed interface JudgeOutcome {
    data class Judged(val verdict: JudgeVerdict) : JudgeOutcome

    data class Unavailable(val reason: String) : JudgeOutcome

    data class Failed(val reason: String) : JudgeOutcome
}

/**
 * The seam between scoring prose and whatever does the scoring.
 *
 * This deliberately names no provider, no model and no credential. The shipped
 * implementation delegates to whatever AI the BOSS host already has configured -
 * including the CLI the user is running the agent with - and a direct HTTP
 * implementation can be added later without the evaluator changing.
 *
 * Implementations never throw: every failure is an outcome.
 */
interface JudgeClient {
    suspend fun judge(request: JudgeRequest): JudgeOutcome

    /** Cheap readiness probe, for reporting rather than gating. */
    suspend fun availability(): JudgeAvailability
}

data class JudgeAvailability(
    val available: Boolean,
    val detail: String,
    /** Model that would grade, when the implementation can say in advance. */
    val modelId: String? = null,
)

/** A client for builds with no judge wired up at all. */
object NoJudgeClient : JudgeClient {
    private const val REASON = "No judge is configured for this build."

    override suspend fun judge(request: JudgeRequest): JudgeOutcome =
        JudgeOutcome.Unavailable(REASON)

    override suspend fun availability(): JudgeAvailability =
        JudgeAvailability(available = false, detail = REASON)
}
