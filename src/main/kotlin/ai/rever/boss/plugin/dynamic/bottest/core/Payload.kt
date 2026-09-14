package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Outcome of pulling the bot's reply out of a response body. */
sealed interface Extraction {
    data class Found(val text: String) : Extraction

    data class Missing(val reason: String) : Extraction
}

/**
 * Request encoding and response extraction.
 *
 * `kotlinx.serialization` is a parent-first shared package in the BOSS host
 * (`PluginClassLoader.defaultSharedPackages`), so the host supplies it at
 * runtime and the thin plugin jar does not need to bundle it.
 */
internal object Payload {

    private val json = Json { ignoreUnknownKeys = true }

    /** Builds the request body for [testCase] according to [config]. */
    fun buildRequestBody(testCase: BotTestCase, config: RequestConfig): String =
        buildJsonObject {
            put(config.messageField, testCase.input)

            val historyField = config.historyField
            if (historyField != null && testCase.history.isNotEmpty()) {
                put(
                    historyField,
                    buildJsonArray {
                        testCase.history.forEach { turn ->
                            add(
                                buildJsonObject {
                                    put("role", turn.role.wireName)
                                    put("content", turn.content)
                                },
                            )
                        }
                    },
                )
            }

            // Last, so a target can deliberately override a generated field.
            config.extraFields.forEach { (key, value) -> put(key, value) }
        }.toString()

    /**
     * Resolves [path] against [body].
     *
     * A blank path returns the body verbatim, which supports bots that answer in
     * plain text. Otherwise the path is split on `.`, with numeric segments
     * indexing arrays.
     */
    fun extractResponse(body: String, path: String): Extraction {
        if (path.isBlank()) {
            return Extraction.Found(body.trim())
        }

        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return Extraction.Missing("Response body is not valid JSON: ${e.message}")
        }

        var current = root
        val segments = path.split('.').filter { it.isNotBlank() }
        segments.forEachIndexed { index, segment ->
            val soFar = segments.take(index + 1).joinToString(".")
            val parent = current
            current = when {
                parent is JsonObject -> parent[segment]
                    ?: return Extraction.Missing("No field '$soFar' in response body")

                parent is JsonArray && segment.toIntOrNull() != null ->
                    parent.getOrNull(segment.toInt())
                        ?: return Extraction.Missing("Index '$soFar' out of bounds (size ${parent.size})")

                else -> return Extraction.Missing("Cannot resolve '$soFar': parent is not an object or array")
            }
        }

        return when (val leaf = current) {
            is JsonNull -> Extraction.Missing("Field '$path' is null")
            is JsonPrimitive -> Extraction.Found(leaf.content)
            else -> Extraction.Missing("Field '$path' is not a scalar value")
        }
    }
}
