package ai.rever.boss.plugin.dynamic.bottest.core

/**
 * Scores a response against its case's natural-language rubric.
 *
 * Slots into the same [Evaluator] list as the deterministic checks - which is
 * what the suspend signature on [Evaluator.evaluate] was reserved for - so the
 * runner needs no knowledge that a judge exists.
 *
 * **A judge that cannot run is skipped, never failed.** An unavailable or broken
 * judge is a fact about this machine's configuration, not about the chatbot, and
 * scoring it as a failure would quietly report a working bot as a broken one.
 * Skipped evaluations are excluded from the score, so a run without a judge is
 * scored on its deterministic checks alone and says so.
 */
class LlmJudgeEvaluator(
    private val client: JudgeClient,
) : Evaluator {

    override val id = ID

    override suspend fun evaluate(context: EvaluationContext): EvaluationResult {
        val rubric = context.criteria.rubric?.takeIf { it.isNotBlank() }
            ?: return EvaluationResult.skip(id, "No rubric set")

        if (!context.exchange.isSuccess) {
            return EvaluationResult.skip(id, "Exchange failed, so there is no response to judge")
        }

        val response = context.response?.takeIf { it.isNotBlank() }
            ?: return EvaluationResult.skip(id, "Response was empty, so there is nothing to judge")

        val outcome = client.judge(
            JudgeRequest(
                testId = context.testCase.id,
                category = context.testCase.category,
                input = context.input,
                response = response,
                rubric = rubric,
                history = context.testCase.history,
            ),
        )

        return when (outcome) {
            is JudgeOutcome.Judged -> {
                val verdict = outcome.verdict
                val attribution = verdict.modelId.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
                if (verdict.passed) {
                    EvaluationResult.pass(id, "${verdict.reasoning}$attribution", score = verdict.score)
                } else {
                    EvaluationResult.fail(id, "${verdict.reasoning}$attribution", score = verdict.score)
                }
            }

            is JudgeOutcome.Unavailable -> EvaluationResult.skip(id, "Judge unavailable: ${outcome.reason}")

            is JudgeOutcome.Failed -> EvaluationResult.skip(id, "Judge failed: ${outcome.reason}")
        }
    }

    companion object {
        const val ID = "llm_judge"
    }
}
