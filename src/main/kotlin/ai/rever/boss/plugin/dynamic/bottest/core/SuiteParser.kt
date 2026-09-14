package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses suite JSON into the existing [TestSuite] / [BotTestCase] / [BotTarget]
 * models. No new duplicate models, and nothing in a suite file is ever executed
 * - a suite is pure data.
 *
 * Every problem is collected rather than thrown on first sight, so a malformed
 * suite reports all of its errors in one pass instead of one per edit.
 */
object SuiteParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Ids address files and cross the MCP boundary, so they stay boring. */
    internal val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun parse(text: String): SuiteParseResult {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return SuiteParseResult.Invalid(listOf("Not valid JSON: ${e.message}"))
        }

        if (root !is JsonObject) {
            return SuiteParseResult.Invalid(listOf("Suite must be a JSON object, found ${root.typeName()}"))
        }

        val errors = mutableListOf<String>()

        val id = root.stringOrNull("id")
        when {
            id == null -> errors += "id: required"
            id.isBlank() -> errors += "id: must not be blank"
            !SAFE_ID.matches(id) -> errors +=
                "id: may only contain letters, digits, dot, underscore and hyphen (got \"$id\")"
        }

        val name = root.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: id.orEmpty()
        val description = root.stringOrNull("description").orEmpty()

        val target = when (val targetElement = root["target"]) {
            null -> {
                errors += "target: required"
                null
            }

            !is JsonObject -> {
                errors += "target: must be an object, found ${targetElement.typeName()}"
                null
            }

            else -> parseTarget(targetElement, errors)
        }

        val cases = when (val testsElement = root["tests"]) {
            null -> {
                errors += "tests: required"
                emptyList()
            }

            !is JsonArray -> {
                errors += "tests: must be an array, found ${testsElement.typeName()}"
                emptyList()
            }

            else -> {
                if (testsElement.isEmpty()) errors += "tests: must contain at least one test"
                testsElement.mapIndexedNotNull { index, element -> parseCase(element, index, errors) }
            }
        }

        cases.groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .sorted()
            .forEach { errors += "tests: duplicate test id \"$it\"" }

        if (errors.isNotEmpty() || id == null || target == null) {
            return SuiteParseResult.Invalid(errors)
        }

        return SuiteParseResult.Valid(
            TestSuite(id = id, name = name, description = description, target = target, cases = cases),
        )
    }

    private fun parseTarget(element: JsonObject, errors: MutableList<String>): BotTarget? {
        val url = element.stringOrNull("url")
        if (url.isNullOrBlank()) {
            errors += "target.url: required"
            return null
        }

        val timeoutMs = element.longOrNull("timeoutMs") ?: 30_000
        if (timeoutMs <= 0) {
            errors += "target.timeoutMs: must be positive (got $timeoutMs)"
            return null
        }

        val requestElement = element["request"] as? JsonObject
        val responseElement = element["response"] as? JsonObject

        val messageField = requestElement?.stringOrNull("messageField") ?: "message"
        if (messageField.isBlank()) {
            errors += "target.request.messageField: must not be blank"
            return null
        }

        return BotTarget(
            url = url,
            method = element.stringOrNull("method")?.takeIf { it.isNotBlank() } ?: "POST",
            timeoutMs = timeoutMs,
            headers = element.stringMap("headers"),
            request = RequestConfig(
                messageField = messageField,
                historyField = requestElement?.stringOrNull("historyField")?.takeIf { it.isNotBlank() },
                extraFields = requestElement?.stringMap("extraFields") ?: emptyMap(),
            ),
            response = ResponseConfig(
                responsePath = responseElement?.stringOrNull("responsePath") ?: "response",
            ),
        )
    }

    private fun parseCase(element: kotlinx.serialization.json.JsonElement, index: Int, errors: MutableList<String>): BotTestCase? {
        val where = "tests[$index]"
        if (element !is JsonObject) {
            errors += "$where: must be an object, found ${element.typeName()}"
            return null
        }

        var valid = true

        val id = element.stringOrNull("id")
        if (id.isNullOrBlank()) {
            errors += "$where.id: required"
            valid = false
        }

        val categoryName = element.stringOrNull("category")
        val category = when {
            categoryName == null -> {
                errors += "$where.category: required (one of ${TestCategory.entries.joinToString(", ") { it.wireName }})"
                valid = false
                null
            }

            else -> TestCategory.fromWireName(categoryName).also {
                if (it == null) {
                    errors += "$where.category: unknown category \"$categoryName\" " +
                        "(expected one of ${TestCategory.entries.joinToString(", ") { c -> c.wireName }})"
                    valid = false
                }
            }
        }

        // Present, but deliberately allowed to be blank: probing how a bot
        // handles an empty or whitespace-only message is a legitimate edge case.
        val input = element.stringOrNull("input")
        if (input == null) {
            errors += "$where.input: required"
            valid = false
        }

        val criteria = parseCriteria(element["criteria"], where, errors) ?: run { valid = false; null }
        val history = parseHistory(element["history"], where, errors) ?: run { valid = false; null }

        if (!valid || id == null || category == null || input == null || criteria == null || history == null) {
            return null
        }

        return BotTestCase(
            id = id,
            category = category,
            input = input,
            history = history,
            criteria = criteria,
            expectedResponse = element.stringOrNull("expectedResponse"),
            metadata = element.stringMap("metadata"),
        )
    }

    /**
     * Criteria accept two shapes.
     *
     * An **array of strings** is a natural-language standard; it is preserved in
     * [Criteria.rubric] but no current evaluator reads it, because judging prose
     * needs the LLM evaluator that does not exist yet.
     *
     * An **object** is the structured, deterministic form the evaluators act on.
     */
    private fun parseCriteria(
        element: kotlinx.serialization.json.JsonElement?,
        where: String,
        errors: MutableList<String>,
    ): Criteria? = when {
        element == null -> Criteria()

        element is JsonArray -> {
            val entries = element.mapIndexedNotNull { i, item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: run { errors += "$where.criteria[$i]: must be a string"; null }
            }
            Criteria(rubric = entries.takeIf { it.isNotEmpty() }?.joinToString("\n"))
        }

        element is JsonObject -> {
            val maxLatencyMs = element.longOrNull("maxLatencyMs")
            if (maxLatencyMs != null && maxLatencyMs <= 0) {
                errors += "$where.criteria.maxLatencyMs: must be positive (got $maxLatencyMs)"
                null
            } else {
                Criteria(
                    requiredPhrases = element.stringList("requiredPhrases"),
                    forbiddenPhrases = element.stringList("forbiddenPhrases"),
                    maxLatencyMs = maxLatencyMs,
                    caseSensitive = (element["caseSensitive"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
                    rubric = element.stringOrNull("rubric"),
                )
            }
        }

        else -> {
            errors += "$where.criteria: must be an array of strings or an object, found ${element.typeName()}"
            null
        }
    }

    private fun parseHistory(
        element: kotlinx.serialization.json.JsonElement?,
        where: String,
        errors: MutableList<String>,
    ): List<ConversationTurn>? = when {
        element == null -> emptyList()

        element !is JsonArray -> {
            errors += "$where.history: must be an array, found ${element.typeName()}"
            null
        }

        else -> element.mapIndexedNotNull { i, item ->
            val turn = item as? JsonObject
            if (turn == null) {
                errors += "$where.history[$i]: must be an object"
                return@mapIndexedNotNull null
            }
            val roleName = turn.stringOrNull("role")
            val role = TurnRole.entries.firstOrNull { it.wireName.equals(roleName, ignoreCase = true) }
            val content = turn.stringOrNull("content")
            when {
                role == null -> {
                    errors += "$where.history[$i].role: must be one of " +
                        TurnRole.entries.joinToString(", ") { it.wireName }
                    null
                }

                content == null -> {
                    errors += "$where.history[$i].content: required"
                    null
                }

                else -> ConversationTurn(role, content)
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.typeName(): String = when (this) {
    is JsonObject -> "an object"
    is JsonArray -> "an array"
    is JsonPrimitive -> if (isString) "a string" else "a literal"
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?: emptyList()

private fun JsonObject.stringMap(key: String): Map<String, String> =
    (this[key] as? JsonObject)
        ?.mapNotNull { (k, v) -> ((v as? JsonPrimitive)?.content)?.let { k to it } }
        ?.toMap()
        ?: emptyMap()
