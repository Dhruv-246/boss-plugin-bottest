package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Renders a run as JSON for an MCP client.
 *
 * JSON rather than prose because the consumer is an agent deciding what to fix:
 * a fixed shape it can index beats a paragraph it has to parse. Output is
 * bounded - failure lists and detail strings are capped - so a suite with
 * hundreds of failures cannot flood an agent's context.
 */
object SuiteReport {

    private val pretty = Json { prettyPrint = true }

    const val MAX_FAILURES_REPORTED = 50
    private const val MAX_DETAIL_CHARS = 300

    fun render(suite: TestSuite, result: SuiteResult): String {
        val failures = result.results.filter { it.status != TestStatus.PASSED }
        val reported = failures.take(MAX_FAILURES_REPORTED)

        val report = buildJsonObject {
            put(
                "suite",
                buildJsonObject {
                    put("id", suite.id)
                    put("name", suite.name)
                    put("target", redactUrl(suite.target.url))
                },
            )

            put(
                "summary",
                buildJsonObject {
                    put("total", result.total)
                    put("passed", result.passed)
                    put("failed", result.failed)
                    put("errors", result.errors)
                    put("overallScore", round(result.score))
                    put("passRate", round(result.passRate))
                    put("errorRate", round(if (result.total == 0) 0.0 else result.errors.toDouble() / result.total))
                    put("averageLatencyMs", averageLatencyMs(result))
                    put("durationMs", result.totalDurationMs)
                },
            )

            put(
                "categories",
                buildJsonArray {
                    result.byCategory.values.forEach { summary ->
                        add(
                            buildJsonObject {
                                put("category", summary.category.wireName)
                                put("total", summary.total)
                                put("passed", summary.passed)
                                put("failed", summary.failed)
                                put("errors", summary.errors)
                                put("passRate", round(summary.passRate))
                                put("meanScore", round(summary.meanScore))
                                put("meanLatencyMs", summary.meanLatencyMs)
                            },
                        )
                    }
                },
            )

            put("failedTestIds", buildJsonArray { failures.forEach { add(it.testCase.id) } })

            put(
                "failures",
                buildJsonArray {
                    reported.forEach { testResult ->
                        add(
                            buildJsonObject {
                                put("id", testResult.testCase.id)
                                put("category", testResult.category.wireName)
                                put("status", testResult.status.name)
                                put("score", round(testResult.score))
                                put("latencyMs", testResult.exchange.latencyMs)
                                testResult.exchange.errorMessage?.let { put("error", truncate(it)) }
                                put(
                                    "reasons",
                                    buildJsonArray {
                                        testResult.failedEvaluations.forEach { evaluation ->
                                            add("${evaluation.evaluatorId}: ${truncate(evaluation.detail)}")
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )

            val notes = notes(suite, result, failures.size)
            if (notes.isNotEmpty()) {
                put("notes", buildJsonArray { notes.forEach { add(it) } })
            }
        }

        return pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), report)
    }

    private fun notes(suite: TestSuite, result: SuiteResult, failureCount: Int): List<String> = buildList {
        if (failureCount > MAX_FAILURES_REPORTED) {
            add("Showing the first $MAX_FAILURES_REPORTED of $failureCount failures; failedTestIds lists them all.")
        }

        val awaitingJudge = suite.casesAwaitingJudge.size
        if (awaitingJudge > 0) {
            add(
                "$awaitingJudge of ${suite.cases.size} cases declare only natural-language criteria. " +
                    "No evaluator checks those yet (the LLM judge is not implemented), so they were " +
                    "scored on transport and latency alone.",
            )
        }

        if (result.errors > 0) {
            add("${result.errors} case(s) never got a usable answer; check the target is reachable before reading scores.")
        }
    }

    private fun averageLatencyMs(result: SuiteResult): Long =
        if (result.results.isEmpty()) 0 else result.results.sumOf { it.exchange.latencyMs } / result.results.size

    private fun round(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    private fun truncate(text: String): String =
        if (text.length <= MAX_DETAIL_CHARS) text else text.take(MAX_DETAIL_CHARS) + "..."

    /**
     * Strips credentials and query string from a URL before it reaches an agent.
     * Targets are frequently reached with a token in the query, and the report
     * is not worth leaking one over.
     */
    internal fun redactUrl(url: String): String = try {
        val uri = URI(url)
        buildString {
            uri.scheme?.let { append(it).append("://") }
            uri.host?.let { append(it) }
            if (uri.port != -1) append(":").append(uri.port)
            uri.path?.let { append(it) }
            if (uri.query != null) append("?<redacted>")
        }.ifBlank { "<unparseable url>" }
    } catch (e: Exception) {
        "<unparseable url>"
    }
}

/** Renders `bottest_list_suites` output. */
object SuiteListReport {

    private val pretty = Json { prettyPrint = true }

    fun render(summaries: List<SuiteSummary>, suitesDirectoryExists: Boolean): String {
        val report = buildJsonObject {
            put("count", summaries.size)
            put(
                "suites",
                buildJsonArray {
                    summaries.forEach { summary ->
                        add(
                            buildJsonObject {
                                put("id", summary.id)
                                put("name", summary.name)
                                put("description", summary.description)
                                put("testCount", summary.testCount)
                                put(
                                    "categories",
                                    buildJsonArray { summary.categories.forEach { add(it.wireName) } },
                                )
                                put("runnable", summary.isRunnable)
                                summary.problem?.let { put("problem", it) }
                            },
                        )
                    }
                },
            )

            if (summaries.isEmpty()) {
                put(
                    "hint",
                    if (suitesDirectoryExists) {
                        "No suite files found. Add a <id>.json suite to the suites directory."
                    } else {
                        "The suites directory does not exist yet. Create it and add a <id>.json suite, " +
                            "or set BOTTEST_SUITES_DIR to point at an existing one."
                    },
                )
            }
        }
        return pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), report)
    }
}
