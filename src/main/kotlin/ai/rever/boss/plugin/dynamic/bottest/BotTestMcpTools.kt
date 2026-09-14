package ai.rever.boss.plugin.dynamic.bottest

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult

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
 * Only the introspection tool exists so far. The suite runner, matrix sweep and
 * comparison tools arrive with the evaluation engine.
 */
internal class BotTestMcpToolProvider(
    override val providerId: String,
    private val pluginVersion: String,
) : McpToolProvider {

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
    )

    private fun info(verbose: Boolean): McpToolResult {
        val lines = mutableListOf(
            "Bot Test $pluginVersion",
            "Status: skeleton - evaluation engine not implemented yet.",
            "Available tools: ${tools().joinToString(", ") { it.name }}",
        )
        if (verbose) {
            lines += "Provider id: $providerId"
            lines += "Planned: suite runner, latency metrics, LLM-as-judge scoring, regression diff."
        }
        return McpToolResult(lines.joinToString("\n"))
    }

    private companion object {
        private const val INFO_SCHEMA =
            """{"type":"object","properties":{"verbose":{"type":"boolean",""" +
            """"description":"Include provider id and planned capabilities."}}}"""
    }
}
