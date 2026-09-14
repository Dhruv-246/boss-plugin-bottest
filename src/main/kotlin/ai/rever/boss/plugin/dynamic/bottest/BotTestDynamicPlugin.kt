package ai.rever.boss.plugin.dynamic.bottest

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

/**
 * Bot Test - a testing framework for conversational AI inside BOSS.
 *
 * Registers the panel and the bottest_* MCP tool surface. Suites are read from
 * the filesystem (see FileSuiteRepository.defaultRoot) and run with the
 * deterministic evaluators. LLM-as-judge scoring, baselines and the real UI are
 * not implemented yet.
 */
class BotTestDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.bottest"
    override val displayName: String = "Bot Test"
    override val version: String = PLUGIN_VERSION
    override val description: String =
        "Testing framework for conversational AI: latency, per-category scoring, and regression tracking"
    override val author: String = "Dhruv-246"
    override val url: String = "https://github.com/Dhruv-246/boss-plugin-bottest"

    override fun register(context: PluginContext) {
        context.panelRegistry.registerPanel(BotTestInfo) { ctx, panelInfo ->
            BotTestComponent(ctx, panelInfo)
        }
        // Contributes the bottest_* tools to the `boss` MCP server. The host's
        // TrackingPluginContext unregisters this automatically on disable/unload.
        // Defaults resolve the suites directory and a JDK-backed runner; both are
        // constructor parameters so tests can substitute fakes.
        context.registerMcpToolProvider(BotTestMcpToolProvider(pluginId, PLUGIN_VERSION))
    }

    override fun dispose() {
        // Nothing held yet. Providers and registries are released by the host.
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
