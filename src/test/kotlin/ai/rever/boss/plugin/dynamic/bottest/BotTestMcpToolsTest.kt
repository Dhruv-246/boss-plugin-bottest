package ai.rever.boss.plugin.dynamic.bottest

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import ai.rever.boss.plugin.api.McpToolArgs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotTestMcpToolsTest {

    private val provider = BotTestMcpToolProvider(
        providerId = "ai.rever.boss.plugin.dynamic.bottest",
        pluginVersion = "0.1.0",
    )

    @Test
    fun `every tool name uses the plugin prefix`() {
        val offenders = provider.tools().map { it.name }.filterNot { it.startsWith(TOOL_PREFIX) }
        assertTrue(offenders.isEmpty(), "Tools missing the '$TOOL_PREFIX' prefix: $offenders")
    }

    @Test
    fun `no tool collides with a BossTerm reserved name`() {
        // A collision is skipped silently by the terminal-tab bridge, so the
        // tool would simply never appear to an agent. Fail the build instead.
        val collisions = provider.tools().map { it.name }.filter { it in RESERVED_TOOL_NAMES }
        assertTrue(collisions.isEmpty(), "Tools collide with reserved BossTerm names: $collisions")
    }

    @Test
    fun `tool names are unique`() {
        val names = provider.tools().map { it.name }
        assertEquals(names.size, names.toSet().size, "Duplicate tool names in $names")
    }

    @Test
    fun `every input schema is a valid JSON Schema object`() {
        provider.tools().forEach { tool ->
            val parsed = Json.parseToJsonElement(tool.inputSchema)
            val obj: JsonObject = parsed.jsonObject
            assertEquals(
                "\"object\"",
                obj["type"].toString(),
                "Tool ${tool.name} must declare a top-level object schema",
            )
            assertTrue(
                obj.containsKey("properties"),
                "Tool ${tool.name} schema is missing a properties block",
            )
        }
    }

    @Test
    fun `every tool has a non-blank description`() {
        provider.tools().forEach { tool ->
            assertTrue(
                tool.description.isNotBlank(),
                "Tool ${tool.name} needs a description - it is what the model reads to pick it",
            )
        }
    }

    @Test
    fun `info reports plugin version and is read only`() = runTest {
        val tool = provider.tools().single { it.name == "${TOOL_PREFIX}info" }
        assertTrue(tool.readOnly, "bottest_info must not declare side effects")

        val result = tool.handler.call(McpToolArgs(emptyMap()))
        assertFalse(result.isError, "bottest_info should succeed with no arguments")
        assertContains(result.text, "0.1.0")
    }

    @Test
    fun `info verbose flag adds provider detail`() = runTest {
        val tool = provider.tools().single { it.name == "${TOOL_PREFIX}info" }

        val terse = tool.handler.call(McpToolArgs(emptyMap()))
        val verbose = tool.handler.call(McpToolArgs(mapOf("verbose" to true)))

        assertFalse(verbose.isError)
        assertTrue(
            verbose.text.length > terse.text.length,
            "verbose=true should return more than the terse form",
        )
        assertContains(verbose.text, "ai.rever.boss.plugin.dynamic.bottest")
    }
}
