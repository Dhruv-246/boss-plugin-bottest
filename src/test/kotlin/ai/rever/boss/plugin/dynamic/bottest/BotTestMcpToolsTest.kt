package ai.rever.boss.plugin.dynamic.bottest

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.dynamic.bottest.core.Criteria
import ai.rever.boss.plugin.dynamic.bottest.core.FakeHttpTransport
import ai.rever.boss.plugin.dynamic.bottest.core.FakeJudgeClient
import ai.rever.boss.plugin.dynamic.bottest.core.FakeSuiteRepository
import ai.rever.boss.plugin.dynamic.bottest.core.JudgeClient
import ai.rever.boss.plugin.dynamic.bottest.core.NoJudgeClient
import ai.rever.boss.plugin.dynamic.bottest.core.SuiteLoadResult
import ai.rever.boss.plugin.dynamic.bottest.core.TestCategory
import ai.rever.boss.plugin.dynamic.bottest.core.TransportConnectionException
import ai.rever.boss.plugin.dynamic.bottest.core.suite
import ai.rever.boss.plugin.dynamic.bottest.core.testCase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotTestMcpToolsTest {

    private val okBody = """{"response":"Your order ships on Tuesday."}"""

    private fun provider(
        repository: FakeSuiteRepository = FakeSuiteRepository(),
        transport: FakeHttpTransport = FakeHttpTransport.responding(okBody),
        judgeClient: JudgeClient = NoJudgeClient,
    ) = BotTestMcpToolProvider(
        providerId = "ai.rever.boss.plugin.dynamic.bottest",
        pluginVersion = "0.1.0",
        repository = repository,
        judgeClient = judgeClient,
        transportFactory = { transport },
    )

    private suspend fun call(
        name: String,
        args: Map<String, Any?> = emptyMap(),
        repository: FakeSuiteRepository = FakeSuiteRepository(),
        transport: FakeHttpTransport = FakeHttpTransport.responding(okBody),
        judgeClient: JudgeClient = NoJudgeClient,
    ) = provider(repository, transport, judgeClient)
        .tools()
        .single { it.name == name }
        .handler
        .call(McpToolArgs(args))

    // ---------- tool surface ----------

    @Test
    fun `every tool name uses the plugin prefix`() {
        val offenders = provider().tools().map { it.name }.filterNot { it.startsWith(TOOL_PREFIX) }
        assertTrue(offenders.isEmpty(), "Tools missing the '$TOOL_PREFIX' prefix: $offenders")
    }

    @Test
    fun `no tool collides with a BossTerm reserved name`() {
        // A collision is skipped silently by the terminal-tab bridge, so the
        // tool would simply never appear to an agent. Fail the build instead.
        val collisions = provider().tools().map { it.name }.filter { it in RESERVED_TOOL_NAMES }
        assertTrue(collisions.isEmpty(), "Tools collide with reserved BossTerm names: $collisions")
    }

    @Test
    fun `tool names are unique`() {
        val names = provider().tools().map { it.name }
        assertEquals(names.size, names.toSet().size, "Duplicate tool names in $names")
    }

    @Test
    fun `the expected tools are exposed`() {
        assertEquals(
            setOf("bottest_info", "bottest_list_suites", "bottest_run_suite"),
            provider().tools().map { it.name }.toSet(),
        )
    }

    @Test
    fun `every input schema is a valid JSON Schema object`() {
        provider().tools().forEach { tool ->
            val schema: JsonObject = Json.parseToJsonElement(tool.inputSchema).jsonObject
            assertEquals(
                "\"object\"",
                schema["type"].toString(),
                "Tool ${tool.name} must declare a top-level object schema",
            )
            assertTrue(schema.containsKey("properties"), "Tool ${tool.name} schema needs properties")
        }
    }

    @Test
    fun `run_suite requires suite_id and documents timeout_ms`() {
        val schema = Json.parseToJsonElement(
            provider().tools().single { it.name == "bottest_run_suite" }.inputSchema,
        ).jsonObject

        assertEquals(
            listOf("suite_id"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val properties = schema["properties"]!!.jsonObject
        assertTrue(properties.containsKey("suite_id"))
        assertEquals("integer", properties["timeout_ms"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only run_suite declares side effects`() {
        val tools = provider().tools().associateBy { it.name }

        assertTrue(tools.getValue("bottest_info").readOnly)
        assertTrue(tools.getValue("bottest_list_suites").readOnly)
        assertFalse(
            tools.getValue("bottest_run_suite").readOnly,
            "run_suite sends HTTP requests to the target, so it is not read-only",
        )
    }

    @Test
    fun `every tool has a non-blank description`() {
        provider().tools().forEach { tool ->
            assertTrue(tool.description.isNotBlank(), "Tool ${tool.name} needs a description")
        }
    }

    // ---------- bottest_info ----------

    @Test
    fun `info reports plugin version`() = runTest {
        val result = call("bottest_info")

        assertFalse(result.isError)
        assertContains(result.text, "0.1.0")
    }

    @Test
    fun `info verbose flag adds provider detail`() = runTest {
        val terse = call("bottest_info")
        val verbose = call("bottest_info", mapOf("verbose" to true))

        assertTrue(verbose.text.length > terse.text.length)
        assertContains(verbose.text, "ai.rever.boss.plugin.dynamic.bottest")
    }

    // ---------- bottest_list_suites ----------

    @Test
    fun `list_suites reports the available suites`() = runTest {
        val result = call(
            "bottest_list_suites",
            repository = FakeSuiteRepository.holding(suite(cases = listOf(testCase(), testCase(id = "t2")))),
        )

        assertFalse(result.isError)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("1", json["count"]!!.jsonPrimitive.content)
        val entry = json["suites"]!!.jsonArray.single().jsonObject
        assertEquals("customer-support", entry["id"]!!.jsonPrimitive.content)
        assertEquals("2", entry["testCount"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list_suites on an empty store hints instead of failing`() = runTest {
        val result = call("bottest_list_suites", repository = FakeSuiteRepository(exists = false))

        assertFalse(result.isError)
        assertContains(result.text, "does not exist")
    }

    @Test
    fun `list_suites surfaces a storage failure as a tool error`() = runTest {
        val exploding = FakeSuiteRepository(onList = { throw IllegalStateException("disk gone") })

        val result = call("bottest_list_suites", repository = exploding)

        assertTrue(result.isError)
        assertContains(result.text, "disk gone")
    }

    // ---------- bottest_run_suite ----------

    @Test
    fun `run_suite runs the suite and returns a structured report`() = runTest {
        val target = suite(
            cases = listOf(
                testCase(id = "happy_01", category = TestCategory.HAPPY_PATH),
                testCase(
                    id = "ambiguous_04",
                    category = TestCategory.AMBIGUOUS,
                    criteria = Criteria(requiredPhrases = listOf("refund")),
                ),
            ),
        )

        val result = call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support"),
            repository = FakeSuiteRepository.holding(target),
        )

        assertFalse(result.isError, result.text)
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("customer-support", json["suite"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val summary = json["summary"]!!.jsonObject
        assertEquals("2", summary["total"]!!.jsonPrimitive.content)
        assertEquals("1", summary["passed"]!!.jsonPrimitive.content)
        assertEquals("1", summary["failed"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("ambiguous_04"),
            json["failedTestIds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(json["categories"]!!.jsonArray.size == 2)
    }

    @Test
    fun `run_suite without suite_id explains how to find one`() = runTest {
        val result = call("bottest_run_suite")

        assertTrue(result.isError)
        assertContains(result.text, "suite_id")
        assertContains(result.text, "bottest_list_suites")
    }

    @Test
    fun `run_suite with an unknown id lists what is available`() = runTest {
        val result = call(
            "bottest_run_suite",
            mapOf("suite_id" to "nope"),
            repository = FakeSuiteRepository.holding(suite()),
        )

        assertTrue(result.isError)
        assertContains(result.text, "No suite with id \"nope\"")
        assertContains(result.text, "customer-support")
    }

    @Test
    fun `run_suite on a malformed suite returns the validation errors`() = runTest {
        val broken = FakeSuiteRepository(
            suites = mapOf(
                "broken" to SuiteLoadResult.Invalid("broken", listOf("target: required", "tests: required")),
            ),
        )

        val result = call("bottest_run_suite", mapOf("suite_id" to "broken"), repository = broken)

        assertTrue(result.isError)
        assertContains(result.text, "target: required")
        assertContains(result.text, "tests: required")
    }

    @Test
    fun `run_suite rejects a non positive timeout override`() = runTest {
        val result = call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support", "timeout_ms" to 0),
            repository = FakeSuiteRepository.holding(suite()),
        )

        assertTrue(result.isError)
        assertContains(result.text, "timeout_ms")
    }

    @Test
    fun `run_suite applies a timeout override to the request`() = runTest {
        val transport = FakeHttpTransport.responding(okBody)

        call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support", "timeout_ms" to 1500),
            repository = FakeSuiteRepository.holding(suite()),
            transport = transport,
        )

        assertEquals(1500, transport.requests.single().timeoutMs)
    }

    @Test
    fun `run_suite reports network failure as errors rather than failing the call`() = runTest {
        val result = call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support"),
            repository = FakeSuiteRepository.holding(
                suite(cases = listOf(testCase(id = "a"), testCase(id = "b"))),
            ),
            transport = FakeHttpTransport.throwing(TransportConnectionException("connection refused")),
        )

        // The tool call itself succeeds; the unreachable bot is data in the report.
        assertFalse(result.isError, result.text)
        val summary = Json.parseToJsonElement(result.text).jsonObject["summary"]!!.jsonObject
        assertEquals("2", summary["errors"]!!.jsonPrimitive.content)
        assertEquals(1.0, summary["errorRate"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `one failing case does not stop the rest of the suite`() = runTest {
        val transport = FakeHttpTransport.failingAfter(
            successes = 1,
            body = okBody,
            error = TransportConnectionException("connection refused"),
        )

        val result = call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support"),
            repository = FakeSuiteRepository.holding(
                suite(cases = listOf(testCase(id = "a"), testCase(id = "b"), testCase(id = "c"))),
            ),
            transport = transport,
        )

        val summary = Json.parseToJsonElement(result.text).jsonObject["summary"]!!.jsonObject
        assertEquals("3", summary["total"]!!.jsonPrimitive.content)
        assertEquals("1", summary["passed"]!!.jsonPrimitive.content)
        assertEquals("2", summary["errors"]!!.jsonPrimitive.content)
    }

    @Test
    fun `run_suite output never contains target credentials`() = runTest {
        val credentialled = suite(url = "https://api.example.com/chat?api_key=super-secret-value")

        val result = call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support"),
            repository = FakeSuiteRepository.holding(credentialled),
        )

        assertFalse(result.text.contains("super-secret-value"), "query credentials must be redacted")
        assertContains(result.text, "<redacted>")
    }

    // ---------- LLM judge ----------

    @Test
    fun `run_suite schema documents the judge switch`() {
        val schema = Json.parseToJsonElement(
            provider().tools().single { it.name == "bottest_run_suite" }.inputSchema,
        ).jsonObject

        val judge = schema["properties"]!!.jsonObject["judge"]!!.jsonObject
        assertEquals("boolean", judge["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `run_suite judges rubric cases by default`() = runTest {
        val judge = FakeJudgeClient.verdict(passed = false, score = 0.2, reasoning = "Assumed the appointment")

        val result = call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support"),
            repository = FakeSuiteRepository.holding(
                suite(cases = listOf(testCase(id = "amb_01", criteria = Criteria(rubric = "Asks a clarification")))),
            ),
            judgeClient = judge,
        )

        assertEquals(1, judge.requests.size, "the rubric case should have been judged")
        val summary = Json.parseToJsonElement(result.text).jsonObject["summary"]!!.jsonObject
        assertEquals("1", summary["failed"]!!.jsonPrimitive.content)
    }

    @Test
    fun `judge false skips the judge entirely`() = runTest {
        val judge = FakeJudgeClient.verdict(passed = false, score = 0.0)

        val result = call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support", "judge" to false),
            repository = FakeSuiteRepository.holding(
                suite(cases = listOf(testCase(id = "amb_01", criteria = Criteria(rubric = "Asks a clarification")))),
            ),
            judgeClient = judge,
        )

        assertTrue(judge.requests.isEmpty(), "judge=false must not call the judge")
        val summary = Json.parseToJsonElement(result.text).jsonObject["summary"]!!.jsonObject
        assertEquals("1", summary["passed"]!!.jsonPrimitive.content)
    }

    @Test
    fun `cases without a rubric never reach the judge`() = runTest {
        val judge = FakeJudgeClient()

        call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support"),
            repository = FakeSuiteRepository.holding(suite(cases = listOf(testCase(id = "plain")))),
            judgeClient = judge,
        )

        assertTrue(judge.requests.isEmpty(), "a deterministic suite should cost no tokens")
    }

    @Test
    fun `an unavailable judge degrades instead of failing the run`() = runTest {
        val result = call(
            "bottest_run_suite",
            mapOf("suite_id" to "customer-support"),
            repository = FakeSuiteRepository.holding(
                suite(cases = listOf(testCase(id = "amb_01", criteria = Criteria(rubric = "Asks a clarification")))),
            ),
            judgeClient = FakeJudgeClient.unavailable("No AI Gateway plugin is installed"),
        )

        assertFalse(result.isError, result.text)
        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("1", json["summary"]!!.jsonObject["passed"]!!.jsonPrimitive.content)
        val notes = json["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            notes.any { it.contains("not judged") && it.contains("No AI Gateway plugin") },
            "the report must say the rubric went unchecked: $notes",
        )
    }

    @Test
    fun `info reports judge availability`() = runTest {
        val available = call("bottest_info", judgeClient = FakeJudgeClient())
        assertContains(available.text, "LLM judge: available")
        assertContains(available.text, "fake-model")

        val missing = call("bottest_info", judgeClient = FakeJudgeClient.unavailable("No AI Gateway plugin is installed"))
        assertContains(missing.text, "LLM judge: unavailable")
        assertContains(missing.text, "No AI Gateway plugin")
    }
}
