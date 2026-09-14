package ai.rever.boss.plugin.dynamic.bottest

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.dynamic.bottest.core.BotTestRunner
import ai.rever.boss.plugin.dynamic.bottest.core.DefaultEvaluators
import ai.rever.boss.plugin.dynamic.bottest.core.Evaluator
import ai.rever.boss.plugin.dynamic.bottest.core.FileSuiteRepository
import ai.rever.boss.plugin.dynamic.bottest.core.HttpTransport
import ai.rever.boss.plugin.dynamic.bottest.core.JudgeClient
import ai.rever.boss.plugin.dynamic.bottest.core.LlmJudgeEvaluator
import ai.rever.boss.plugin.dynamic.bottest.core.NoJudgeClient
import ai.rever.boss.plugin.dynamic.bottest.core.JdkHttpTransport
import ai.rever.boss.plugin.dynamic.bottest.core.SuiteListReport
import ai.rever.boss.plugin.dynamic.bottest.core.SuiteLoadResult
import ai.rever.boss.plugin.dynamic.bottest.core.SuiteReport
import ai.rever.boss.plugin.dynamic.bottest.core.SuiteRepository

/**
 * Every tool this plugin contributes is prefixed, both to namespace the surface
 * and to stay clear of the names BossTerm reserves for its own built-ins.
 */
internal const val TOOL_PREFIX = "bottest_"

/**
 * Tool names owned by BossTerm's built-ins and the terminal-tab bridge. The
 * bridge silently skips a plugin tool that collides with one of these, so a
 * collision is invisible at runtime - [BotTestMcpToolsTest] guards against it
 * instead.
 *
 * Source: boss-plugins PLUGIN_DEVELOPMENT.md section 9.
 */
internal val RESERVED_TOOL_NAMES: Set<String> = setOf(
    "list_tabs", "get_active_tab", "list_panes", "read_scrollback", "search_output",
    "get_last_command", "read_debug_console", "send_input", "send_signal",
    "run_in_panel", "run_command", "show_image", "manage_tools", "run_in_sidebar", "cli",
)

/**
 * MCP tools contributed by the Bot Test plugin.
 *
 * Registered from [BotTestDynamicPlugin.register], so these appear on the `boss`
 * MCP server while the plugin is active and are removed when it is disabled or
 * unloaded. Agents see them as `mcp__boss__bottest_*`.
 *
 * The tools read suite files and make HTTP calls to the target described in a
 * suite. They never execute anything from a suite - a suite is pure data - and
 * never run a shell command.
 */
internal class BotTestMcpToolProvider(
    override val providerId: String,
    private val pluginVersion: String,
    private val repository: SuiteRepository = FileSuiteRepository(FileSuiteRepository.defaultRoot()),
    private val judgeClient: JudgeClient = NoJudgeClient,
    private val transportFactory: () -> HttpTransport = { JdkHttpTransport() },
) : McpToolProvider {

    private fun runner(useJudge: Boolean): BotTestRunner {
        val evaluators: List<Evaluator> = if (useJudge) {
            DefaultEvaluators.all + LlmJudgeEvaluator(judgeClient)
        } else {
            DefaultEvaluators.all
        }
        return BotTestRunner(transportFactory(), evaluators)
    }

    override fun tools(): List<McpToolDefinition> = listOf(
        McpToolDefinition(
            name = "${TOOL_PREFIX}info",
            description = "Report the Bot Test plugin's status and which evaluation " +
                "capabilities are currently available. Use this to check the plugin is " +
                "loaded and reachable before calling other bottest_* tools.",
            inputSchema = INFO_SCHEMA,
            readOnly = true,
            handler = McpToolHandler { args -> info(args.boolean("verbose") ?: false) },
        ),
        McpToolDefinition(
            name = "${TOOL_PREFIX}list_suites",
            description = "List the conversational test suites available to run, with each " +
                "suite's id, name, description, test count and categories. Call this first to " +
                "discover the suite_id that bottest_run_suite needs.",
            readOnly = true,
            handler = McpToolHandler { listSuites() },
        ),
        McpToolDefinition(
            name = "${TOOL_PREFIX}run_suite",
            description = "Run a conversational test suite against the chatbot endpoint the " +
                "suite configures, and return pass/fail counts, an overall score, per-category " +
                "scores, latency, and the reason each failing test failed. Sends HTTP requests " +
                "to the configured target. Cases carrying a natural-language rubric are also " +
                "scored by an LLM judge using the AI already configured in BOSS; pass judge=false " +
                "to skip that.",
            inputSchema = RUN_SUITE_SCHEMA,
            readOnly = false,
            handler = McpToolHandler { args -> runSuite(args) },
        ),
    )

    private suspend fun info(verbose: Boolean): McpToolResult {
        val judge = judgeClient.availability()
        val lines = mutableListOf(
            "Bot Test $pluginVersion",
            "Status: suite files, deterministic evaluation and rubric scoring are available.",
            "Not implemented yet: baselines/regression, UI.",
            "LLM judge: ${if (judge.available) "available" else "unavailable"} - ${judge.detail}" +
                judge.modelId?.let { " (model: $it)" }.orEmpty(),
            "Available tools: ${tools().joinToString(", ") { it.name }}",
        )
        if (verbose) {
            lines += "Provider id: $providerId"
            lines += "Evaluators: http_success, response_non_empty, latency, required_phrases, " +
                "forbidden_phrases, llm_judge"
        }
        return McpToolResult(lines.joinToString("\n"))
    }

    private suspend fun listSuites(): McpToolResult = try {
        McpToolResult(SuiteListReport.render(repository.list(), repository.storageExists()))
    } catch (e: Exception) {
        McpToolResult("Could not list suites: ${e.message ?: e::class.simpleName}", isError = true)
    }

    private suspend fun runSuite(args: McpToolArgs): McpToolResult {
        val suiteId = args.string("suite_id")?.trim()
        if (suiteId.isNullOrBlank()) {
            return McpToolResult(
                "Missing required argument: suite_id. Call ${TOOL_PREFIX}list_suites to see available ids.",
                isError = true,
            )
        }

        val useJudge = args.boolean("judge") ?: true
        val timeoutOverrideMs = args.int("timeout_ms")?.toLong()
        if (timeoutOverrideMs != null && timeoutOverrideMs <= 0) {
            return McpToolResult("timeout_ms must be a positive number of milliseconds.", isError = true)
        }

        val loaded = try {
            repository.load(suiteId)
        } catch (e: Exception) {
            return McpToolResult(
                "Could not read suite \"$suiteId\": ${e.message ?: e::class.simpleName}",
                isError = true,
            )
        }

        val suite = when (loaded) {
            is SuiteLoadResult.Loaded -> loaded.suite

            is SuiteLoadResult.NotFound -> return McpToolResult(
                buildString {
                    append("No suite with id \"${loaded.id}\".")
                    if (loaded.available.isEmpty()) {
                        append(" No suites are available; call ${TOOL_PREFIX}list_suites for where they are read from.")
                    } else {
                        append(" Available: ${loaded.available.joinToString(", ")}")
                    }
                },
                isError = true,
            )

            is SuiteLoadResult.Invalid -> return McpToolResult(
                "Suite \"${loaded.id}\" is not valid:\n" + loaded.errors.joinToString("\n") { "- $it" },
                isError = true,
            )
        }

        val target = if (timeoutOverrideMs == null) {
            suite.target
        } else {
            suite.target.copy(timeoutMs = timeoutOverrideMs)
        }

        return try {
            val result = runner(useJudge).run(target, suite.cases)
            McpToolResult(SuiteReport.render(suite, result))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The runner already contains per-case failures; reaching here means
            // something outside a case broke.
            McpToolResult(
                "Suite \"${suite.id}\" could not be run: ${e.message ?: e::class.simpleName}",
                isError = true,
            )
        }
    }

    private companion object {
        private const val INFO_SCHEMA =
            """{"type":"object","properties":{"verbose":{"type":"boolean",""" +
            """"description":"Include provider id and the evaluator list."}}}"""

        private const val RUN_SUITE_SCHEMA =
            """{"type":"object","properties":{""" +
            """"suite_id":{"type":"string","description":"Id of the suite to run, as reported by bottest_list_suites."},""" +
            """"timeout_ms":{"type":"integer","description":"Optional per-request timeout override in milliseconds. Defaults to the suite's target timeout."},""" +
            """"judge":{"type":"boolean","description":"Score natural-language rubric criteria with an LLM judge, using the AI already configured in BOSS. Defaults to true; only cases that declare a rubric are judged."}""" +
            """},"required":["suite_id"]}"""
    }
}
