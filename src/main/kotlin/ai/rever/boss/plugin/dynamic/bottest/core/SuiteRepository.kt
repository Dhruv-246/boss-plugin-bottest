package ai.rever.boss.plugin.dynamic.bottest.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

interface SuiteRepository {
    suspend fun list(): List<SuiteSummary>

    suspend fun load(id: String): SuiteLoadResult

    /**
     * Whether the backing store exists at all. Lets callers tell "no suites yet"
     * apart from "you are pointed at the wrong directory".
     */
    suspend fun storageExists(): Boolean
}

/**
 * Suites as `<id>.json` files in one directory, so they can be authored in an
 * editor and kept in version control. The host's `PluginStorageProvider` is
 * key/value and would make hand-editing awkward, which is why this does not use
 * it.
 *
 * The directory is not required to exist: a missing directory lists as empty
 * rather than failing, so a fresh install is not an error state.
 */
class FileSuiteRepository(
    private val root: Path,
    /** Guards against a huge or runaway file being read into memory. */
    private val maxFileBytes: Long = 1024 * 1024,
) : SuiteRepository {

    override suspend fun list(): List<SuiteSummary> = withContext(Dispatchers.IO) {
        val normalizedRoot = root.normalize()
        if (!Files.isDirectory(normalizedRoot)) return@withContext emptyList()

        Files.list(normalizedRoot).use { stream ->
            stream.filter { it.isRegularFile() && it.extension.equals("json", ignoreCase = true) }
                .sorted(compareBy { it.name })
                .map { path -> summarize(path) }
                .toList()
        }
    }

    override suspend fun storageExists(): Boolean = withContext(Dispatchers.IO) {
        Files.isDirectory(root.normalize())
    }

    override suspend fun load(id: String): SuiteLoadResult = withContext(Dispatchers.IO) {
        val path = resolveSuitePath(id)
            ?: return@withContext SuiteLoadResult.NotFound(id, availableIds())

        if (!path.isRegularFile()) {
            return@withContext SuiteLoadResult.NotFound(id, availableIds())
        }

        val text = readWithinLimit(path)
            ?: return@withContext SuiteLoadResult.Invalid(
                id,
                listOf("Suite file is larger than $maxFileBytes bytes"),
            )

        when (val parsed = SuiteParser.parse(text)) {
            is SuiteParseResult.Valid -> {
                val suite = parsed.suite
                if (suite.id != id) {
                    SuiteLoadResult.Invalid(
                        id,
                        listOf("id: file is named \"$id.json\" but declares id \"${suite.id}\""),
                    )
                } else {
                    SuiteLoadResult.Loaded(suite)
                }
            }

            is SuiteParseResult.Invalid -> SuiteLoadResult.Invalid(id, parsed.errors)
        }
    }

    /**
     * Resolves `<id>.json` under [root], or null if [id] is unsafe.
     *
     * Two independent guards, because this id arrives from an agent over MCP:
     * the id must match [SuiteParser.SAFE_ID], and the resolved path must still
     * sit inside the root after normalisation.
     */
    private fun resolveSuitePath(id: String): Path? {
        if (!SuiteParser.SAFE_ID.matches(id)) return null

        val normalizedRoot = root.normalize().toAbsolutePath()
        val candidate = normalizedRoot.resolve("$id.json").normalize().toAbsolutePath()
        return candidate.takeIf { it.startsWith(normalizedRoot) }
    }

    private fun summarize(path: Path): SuiteSummary {
        val id = path.nameWithoutExtension
        val text = readWithinLimit(path)
            ?: return problemSummary(id, "File is larger than $maxFileBytes bytes")

        return when (val parsed = SuiteParser.parse(text)) {
            is SuiteParseResult.Valid -> {
                val suite = parsed.suite
                SuiteSummary(
                    id = suite.id,
                    name = suite.name,
                    description = suite.description,
                    testCount = suite.cases.size,
                    categories = suite.cases.map { it.category }.distinct().sortedBy { it.ordinal },
                )
            }

            is SuiteParseResult.Invalid -> problemSummary(id, parsed.errors.joinToString("; "))
        }
    }

    private fun problemSummary(id: String, problem: String) = SuiteSummary(
        id = id,
        name = id,
        description = "",
        testCount = 0,
        categories = emptyList(),
        problem = problem,
    )

    private fun readWithinLimit(path: Path): String? = try {
        if (Files.size(path) > maxFileBytes) null else path.readText()
    } catch (e: IOException) {
        null
    }

    private fun availableIds(): List<String> {
        val normalizedRoot = root.normalize()
        if (!Files.isDirectory(normalizedRoot)) return emptyList()
        return Files.list(normalizedRoot).use { stream ->
            stream.filter { it.isRegularFile() && it.extension.equals("json", ignoreCase = true) }
                .map { it.nameWithoutExtension }
                .sorted()
                .toList()
        }
    }

    companion object {
        /**
         * Where suites live by default. `BOTTEST_SUITES_DIR` overrides it, which
         * is also how the directory is pointed at a project checkout.
         */
        fun defaultRoot(): Path {
            val override = System.getenv("BOTTEST_SUITES_DIR")
            return if (!override.isNullOrBlank()) {
                Path.of(override)
            } else {
                Path.of(System.getProperty("user.home"), ".bottest", "suites")
            }
        }
    }
}
