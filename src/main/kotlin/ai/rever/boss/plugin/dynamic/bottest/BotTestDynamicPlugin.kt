package ai.rever.boss.plugin.dynamic.bottest

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.bottest.core.FileSuiteRepository

/**
 * Bot Test - a testing framework for conversational AI inside BOSS.
 *
 * Registers the panel and the bottest_* MCP tool surface. Suites are read from
 * the filesystem (see FileSuiteRepository.defaultRoot) and run with the
 * deterministic evaluators plus, for cases carrying a rubric, an LLM judge that
 * borrows whatever AI the host already has. Baselines and the real UI are not
 * implemented yet.
 */
class BotTestDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.bottest"
    override val displayName: String = "Bot Test"
    override val version: String = PLUGIN_VERSION
    override val description: String =
        "Testing framework for conversational AI: latency, per-category scoring, and regression tracking"
    override val author: String = "Dhruv-246"
    override val url: String = "https://github.com/Dhruv-246/boss-plugin-bottest"

    /**
     * Held so the judge can resolve the AI gateway lazily, per call - the gateway
     * contract forbids caching it at registration, because plugin load order is
     * not guaranteed and a null resolved now would be cached forever. Cleared in
     * [dispose] so an unloaded plugin does not retain a host context.
     */
    @Volatile
    private var pluginContext: PluginContext? = null

    override fun register(context: PluginContext) {
        pluginContext = context
        context.panelRegistry.registerPanel(BotTestInfo) { ctx, panelInfo ->
            BotTestComponent(ctx, panelInfo)
        }
        // Contributes the bottest_* tools to the `boss` MCP server. The host's
        // TrackingPluginContext unregisters this automatically on disable/unload.
        // Defaults resolve the suites directory and a JDK-backed transport; all are
        // constructor parameters so tests can substitute fakes.
        context.registerMcpToolProvider(
            BotTestMcpToolProvider(
                providerId = pluginId,
                pluginVersion = PLUGIN_VERSION,
                repository = FileSuiteRepository(FileSuiteRepository.defaultRoot()),
                judgeClient = AiGatewayJudgeClient(contextProvider = { pluginContext }),
            ),
        )
    }

    override fun dispose() {
        pluginContext = null
    }

    companion object {
        /**
         * Kept in sync by hand with `version` in build.gradle.kts, which is the
         * single source of truth and is what gets written into plugin.json at
         * build time. Only the manifest value is load-bearing for the host.
         */
        const val PLUGIN_VERSION: String = "0.1.0"
    }
}
