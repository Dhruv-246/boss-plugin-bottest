package ai.rever.boss.plugin.dynamic.bottest.core

/**
 * The kind of behaviour a test case probes. Scores are reported per category,
 * because "the bot got 80%" hides that it may be fine on happy paths and weak
 * under adversarial input.
 */
enum class TestCategory(val wireName: String) {
    HAPPY_PATH("happy_path"),
    AMBIGUOUS("ambiguous"),
    OUT_OF_SCOPE("out_of_scope"),
    ADVERSARIAL("adversarial"),
    PERSONA("persona"),
    MULTI_TURN("multi_turn"),
    EDGE_CASE("edge_case"),
    ;

    companion object {
        /** Lookup by `wireName`, for suites authored as JSON/YAML. Null if unknown. */
        fun fromWireName(name: String): TestCategory? =
            entries.firstOrNull { it.wireName.equals(name.trim(), ignoreCase = true) }
    }
}

enum class TurnRole(val wireName: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
}

/** One prior turn, for cases that need context before the input under test. */
data class ConversationTurn(
    val role: TurnRole,
    val content: String,
)

/**
 * What a good response has to satisfy.
 *
 * The structured fields drive the deterministic evaluators. [rubric] carries a
 * natural-language standard that no current evaluator reads - it is the seam an
 * LLM-as-judge evaluator will use, so suites can be authored against it now.
 */
data class Criteria(
    val requiredPhrases: List<String> = emptyList(),
    val forbiddenPhrases: List<String> = emptyList(),
    val maxLatencyMs: Long? = null,
    val caseSensitive: Boolean = false,
    val rubric: String? = null,
) {
    init {
        require(maxLatencyMs == null || maxLatencyMs > 0) {
            "maxLatencyMs must be positive when set, was $maxLatencyMs"
        }
    }
}

/** A single conversational test case. */
data class BotTestCase(
    val id: String,
    val category: TestCategory,
    val input: String,
    val history: List<ConversationTurn> = emptyList(),
    val criteria: Criteria = Criteria(),
    val expectedResponse: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "Test case id must not be blank" }
    }
}
