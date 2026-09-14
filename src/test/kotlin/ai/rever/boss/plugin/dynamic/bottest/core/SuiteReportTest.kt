package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuiteReportTest {

    private val okBody = """{"response":"Your order ships on Tuesday."}"""

    private suspend fun report(
        cases: List<BotTestCase>,
        body: String = okBody,
        stepMs: Long = 100,
        suiteUrl: String = "http://localhost:8000/chat",
    ): kotlinx.serialization.json.JsonObject {
        val testSuite = suite(cases = cases, url = suiteUrl)
        val runner = BotTestRunner(
            FakeHttpTransport.responding(body),
            nanoTime = FakeClock(stepMs = stepMs)::nanoTime,
        )
        val result = runner.run(testSuite.target, testSuite.cases)
        return Json.parseToJsonElement(SuiteReport.render(testSuite, result)).jsonObject
    }

    @Test
    fun `report carries suite identity and a summary block`() = runTest {
        val json = report(listOf(testCase(id = "t1")))

        assertEquals("customer-support", json["suite"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Customer Support Tests", json["suite"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val summary = json["summary"]!!.jsonObject
        assertEquals("1", summary["total"]!!.jsonPrimitive.content)
        assertEquals("1", summary["passed"]!!.jsonPrimitive.content)
        assertEquals("0", summary["failed"]!!.jsonPrimitive.content)
        assertEquals("0", summary["errors"]!!.jsonPrimitive.content)
        assertEquals("100", summary["averageLatencyMs"]!!.jsonPrimitive.content)
    }

    @Test
    fun `summary reports score pass rate and error rate`() = runTest {
        val json = report(
            listOf(
                testCase(id = "pass"),
                testCase(id = "fail", criteria = Criteria(requiredPhrases = listOf("refund"))),
            ),
        )

        val summary = json["summary"]!!.jsonObject
        assertEquals("2", summary["total"]!!.jsonPrimitive.content)
        assertEquals("1", summary["passed"]!!.jsonPrimitive.content)
        assertEquals("1", summary["failed"]!!.jsonPrimitive.content)
        assertEquals(0.5, summary["passRate"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.0, summary["errorRate"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `network failure shows up as an error rate not a crash`() = runTest {
        val testSuite = suite(cases = listOf(testCase(id = "t1"), testCase(id = "t2")))
        val runner = BotTestRunner(
            FakeHttpTransport.throwing(TransportConnectionException("connection refused")),
        )

        val result = runner.run(testSuite.target, testSuite.cases)
        val json = Json.parseToJsonElement(SuiteReport.render(testSuite, result)).jsonObject

        val summary = json["summary"]!!.jsonObject
        assertEquals("2", summary["errors"]!!.jsonPrimitive.content)
        assertEquals(1.0, summary["errorRate"]!!.jsonPrimitive.content.toDouble())
        assertTrue(
            json["notes"]!!.jsonArray.any { it.jsonPrimitive.content.contains("never got a usable answer") },
        )
    }

    @Test
    fun `per category scores are reported by wire name`() = runTest {
        val json = report(
            listOf(
                testCase(id = "h1", category = TestCategory.HAPPY_PATH),
                testCase(id = "a1", category = TestCategory.ADVERSARIAL),
                testCase(
                    id = "a2",
                    category = TestCategory.ADVERSARIAL,
                    criteria = Criteria(forbiddenPhrases = listOf("Tuesday")),
                ),
            ),
        )

        val categories = json["categories"]!!.jsonArray.associate { entry ->
            entry.jsonObject["category"]!!.jsonPrimitive.content to entry.jsonObject
        }

        assertEquals(setOf("happy_path", "adversarial"), categories.keys)
        assertEquals(1.0, categories["happy_path"]!!["passRate"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.5, categories["adversarial"]!!["passRate"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `failures list ids and the reason each failed`() = runTest {
        val json = report(
            listOf(
                testCase(id = "pass_01"),
                testCase(id = "ambiguous_04", criteria = Criteria(requiredPhrases = listOf("refund"))),
            ),
        )

        assertEquals(
            listOf("ambiguous_04"),
            json["failedTestIds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val failure = json["failures"]!!.jsonArray.single().jsonObject
        assertEquals("ambiguous_04", failure["id"]!!.jsonPrimitive.content)
        assertEquals("FAILED", failure["status"]!!.jsonPrimitive.content)
        val reasons = failure["reasons"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(reasons.any { it.startsWith("required_phrases:") && it.contains("refund") }, reasons.toString())
    }

    @Test
    fun `errored cases are distinguished from failed ones`() = runTest {
        val testSuite = suite(cases = listOf(testCase(id = "boom")))
        val runner = BotTestRunner(FakeHttpTransport.throwing(TransportTimeoutException("timed out")))

        val result = runner.run(testSuite.target, testSuite.cases)
        val json = Json.parseToJsonElement(SuiteReport.render(testSuite, result)).jsonObject

        val failure = json["failures"]!!.jsonArray.single().jsonObject
        assertEquals("ERROR", failure["status"]!!.jsonPrimitive.content)
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("timed out"))
    }

    @Test
    fun `rubric only cases are flagged as unjudged`() = runTest {
        val json = report(
            listOf(
                testCase(id = "r1", criteria = Criteria(rubric = "Asks a clarifying question")),
                testCase(id = "r2", criteria = Criteria(rubric = "Stays in character")),
            ),
        )

        val notes = json["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            notes.any { it.contains("2 of 2") && it.contains("LLM judge is not implemented") },
            notes.toString(),
        )
    }

    @Test
    fun `failure list is capped so a large suite cannot flood the agent`() = runTest {
        val many = (1..SuiteReport.MAX_FAILURES_REPORTED + 10).map { index ->
            testCase(id = "fail_$index", criteria = Criteria(requiredPhrases = listOf("refund")))
        }

        val json = report(many)

        assertEquals(many.size, json["failedTestIds"]!!.jsonArray.size, "every id is still listed")
        assertEquals(SuiteReport.MAX_FAILURES_REPORTED, json["failures"]!!.jsonArray.size)
        assertTrue(json["notes"]!!.jsonArray.any { it.jsonPrimitive.content.contains("Showing the first") })
    }

    @Test
    fun `target url is reported without credentials or query string`() {
        assertEquals(
            "https://api.example.com/chat?<redacted>",
            SuiteReport.redactUrl("https://api.example.com/chat?api_key=secret-token"),
        )
        assertEquals("http://localhost:8000/chat", SuiteReport.redactUrl("http://localhost:8000/chat"))
        assertEquals("<unparseable url>", SuiteReport.redactUrl("not a url at all ::::"))
    }

    @Test
    fun `report never echoes target headers`() = runTest {
        val testSuite = TestSuite(
            id = "s",
            name = "s",
            description = "",
            target = BotTarget(
                url = "http://localhost:8000/chat",
                headers = mapOf("Authorization" to "Bearer super-secret-value"),
            ),
            cases = listOf(testCase()),
        )
        val runner = BotTestRunner(FakeHttpTransport.responding(okBody))

        val rendered = SuiteReport.render(testSuite, runner.run(testSuite.target, testSuite.cases))

        assertFalse(rendered.contains("super-secret-value"), "a bearer token must never reach the agent")
        assertFalse(rendered.contains("Authorization"))
    }

    @Test
    fun `list report exposes no filesystem paths`() {
        val rendered = SuiteListReport.render(
            listOf(
                SuiteSummary(
                    id = "alpha",
                    name = "Alpha",
                    description = "first",
                    testCount = 3,
                    categories = listOf(TestCategory.HAPPY_PATH),
                ),
            ),
            suitesDirectoryExists = true,
        )

        val json = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("1", json["count"]!!.jsonPrimitive.content)
        val entry = json["suites"]!!.jsonArray.single().jsonObject
        assertEquals("alpha", entry["id"]!!.jsonPrimitive.content)
        assertEquals("3", entry["testCount"]!!.jsonPrimitive.content)
        assertEquals(listOf("happy_path"), entry["categories"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(rendered.contains("/"), "no path separators should appear in the listing")
    }

    @Test
    fun `list report distinguishes empty from missing directory`() {
        val missing = SuiteListReport.render(emptyList(), suitesDirectoryExists = false)
        assertTrue(missing.contains("does not exist"), missing)

        val empty = SuiteListReport.render(emptyList(), suitesDirectoryExists = true)
        assertTrue(empty.contains("No suite files found"), empty)
    }

    @Test
    fun `broken suites are listed as not runnable with their problem`() {
        val rendered = SuiteListReport.render(
            listOf(
                SuiteSummary(
                    id = "broken",
                    name = "broken",
                    description = "",
                    testCount = 0,
                    categories = emptyList(),
                    problem = "target: required",
                ),
            ),
            suitesDirectoryExists = true,
        )

        val entry = Json.parseToJsonElement(rendered).jsonObject["suites"]!!.jsonArray.single().jsonObject
        assertEquals("false", entry["runnable"]!!.jsonPrimitive.content)
        assertEquals("target: required", entry["problem"]!!.jsonPrimitive.content)
    }
}
