package ai.rever.boss.plugin.dynamic.bottest.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuiteParserTest {

    private fun valid(text: String): TestSuite {
        val parsed = SuiteParser.parse(text)
        assertIs<SuiteParseResult.Valid>(parsed, "expected a valid suite, got $parsed")
        return parsed.suite
    }

    private fun errors(text: String): List<String> {
        val parsed = SuiteParser.parse(text)
        assertIs<SuiteParseResult.Invalid>(parsed, "expected an invalid suite, got $parsed")
        return parsed.errors
    }

    private val minimal = """
        {
          "id": "customer-support",
          "name": "Customer Support Tests",
          "description": "Basic tests for the support chatbot",
          "target": { "url": "http://localhost:8000/chat", "method": "POST" },
          "tests": [
            { "id": "happy_01", "category": "happy_path", "input": "How do I reset my password?" }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses the documented suite shape`() {
        val suite = valid(minimal)

        assertEquals("customer-support", suite.id)
        assertEquals("Customer Support Tests", suite.name)
        assertEquals("Basic tests for the support chatbot", suite.description)
        assertEquals("http://localhost:8000/chat", suite.target.url)
        assertEquals("POST", suite.target.method)
        assertEquals(1, suite.cases.size)
        assertEquals(TestCategory.HAPPY_PATH, suite.cases.single().category)
    }

    @Test
    fun `target defaults fill in when omitted`() {
        val suite = valid(minimal)

        assertEquals(30_000, suite.target.timeoutMs)
        assertEquals("message", suite.target.request.messageField)
        assertEquals("response", suite.target.response.responsePath)
        assertNull(suite.target.request.historyField)
    }

    @Test
    fun `full target configuration is preserved`() {
        val suite = valid(
            """
            {
              "id": "s", "target": {
                "url": "https://api.example/v1/chat",
                "method": "PUT",
                "timeoutMs": 5000,
                "headers": { "X-Tenant": "acme" },
                "request": {
                  "messageField": "query",
                  "historyField": "messages",
                  "extraFields": { "model": "small" }
                },
                "response": { "responsePath": "choices.0.message.content" }
              },
              "tests": [ { "id": "t1", "category": "edge_case", "input": "hi" } ]
            }
            """.trimIndent(),
        )

        val target = suite.target
        assertEquals("PUT", target.method)
        assertEquals(5000, target.timeoutMs)
        assertEquals(mapOf("X-Tenant" to "acme"), target.headers)
        assertEquals("query", target.request.messageField)
        assertEquals("messages", target.request.historyField)
        assertEquals(mapOf("model" to "small"), target.request.extraFields)
        assertEquals("choices.0.message.content", target.response.responsePath)
    }

    @Test
    fun `every category is accepted by wire name`() {
        val tests = TestCategory.entries.joinToString(",") { category ->
            """{ "id": "${category.wireName}_1", "category": "${category.wireName}", "input": "hi" }"""
        }
        val suite = valid(
            """{ "id":"all", "target": { "url":"http://x/chat" }, "tests": [$tests] }""",
        )

        assertEquals(TestCategory.entries.toSet(), suite.cases.map { it.category }.toSet())
    }

    @Test
    fun `string criteria become a rubric that no evaluator checks yet`() {
        val suite = valid(
            """
            {
              "id": "s", "target": { "url": "http://x/chat" },
              "tests": [ {
                "id": "ambiguous_01", "category": "ambiguous",
                "input": "Can you change my appointment?",
                "criteria": [ "Does not assume which appointment", "Asks a clarification" ]
              } ]
            }
            """.trimIndent(),
        )

        val criteria = suite.cases.single().criteria
        assertEquals("Does not assume which appointment\nAsks a clarification", criteria.rubric)
        assertTrue(criteria.requiredPhrases.isEmpty())
        assertTrue(criteria.isRubricOnly)
        assertEquals(1, suite.casesAwaitingJudge.size)
    }

    @Test
    fun `object criteria drive the deterministic evaluators`() {
        val suite = valid(
            """
            {
              "id": "s", "target": { "url": "http://x/chat" },
              "tests": [ {
                "id": "t1", "category": "happy_path", "input": "hi",
                "criteria": {
                  "requiredPhrases": ["password"],
                  "forbiddenPhrases": ["as an AI"],
                  "maxLatencyMs": 2000,
                  "caseSensitive": true,
                  "rubric": "Also judged later"
                }
              } ]
            }
            """.trimIndent(),
        )

        val criteria = suite.cases.single().criteria
        assertEquals(listOf("password"), criteria.requiredPhrases)
        assertEquals(listOf("as an AI"), criteria.forbiddenPhrases)
        assertEquals(2000, criteria.maxLatencyMs)
        assertTrue(criteria.caseSensitive)
        assertTrue(!criteria.isRubricOnly, "structured fields mean an evaluator can act on it")
    }

    @Test
    fun `history expected response and metadata survive the round trip`() {
        val suite = valid(
            """
            {
              "id": "s", "target": { "url": "http://x/chat" },
              "tests": [ {
                "id": "mt", "category": "multi_turn", "input": "And the second?",
                "history": [
                  { "role": "user", "content": "Tickets 1 and 2" },
                  { "role": "assistant", "content": "Ticket 1 is open" }
                ],
                "expectedResponse": "Ticket 2 is closed",
                "metadata": { "owner": "support" }
              } ]
            }
            """.trimIndent(),
        )

        val case = suite.cases.single()
        assertEquals(2, case.history.size)
        assertEquals(TurnRole.USER, case.history[0].role)
        assertEquals("Ticket 1 is open", case.history[1].content)
        assertEquals("Ticket 2 is closed", case.expectedResponse)
        assertEquals(mapOf("owner" to "support"), case.metadata)
    }

    @Test
    fun `name defaults to the id when omitted`() {
        val suite = valid(
            """{ "id":"only-id", "target": { "url":"http://x/chat" }, "tests":[{"id":"t","category":"happy_path","input":"hi"}] }""",
        )
        assertEquals("only-id", suite.name)
        assertEquals("", suite.description)
    }

    @Test
    fun `non JSON input is reported clearly`() {
        val problems = errors("this is not json")
        assertTrue(problems.single().contains("Not valid JSON"), problems.toString())
    }

    @Test
    fun `a JSON array is not a suite`() {
        val problems = errors("[]")
        assertTrue(problems.single().contains("must be a JSON object"), problems.toString())
    }

    @Test
    fun `missing required fields are all reported at once`() {
        val problems = errors("{}")

        assertTrue(problems.any { it.startsWith("id:") }, problems.toString())
        assertTrue(problems.any { it.startsWith("target:") }, problems.toString())
        assertTrue(problems.any { it.startsWith("tests:") }, problems.toString())
    }

    @Test
    fun `unsafe suite ids are rejected`() {
        val problems = errors(
            """{ "id":"../../etc/passwd", "target":{"url":"http://x/chat"}, "tests":[{"id":"t","category":"happy_path","input":"hi"}] }""",
        )
        assertTrue(problems.any { it.startsWith("id:") }, problems.toString())
    }

    @Test
    fun `missing target url is reported`() {
        val problems = errors(
            """{ "id":"s", "target": { "method":"POST" }, "tests":[{"id":"t","category":"happy_path","input":"hi"}] }""",
        )
        assertTrue(problems.any { it.contains("target.url") }, problems.toString())
    }

    @Test
    fun `empty test list is rejected`() {
        val problems = errors("""{ "id":"s", "target":{"url":"http://x/chat"}, "tests": [] }""")
        assertTrue(problems.any { it.contains("at least one test") }, problems.toString())
    }

    @Test
    fun `unknown category names the valid options`() {
        val problems = errors(
            """{ "id":"s", "target":{"url":"http://x/chat"}, "tests":[{"id":"t","category":"happpy_path","input":"hi"}] }""",
        )
        val message = problems.single { it.contains("category") }
        assertTrue(message.contains("happpy_path"), message)
        assertTrue(message.contains("happy_path"), "the error should list the valid options: $message")
    }

    @Test
    fun `per test problems are located by index`() {
        val problems = errors(
            """
            {
              "id":"s", "target":{"url":"http://x/chat"},
              "tests":[
                {"id":"ok","category":"happy_path","input":"hi"},
                {"category":"happy_path","input":"missing id"},
                {"id":"no-input","category":"happy_path"}
              ]
            }
            """.trimIndent(),
        )

        assertTrue(problems.any { it.startsWith("tests[1].id") }, problems.toString())
        assertTrue(problems.any { it.startsWith("tests[2].input") }, problems.toString())
    }

    @Test
    fun `a blank input is a valid edge case not a malformed suite`() {
        // Probing how a bot handles an empty message is exactly what the
        // edge_case category is for, so blank input must survive validation.
        val suite = valid(
            """{ "id":"s","target":{"url":"http://x/chat"},
               "tests":[{"id":"edge_01","category":"edge_case","input":"   "}] }""",
        )

        assertEquals("   ", suite.cases.single().input)
    }

    @Test
    fun `duplicate test ids are rejected`() {
        val problems = errors(
            """
            {
              "id":"s", "target":{"url":"http://x/chat"},
              "tests":[
                {"id":"dup","category":"happy_path","input":"one"},
                {"id":"dup","category":"ambiguous","input":"two"}
              ]
            }
            """.trimIndent(),
        )
        assertTrue(problems.any { it.contains("duplicate test id") }, problems.toString())
    }

    @Test
    fun `invalid latency budget is rejected rather than throwing`() {
        val problems = errors(
            """
            {
              "id":"s", "target":{"url":"http://x/chat"},
              "tests":[{"id":"t","category":"happy_path","input":"hi","criteria":{"maxLatencyMs":0}}]
            }
            """.trimIndent(),
        )
        assertTrue(problems.any { it.contains("maxLatencyMs") }, problems.toString())
    }

    @Test
    fun `malformed history is reported`() {
        val problems = errors(
            """
            {
              "id":"s", "target":{"url":"http://x/chat"},
              "tests":[{"id":"t","category":"multi_turn","input":"hi",
                "history":[{"role":"wizard","content":"nope"}]}]
            }
            """.trimIndent(),
        )
        assertTrue(problems.any { it.contains("history[0].role") }, problems.toString())
    }

    @Test
    fun `wrong criteria type is reported`() {
        val problems = errors(
            """{ "id":"s","target":{"url":"http://x/chat"},"tests":[{"id":"t","category":"happy_path","input":"hi","criteria":42}] }""",
        )
        assertTrue(problems.any { it.contains("criteria") }, problems.toString())
    }
}
