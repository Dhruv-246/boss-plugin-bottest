package ai.rever.boss.plugin.dynamic.bottest.core

/**
 * Everything an evaluator is allowed to look at: the case that was sent and the
 * exchange that came back.
 */
data class EvaluationContext(
    val testCase: BotTestCase,
    val exchange: BotExchange,
) {
    val input: String get() = testCase.input
    val response: String? get() = exchange.responseText
    val criteria: Criteria get() = testCase.criteria
}

/**
 * One judgement about one response.
 *
 * [skipped] marks an evaluator that had nothing to check - no phrases
 * configured, no latency budget. Skipped results are excluded from scoring, so
 * an unconfigured check never inflates or deflates a score.
 */
data class EvaluationResult(
    val evaluatorId: String,
    val passed: Boolean,
    val score: Double,
    val detail: String,
    val skipped: Boolean = false,
) {
    init {
        require(score in 0.0..1.0) { "score must be in 0.0..1.0, was $score" }
    }

    companion object {
        fun pass(evaluatorId: String, detail: String, score: Double = 1.0) =
            EvaluationResult(evaluatorId, passed = true, score = score, detail = detail)

        fun fail(evaluatorId: String, detail: String, score: Double = 0.0) =
            EvaluationResult(evaluatorId, passed = false, score = score, detail = detail)

        fun skip(evaluatorId: String, detail: String) =
            EvaluationResult(evaluatorId, passed = true, score = 1.0, detail = detail, skipped = true)
    }
}

/**
 * A single check against a response.
 *
 * `evaluate` is `suspend` even though every current implementation is pure and
 * synchronous. That is deliberate: it is what lets an LLM-as-judge evaluator be
 * added later as just another element in the list, with no change to this
 * interface or to the runner.
 */
interface Evaluator {
    val id: String

    suspend fun evaluate(context: EvaluationContext): EvaluationResult
}

/** The bot said something rather than nothing. */
object ResponseNonEmptyEvaluator : Evaluator {
    override val id = "response_non_empty"

    override suspend fun evaluate(context: EvaluationContext): EvaluationResult {
        val response = context.response
        return when {
            response == null -> fail("No response was extracted")
            response.isBlank() -> fail("Response was empty or whitespace only")
            else -> EvaluationResult.pass(id, "Response had ${response.length} characters")
        }
    }

    private fun fail(detail: String) = EvaluationResult.fail(id, detail)
}

/** The transport round trip succeeded. */
object HttpSuccessEvaluator : Evaluator {
    override val id = "http_success"

    override suspend fun evaluate(context: EvaluationContext): EvaluationResult {
        val exchange = context.exchange
        return if (exchange.isSuccess) {
            EvaluationResult.pass(id, "HTTP ${exchange.statusCode ?: 200}")
        } else {
            EvaluationResult.fail(
                id,
                "${exchange.outcome}${exchange.errorMessage?.let { ": $it" }.orEmpty()}",
            )
        }
    }
}

/** The response arrived inside the case's latency budget, when one is set. */
object LatencyEvaluator : Evaluator {
    override val id = "latency"

    override suspend fun evaluate(context: EvaluationContext): EvaluationResult {
        val budget = context.criteria.maxLatencyMs
            ?: return EvaluationResult.skip(id, "No latency budget set")

        val actual = context.exchange.latencyMs
        return if (actual <= budget) {
            EvaluationResult.pass(id, "${actual}ms within ${budget}ms budget")
        } else {
            EvaluationResult.fail(id, "${actual}ms exceeded ${budget}ms budget")
        }
    }
}

/**
 * Every required phrase appears. Partial credit is proportional, so a response
 * matching three of four phrases scores 0.75 while still failing.
 */
object RequiredPhrasesEvaluator : Evaluator {
    override val id = "required_phrases"

    override suspend fun evaluate(context: EvaluationContext): EvaluationResult {
        val required = context.criteria.requiredPhrases
        if (required.isEmpty()) return EvaluationResult.skip(id, "No required phrases set")

        val response = context.response
            ?: return EvaluationResult.fail(id, "No response to search")

        val ignoreCase = !context.criteria.caseSensitive
        val missing = required.filterNot { response.contains(it, ignoreCase = ignoreCase) }
        val found = required.size - missing.size

        return if (missing.isEmpty()) {
            EvaluationResult.pass(id, "All ${required.size} required phrases present")
        } else {
            EvaluationResult.fail(
                id,
                "Missing ${missing.size} of ${required.size}: ${missing.joinToString(", ") { "\"$it\"" }}",
                score = found.toDouble() / required.size,
            )
        }
    }
}

/** No forbidden phrase appears. Any hit fails outright. */
object ForbiddenPhrasesEvaluator : Evaluator {
    override val id = "forbidden_phrases"

    override suspend fun evaluate(context: EvaluationContext): EvaluationResult {
        val forbidden = context.criteria.forbiddenPhrases
        if (forbidden.isEmpty()) return EvaluationResult.skip(id, "No forbidden phrases set")

        val response = context.response
            ?: return EvaluationResult.skip(id, "No response to search")

        val ignoreCase = !context.criteria.caseSensitive
        val present = forbidden.filter { response.contains(it, ignoreCase = ignoreCase) }

        return if (present.isEmpty()) {
            EvaluationResult.pass(id, "None of the ${forbidden.size} forbidden phrases present")
        } else {
            EvaluationResult.fail(
                id,
                "Contains forbidden: ${present.joinToString(", ") { "\"$it\"" }}",
            )
        }
    }
}

/** The deterministic set the runner uses unless told otherwise. */
object DefaultEvaluators {
    val all: List<Evaluator> = listOf(
        HttpSuccessEvaluator,
        ResponseNonEmptyEvaluator,
        LatencyEvaluator,
        RequiredPhrasesEvaluator,
        ForbiddenPhrasesEvaluator,
    )
}
