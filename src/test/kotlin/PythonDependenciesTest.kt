/*
 * xemantic-kotlin-script-python - Stateful Python, embedded with Jep, in Kotlin scripts
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.kotlin.script.python

import com.xemantic.kotlin.test.assert
import jep.Interpreter
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PythonDependenciesTest {

    @Test
    fun `should find no metadata in code without metadata block`() {
        // when
        val metadata = PythonDependencies.scriptMetadata("x = 1")

        // then
        assert(metadata == null)
    }

    @Test
    fun `should find metadata of script block`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            import sys
            # /// script
            # requires-python = ">=3.11"
            #
            # dependencies = ["numpy"]
            # ///
            print(sys.argv)
            """.trimIndent()
        )

        // then
        assert(metadata == "requires-python = \">=3.11\"\n\ndependencies = [\"numpy\"]\n")
    }

    @Test
    fun `should find metadata of script block ending the code`() {
        // when
        val metadata = PythonDependencies.scriptMetadata("# /// script\n# dependencies = []\n# ///")

        // then
        assert(metadata == "dependencies = []\n")
    }

    @Test
    fun `should find metadata of script block with CRLF line endings`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            "import sys\r\n# /// script\r\n#\r\n# dependencies = [\"numpy\"]\r\n# ///\r\nprint(sys.argv)\r\n"
        )

        // then
        assert(metadata == "\ndependencies = [\"numpy\"]\n")
    }

    @Test
    fun `should split metadata content only at line feeds`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            "# /// script\n# title = \"a\u000cb\u0085c d\"\n# ///\n"
        )

        // then
        // str.splitlines of the reference implementation would also break the TOML string at these characters
        assert(metadata == "title = \"a\u000cb\u0085c d\"\n")
    }

    @Test
    fun `should not wait for other installation when code has no metadata`() {
        // given
        val interpreter = Proxy.newProxyInstance(
            Interpreter::class.java.classLoader, arrayOf(Interpreter::class.java)
        ) { _, method, _ -> error("Unexpected call of Interpreter.${method.name}") } as Interpreter

        // when
        val state = synchronized(PythonDependencies) {
            // as if another thread was installing dependencies
            val installer = thread { PythonDependencies.install("x = 1", interpreter) }
            while (installer.state != Thread.State.BLOCKED && installer.state != Thread.State.TERMINATED) {
                Thread.onSpinWait()
            }
            installer.state
        }

        // then
        assert(state == Thread.State.TERMINATED)
    }

    @Test
    fun `should find no metadata for lone script marker line`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            open('t.py', 'w').write('''
            # /// script
            ''')
            """.trimIndent()
        )

        // then
        assert(metadata == null)
    }

    @Test
    fun `should find no metadata for unclosed script block`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            # /// script
            # dependencies = ["numpy"]
            x = 1
            # ///
            """.trimIndent()
        )

        // then
        assert(metadata == null)
    }

    @Test
    fun `should find no metadata for script block without content`() {
        // when
        val metadata = PythonDependencies.scriptMetadata("# /// script\n# ///\n")

        // then
        assert(metadata == null)
    }

    @Test
    fun `should end script block at last closing line of consecutive comments`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            # /// script
            # dependencies = ["numpy"]
            # ///
            # embedded = '''
            # ///
            # '''
            # ///
            # a comment
            """.trimIndent()
        )

        // then
        assert(metadata == "dependencies = [\"numpy\"]\n///\nembedded = '''\n///\n'''\n")
    }

    @Test
    fun `should find no metadata for script block within block of other type`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            # /// other
            # /// script
            # dependencies = ["numpy"]
            # ///
            """.trimIndent()
        )

        // then
        assert(metadata == null)
    }

    @Test
    fun `should fail on multiple script blocks`() {
        // when
        val exception = assertFailsWith<PythonDependencyException> {
            PythonDependencies.scriptMetadata(
                """
                # /// script
                # dependencies = ["numpy"]
                # ///
                x = 1
                # /// script
                # dependencies = ["pandas"]
                # ///
                """.trimIndent()
            )
        }

        // then
        assert(exception.message == "Multiple '# /// script' metadata blocks, only one is allowed")
    }

    @Test
    fun `should find no metadata in many script marker lines without backtracking`() {
        // given
        // a backtracking regex retries all the following lines for each marker, hanging for hours,
        // and a recursive one overflows the stack
        val code = "# /// script\n".repeat(200_000)

        // when
        val metadata = PythonDependencies.scriptMetadata(code)

        // then
        assert(metadata == null)
    }

    @Test
    fun `should plan with more imported modules than fit on command line`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        // about 6 MB of names, more than ARG_MAX of macOS and Linux, and the 32767 characters of Windows,
        // and enough to hang if the distributions of the environment are scanned again for each module
        val imported = List(300_000) { "imported_module_$it" }

        // when
        val plan = PythonDependencies.plan("dependencies = [\"nonexistent-dependency\"]\n", directory, imported)

        // then
        assert(("install" to "nonexistent-dependency") in plan)
    }

    @Test
    fun `should plan for metadata with quotes, backslashes, line breaks and non-ASCII characters`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        val metadata = "title = \"\\\"q\\\" \\\\ \u00fc\u2028 \uD83D\uDE00 \\u0001\"\r\n" +
            "dependencies = [\n  \"nonexistent-dependency\",\n]\n"

        // when
        val plan = PythonDependencies.plan(metadata, directory, listOf("sys", "\u00fcber"))

        // then
        assert(plan.filter { it.first == "install" } == listOf("install" to "nonexistent-dependency"))
    }

    @Test
    fun `should plan without tomllib of Python before 3_11`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        // None in sys.modules makes the import fail with ModuleNotFoundError, like on Python 3.10,
        // and without tomli, the copy vendored by pip is used
        val code = "import sys; sys.modules.update(dict.fromkeys(['tomllib', 'tomli'], None))\n" +
            PythonDependencies.PLAN

        // when
        val (exitCode, output) = runProcess(
            listOf(PythonEnvironment.executable, "-c", code, directory),
            input = """{"metadata": "dependencies = [\"nonexistent-dependency\"]\n", "modules": []}"""
        )

        // then
        assert(exitCode == 0 && "install nonexistent-dependency" in output.lines())
    }

    @Test
    fun `should fail plan on invalid metadata without tomllib of Python before 3_11`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        val code = "import sys; sys.modules.update(dict.fromkeys(['tomllib', 'tomli'], None))\n" +
            PythonDependencies.PLAN

        // when
        val (exitCode, output) = runProcess(
            listOf(PythonEnvironment.executable, "-c", code, directory),
            input = """{"metadata": "dependencies = \n", "modules": []}"""
        )

        // then
        assert(exitCode != 0 && output.startsWith("Invalid '# /// script' metadata: "))
    }

    @Test
    fun `should fail plan with clear message without any TOML parser`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        val code = "import sys; sys.modules.update(dict.fromkeys(['tomllib', 'tomli', 'pip'], None))\n" +
            PythonDependencies.PLAN

        // when
        val (exitCode, output) = runProcess(
            listOf(PythonEnvironment.executable, "-c", code, directory),
            input = """{"metadata": "dependencies = [\"nonexistent-dependency\"]\n", "modules": []}"""
        )

        // then
        assert(exitCode != 0 && output == PythonDependencies.NO_TOML_PARSER_MESSAGE)
    }

    @Test
    fun `should constrain distribution of dependency directory to versions its installed dependents require`() {
        // given
        PythonEnvironment.initialize()
        withDependencyDirectory { directory ->
            directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2,>=1")
            directory.writeDistribution("issue28-other-dependent", "1.0", "Issue28_Dependency!=1.5")
            directory.writeDistribution("issue28-dependency", "1.9")

            // when
            val plan = PythonDependencies.plan(
                "dependencies = [\"issue28-new\"]\n", directory.toString(), emptyList()
            )

            // then
            val constraints = plan.filter { it.first == "constraint" }.map { it.second }
            assert(constraints.filter { "issue28" in it.lowercase() } == listOf("issue28-dependency!=1.5,<2,>=1"))
        }
    }

    @Test
    fun `should not constrain distribution of dependency directory which no other one requires`() {
        // given
        PythonEnvironment.initialize()
        withDependencyDirectory { directory ->
            directory.writeDistribution("issue28-dependency", "1.0")

            // when
            val plan = PythonDependencies.plan(
                "dependencies = [\"issue28-dependency>=2\"]\n", directory.toString(), emptyList()
            )

            // then
            assert(plan.filter { "issue28" in it.second } == listOf("install" to "issue28-dependency>=2"))
        }
    }

    @Test
    fun `should not constrain by requirements of extras and other platforms`() {
        // given
        PythonEnvironment.initialize()
        withDependencyDirectory { directory ->
            directory.writeDistribution(
                "issue28-dependent", "1.0",
                "issue28-extra-dependency<2; extra == \"socks\"",
                "issue28-platform-dependency<2; sys_platform == \"nonexistent\"",
                "issue28-dependency[socks]<2; python_version >= \"3\""
            )

            // when
            val plan = PythonDependencies.plan(
                "dependencies = [\"issue28-new\"]\n", directory.toString(), emptyList()
            )

            // then
            // a constraint cannot have extras
            val constraints = plan.filter { it.first == "constraint" && "issue28" in it.second }
            assert(constraints == listOf("constraint" to "issue28-dependency<2"))
        }
    }

    @Test
    fun `should not constrain by requirements of distribution being upgraded`() {
        // given
        PythonEnvironment.initialize()
        withDependencyDirectory { directory ->
            directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
            directory.writeDistribution("issue28-dependency", "1.0")

            // when
            val plan = PythonDependencies.plan(
                "dependencies = [\"Issue28.Dependent>=2\"]\n", directory.toString(), emptyList()
            )

            // then
            assert(plan.filter { "issue28" in it.second.lowercase() } == listOf("install" to "Issue28.Dependent>=2"))
        }
    }

    @Test
    fun `should constrain imported distribution of dependency directory to its version`() {
        // given
        PythonEnvironment.initialize()
        withDependencyDirectory { directory ->
            directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
            directory.writeDistribution("issue28-dependency", "1.5", topLevel = "issue28_dependency")

            // when
            val plan = PythonDependencies.plan(
                "dependencies = [\"issue28-new\"]\n", directory.toString(), listOf("issue28_dependency")
            )

            // then
            val constraints = plan.filter { it.first == "constraint" && "issue28" in it.second }
            assert(constraints == listOf("constraint" to "issue28-dependency==1.5"))
        }
    }

    @Test
    fun `should constrain every distribution of dependency directory without packaging to read requirements`() {
        // given
        PythonEnvironment.initialize()
        withDependencyDirectory { directory ->
            directory.writeDistribution("issue28-dependency", "1.0")
            // None in sys.modules makes the import fail, like without packaging, and without pip vendoring it
            val code = "import sys; sys.modules.update(dict.fromkeys(['packaging', 'pip._vendor.packaging'], None))\n" +
                PythonDependencies.PLAN

            // when
            val (exitCode, output) = runProcess(
                listOf(PythonEnvironment.executable, "-c", code, directory.toString()),
                input = """{"metadata": "dependencies = [\"issue28-new\"]\n", "modules": []}"""
            )

            // then
            assert(exitCode == 0 && "constraint issue28-dependency==1.0" in output.lines())
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun withDependencyDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("python-dependencies")
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

}

/** Writes the metadata of a distribution installed into this directory, with a module of the [topLevel] name, if given. */
internal fun Path.writeDistribution(
    name: String,
    version: String,
    vararg requirements: String,
    topLevel: String? = null
) {
    val distInfo = resolve("${name.replace('-', '_')}-$version.dist-info").createDirectories()
    distInfo.resolve("METADATA").writeText(
        "Metadata-Version: 2.1\nName: $name\nVersion: $version\n" +
            requirements.joinToString("") { "Requires-Dist: $it\n" }
    )
    if (topLevel != null) {
        resolve("$topLevel.py").writeText("")
        distInfo.resolve("RECORD").writeText("$topLevel.py,,\n")
    }
}
