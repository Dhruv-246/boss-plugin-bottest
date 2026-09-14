package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuiteRepositoryTest {

    private val root: Path = createTempDirectory("bottest-suites")

    @AfterTest
    fun cleanUp() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    private fun writeSuite(id: String, body: String = suiteJson(id)) {
        root.resolve("$id.json").writeText(body)
    }

    private fun suiteJson(id: String, tests: Int = 1) = """
        {
          "id": "$id",
          "name": "Suite $id",
          "description": "desc $id",
          "target": { "url": "http://localhost:8000/chat" },
          "tests": [ ${(1..tests).joinToString(",") { """{"id":"t$it","category":"happy_path","input":"hi"}""" }} ]
        }
    """.trimIndent()

    @Test
    fun `list summarises every suite file`() = runTest {
        writeSuite("alpha")
        writeSuite("beta", suiteJson("beta", tests = 3))

        val summaries = FileSuiteRepository(root).list()

        assertEquals(listOf("alpha", "beta"), summaries.map { it.id })
        assertEquals("Suite alpha", summaries[0].name)
        assertEquals("desc beta", summaries[1].description)
        assertEquals(3, summaries[1].testCount)
        assertEquals(listOf(TestCategory.HAPPY_PATH), summaries[1].categories)
        assertTrue(summaries.all { it.isRunnable })
    }

    @Test
    fun `a broken file is listed as a problem rather than disappearing`() = runTest {
        writeSuite("good")
        root.resolve("broken.json").writeText("{ not json")

        val summaries = FileSuiteRepository(root).list()

        assertEquals(listOf("broken", "good"), summaries.map { it.id }.sorted())
        val broken = summaries.single { it.id == "broken" }
        assertFalse(broken.isRunnable)
        val problem = assertNotNull(broken.problem)
        assertTrue(problem.contains("Not valid JSON"), problem)
    }

    @Test
    fun `non json files are ignored`() = runTest {
        writeSuite("real")
        root.resolve("notes.txt").writeText("ignore me")
        root.resolve("nested").createDirectories()

        assertEquals(listOf("real"), FileSuiteRepository(root).list().map { it.id })
    }

    @Test
    fun `a missing directory lists as empty instead of failing`() = runTest {
        val repository = FileSuiteRepository(root.resolve("does-not-exist"))

        assertEquals(emptyList(), repository.list())
        assertFalse(repository.storageExists())
    }

    @Test
    fun `load returns the parsed suite`() = runTest {
        writeSuite("alpha")

        val loaded = FileSuiteRepository(root).load("alpha")

        assertIs<SuiteLoadResult.Loaded>(loaded)
        assertEquals("alpha", loaded.suite.id)
        assertEquals(1, loaded.suite.cases.size)
    }

    @Test
    fun `unknown id reports what is available`() = runTest {
        writeSuite("alpha")
        writeSuite("beta")

        val loaded = FileSuiteRepository(root).load("gamma")

        assertIs<SuiteLoadResult.NotFound>(loaded)
        assertEquals(listOf("alpha", "beta"), loaded.available)
    }

    @Test
    fun `malformed suite reports validation errors on load`() = runTest {
        root.resolve("bad.json").writeText("""{ "id": "bad" }""")

        val loaded = FileSuiteRepository(root).load("bad")

        assertIs<SuiteLoadResult.Invalid>(loaded)
        assertTrue(loaded.errors.any { it.startsWith("target:") }, loaded.errors.toString())
    }

    @Test
    fun `a file whose declared id disagrees with its name is rejected`() = runTest {
        root.resolve("named-one.json").writeText(suiteJson("declared-other"))

        val loaded = FileSuiteRepository(root).load("named-one")

        assertIs<SuiteLoadResult.Invalid>(loaded)
        assertTrue(loaded.errors.single().contains("declares id"), loaded.errors.toString())
    }

    @Test
    fun `path traversal ids cannot escape the suites directory`() = runTest {
        // A real secret one directory up from the suites root.
        val secret = root.parent.resolve("bottest-secret.json")
        secret.writeText(suiteJson("secret"))
        try {
            val repository = FileSuiteRepository(root)

            listOf(
                "../bottest-secret",
                "../../etc/passwd",
                "/etc/passwd",
                "..\\bottest-secret",
                "sub/../../bottest-secret",
            ).forEach { hostileId ->
                val loaded = repository.load(hostileId)
                assertIs<SuiteLoadResult.NotFound>(loaded, "id \"$hostileId\" must not resolve")
            }
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    fun `oversized files are refused rather than read into memory`() = runTest {
        root.resolve("huge.json").writeText(suiteJson("huge"))

        val loaded = FileSuiteRepository(root, maxFileBytes = 10).load("huge")

        assertIs<SuiteLoadResult.Invalid>(loaded)
        assertTrue(loaded.errors.single().contains("larger than"), loaded.errors.toString())
    }

    @Test
    fun `storageExists reflects the directory`() = runTest {
        assertTrue(FileSuiteRepository(root).storageExists())
    }

    @Test
    fun `the bundled example suite parses and is runnable`() = runTest {
        // Guards the shipped example against drifting out of the schema.
        val example = Path.of("examples/basic-suite.json")
        assertTrue(Files.isRegularFile(example), "examples/basic-suite.json should exist")

        val parsed = SuiteParser.parse(Files.readString(example))

        assertIs<SuiteParseResult.Valid>(parsed, "example suite must stay valid: $parsed")
        val suite = parsed.suite
        assertEquals("basic-suite", suite.id)
        assertTrue(suite.cases.size >= 5, "example should cover a handful of cases")
        assertTrue(
            suite.cases.map { it.category }.distinct().size >= 5,
            "example should span several categories",
        )
    }
}
