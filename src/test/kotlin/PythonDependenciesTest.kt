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
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.outputStream
import kotlin.io.path.readText
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
    fun `should plan requirements of distributions of dependency directory`() {
        // given
        PythonEnvironment.initialize()
        withDirectory { directory ->
            directory.writeDistribution("issue28-dependent", "1.0", "Issue28_Dependency<2,>=1", "issue28-other!=1.5")
            directory.writeDistribution("issue28-dependency", "1.9")

            // when
            val plan = PythonDependencies.plan("dependencies = [\"issue28-new\"]\n", directory.toString(), emptyList())

            // then
            assert(
                plan.filter { it.first == "requirement" } == listOf(
                    "requirement" to "issue28-dependent 1.0 issue28-dependency <2,>=1",
                    "requirement" to "issue28-dependent 1.0 issue28-other !=1.5"
                )
            )
        }
    }

    @Test
    fun `should not plan requirements of extras and other platforms`() {
        // given
        PythonEnvironment.initialize()
        withDirectory { directory ->
            directory.writeDistribution(
                "issue28-dependent", "1.0",
                "issue28-extra-dependency<2; extra == \"socks\"",
                "issue28-platform-dependency<2; sys_platform == \"nonexistent\"",
                "issue28-any-version-dependency",
                "issue28-dependency[socks]<2; python_version >= \"3\""
            )

            // when
            val plan = PythonDependencies.plan("dependencies = [\"issue28-new\"]\n", directory.toString(), emptyList())

            // then
            // a constraint cannot have extras
            assert(
                plan.filter { it.first == "requirement" } ==
                    listOf("requirement" to "issue28-dependent 1.0 issue28-dependency <2")
            )
        }
    }

    @Test
    fun `should plan requirements of current dist-info of distribution with several dist-info directories`() {
        // given
        PythonEnvironment.initialize()
        withDirectory { directory ->
            // pip --target leaves the dist-info of the replaced version behind,
            // with the hashes of the files since overwritten
            directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
            directory.writeDistribution("issue28-dependent", "2.0", "issue28-dependency>=2")
            directory.writeDistribution("issue28-dependency", "2.0")

            // when
            val plan = PythonDependencies.plan("dependencies = [\"issue28-new\"]\n", directory.toString(), emptyList())

            // then
            assert(
                plan.filter { it.first == "requirement" } ==
                    listOf("requirement" to "issue28-dependent 2.0 issue28-dependency >=2")
            )
        }
    }

    @Test
    fun `should not plan requirements of distribution declared as dependency`() {
        // given
        PythonEnvironment.initialize()
        withDirectory { directory ->
            directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
            directory.writeDistribution("issue28-dependency", "1.0")

            // when
            val plan = PythonDependencies.plan(
                "dependencies = [\"Issue28.Dependent>=2\"]\n", directory.toString(), emptyList()
            )

            // then
            // the declared distribution is replaced, so the requirements of the installed version do not matter
            assert(plan.none { it.first == "requirement" })
        }
    }

    @Test
    fun `should keep imported distribution of dependency directory in its version`() {
        // given
        PythonEnvironment.initialize()
        withDirectory { directory ->
            directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
            directory.writeDistribution("issue28-dependency", "1.5")

            // when
            val plan = PythonDependencies.plan(
                "dependencies = [\"issue28-new\"]\n", directory.toString(), listOf("issue28_dependency")
            )

            // then
            // no requirement is needed of a distribution kept in its version
            assert(
                plan.filter { "issue28-dependency" in it.second } == listOf("constraint" to "issue28-dependency==1.5")
            )
        }
    }

    @Test
    fun `should not keep distribution of dependency directory in its version without packaging`() {
        // given
        PythonEnvironment.initialize()
        withDirectory { directory ->
            directory.writeDistribution("issue28-dependency", "1.0")
            // None in sys.modules makes the import fail, like without packaging, and without pip vendoring it
            val code = "import sys; sys.modules.update(dict.fromkeys(['packaging', 'pip._vendor.packaging'], None))\n" +
                PythonDependencies.PLAN

            // when
            val (exitCode, output) = runProcess(
                listOf(PythonEnvironment.executable, "-c", code, directory.toString()),
                input = """{"metadata": "dependencies = [\"issue28-dependency>=2\"]\n", "modules": []}"""
            )

            // then
            assert(exitCode == 0)
            assert(output.lines().filter { "issue28" in it } == listOf("install issue28-dependency>=2"))
        }
    }

    @Test
    fun `should fail installing dependency breaking requirement of installed distribution`() {
        forEachInstaller { installer ->
            withLocalIndex { directory, index ->
                // given
                directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
                directory.writeDistribution("issue28-dependency", "1.0")
                index.writeWheel("issue28-dependency", "2.0")
                index.writeWheel("issue28-new", "1.0", "issue28-dependency>=2")

                // when
                val exception = assertFailsWith<PythonDependencyException> {
                    directory.installDependencies(installer, index, "issue28-new")
                }

                // then
                val message = exception.message!!
                assert("  issue28-dependency <2, required by issue28-dependent 1.0\n" in message)
                assert(directory.version("issue28_dependency") == "1.0")
            }
        }
    }

    @Test
    fun `should fail installing dependency between contradictory requirements of installed distributions`() {
        forEachInstaller { installer ->
            withLocalIndex { directory, index ->
                // given
                directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
                directory.writeDistribution("issue28-other-dependent", "1.0", "issue28-dependency>=3")
                index.writeWheel("issue28-dependency", "2.0")
                index.writeWheel("issue28-new", "1.0", "issue28-dependency")

                // when
                val exception = assertFailsWith<PythonDependencyException> {
                    directory.installDependencies(installer, index, "issue28-new")
                }

                // then
                val message = exception.message!!
                assert("  issue28-dependency <2, required by issue28-dependent 1.0\n" in message)
                assert("  issue28-dependency >=3, required by issue28-other-dependent 1.0\n" in message)
            }
        }
    }

    @Test
    fun `should install version of dependency within requirement of installed distribution`() {
        forEachInstaller { installer ->
            withLocalIndex { directory, index ->
                // given
                directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
                directory.writeDistribution("issue28-dependency", "0.9")
                index.writeWheel("issue28-dependency", "1.0")
                index.writeWheel("issue28-dependency", "2.0")
                index.writeWheel("issue28-new", "1.0", "issue28-dependency>=1")

                // when
                directory.installDependencies(installer, index, "issue28-new")

                // then
                assert(directory.version("issue28_dependency") == "1.0")
            }
        }
    }

    @Test
    fun `should fail upgrading installed distribution along with distribution it requires`() {
        forEachInstaller { installer ->
            withLocalIndex { directory, index ->
                // given
                directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
                directory.writeDistribution("issue28-dependency", "1.0")
                index.writeWheel("issue28-dependent", "2.0", "issue28-dependency>=2")
                index.writeWheel("issue28-dependency", "2.0")
                index.writeWheel("issue28-new", "1.0", "issue28-dependent>=2")

                // when
                val exception = assertFailsWith<PythonDependencyException> {
                    directory.installDependencies(installer, index, "issue28-new")
                }

                // then
                // only the declared distributions are known to be replaced before the installation
                assert("  issue28-dependency <2, required by issue28-dependent 1.0\n" in exception.message!!)
                assert(directory.version("issue28_dependent") == "1.0")
            }
        }
    }

    @Test
    fun `should upgrade installed distribution declared as dependency along with distribution it requires`() {
        forEachInstaller { installer ->
            withLocalIndex { directory, index ->
                // given
                directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
                directory.writeDistribution("issue28-dependency", "1.0")
                index.writeWheel("issue28-dependent", "2.0", "issue28-dependency>=2")
                index.writeWheel("issue28-dependency", "2.0")
                index.writeWheel("issue28-new", "1.0", "issue28-dependent>=2")

                // when
                directory.installDependencies(installer, index, "issue28-new", "issue28-dependent>=2")

                // then
                assert(directory.version("issue28_dependent") == "2.0")
                assert(directory.version("issue28_dependency") == "2.0")
            }
        }
    }

    @Test
    fun `should not break installed distribution when constraint makes installer backtrack`() {
        forEachInstaller { installer ->
            withLocalIndex { directory, index ->
                // given
                directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")
                directory.writeDistribution("issue28-dependency", "1.0")
                directory.writeDistribution("issue28-other-dependent", "1.0", "issue28-other-dependency<2")
                directory.writeDistribution("issue28-other-dependency", "1.0")
                index.writeWheel("issue28-new", "2.0", "issue28-dependency>=2")
                // the version the installer backtracks to, constrained to issue28-dependency<2
                index.writeWheel("issue28-new", "1.0", "issue28-other-dependency>=2")
                index.writeWheel("issue28-dependency", "2.0")
                index.writeWheel("issue28-other-dependency", "2.0")

                // when
                val exception = assertFailsWith<PythonDependencyException> {
                    directory.installDependencies(installer, index, "issue28-new")
                }

                // then
                assert("required by issue28-other-dependent 1.0" in exception.message!!)
                assert(directory.version("issue28_other_dependency") == "1.0")
            }
        }
    }

    @Test
    fun `should keep requirements of distribution upgraded by pip`() {
        forEachInstaller { installer ->
            withLocalIndex { directory, index ->
                // given
                index.writeWheel("issue28-dependent", "1.0", "issue28-dependency<2")
                index.writeWheel("issue28-dependent", "2.0", "issue28-dependency<3")
                index.writeWheel("issue28-dependency", "1.0")
                index.writeWheel("issue28-dependency", "3.0")
                index.writeWheel("issue28-new", "1.0", "issue28-dependency>=3")
                directory.installDependencies(installer, index, "issue28-dependent==1.0")
                // pip --target leaves the dist-info of the replaced version behind
                directory.installDependencies(installer, index, "issue28-dependent==2.0")

                // when
                val exception = assertFailsWith<PythonDependencyException> {
                    directory.installDependencies(installer, index, "issue28-new")
                }

                // then
                assert("  issue28-dependency <3, required by issue28-dependent 2.0\n" in exception.message!!)
                assert(directory.version("issue28_dependency") == "1.0")
            }
        }
    }

    @Test
    fun `should install dependency requiring installed distribution which uv does not find in index`() {
        // given
        val uv = PythonDependencies.findOnPath("uv") ?: return
        withLocalIndex { directory, index ->
            // installed from a URL, or an index no longer configured, like pip would install it again
            directory.writeDistribution("issue28-dependency", "1.0")
            directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency>=1")
            index.writeWheel("issue28-new", "1.0", "issue28-dependency")

            // when
            directory.installDependencies(uv, index, "issue28-new")

            // then
            assert(directory.version("issue28_new") == "1.0")
        }
    }

    @Test
    fun `should fail installing unavailable dependency without blaming dependency directory`() {
        forEachInstaller { installer ->
            withLocalIndex { directory, index ->
                // given
                directory.writeDistribution("issue28-dependent", "1.0", "issue28-dependency<2")

                // when
                val exception = assertFailsWith<PythonDependencyException> {
                    directory.installDependencies(installer, index, "issue28-nonexistent")
                }

                // then
                val message = exception.message!!
                assert(message.startsWith("Cannot install Python dependencies issue28-nonexistent:"))
                assert("required by" !in message && "dependency directory" !in message)
            }
        }
    }

    /**
     * Runs the [block] with pip, as `null`, and with uv, if it is on the `PATH`,
     * not inherited by the test JVM from the Gradle client, but from its daemon.
     */
    private fun forEachInstaller(block: (uv: String?) -> Unit) {
        PythonEnvironment.initialize()
        block(null)
        PythonDependencies.findOnPath("uv")?.let(block)
    }

    /**
     * Plans and installs the [dependencies] into this directory, only from the wheels of the [index] directory,
     * with the [uv] executable, or pip, if `null`.
     */
    private fun Path.installDependencies(uv: String?, index: Path, vararg dependencies: String) {
        val metadata = "dependencies = [${dependencies.joinToString { "\"$it\"" }}]\n"
        val plan = PythonDependencies.plan(metadata, toString(), emptyList())
        val installerOptions = listOf("--no-index", "--find-links", index.toString())
        PythonDependencies.install(plan, toString(), installerOptions, uv)
    }

    /** The version of the distribution of the [module], written by [writeDistribution] or [writeWheel]. */
    private fun Path.version(module: String) = resolve(module).resolve("__init__.py").readText()

    private fun withLocalIndex(block: (directory: Path, index: Path) -> Unit) {
        withDirectory { directory ->
            withDirectory { index -> block(directory, index) }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun withDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("python-dependencies")
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

}

/**
 * Writes the metadata of a distribution installed into this directory, with its package,
 * whose `__init__.py` contains its version, like pip would install the wheel written by [writeWheel].
 */
internal fun Path.writeDistribution(name: String, version: String, vararg requirements: String) {
    val distribution = name.replace('-', '_')
    val distInfo = resolve("$distribution-$version.dist-info").createDirectories()
    distInfo.resolve("METADATA").writeText(metadata(name, version, requirements))
    resolve(distribution).createDirectories().resolve("__init__.py").writeText(version)
    distInfo.resolve("RECORD").writeText(
        "$distribution/__init__.py,${hash(version)},${version.length}\n" +
            "${distInfo.fileName}/METADATA,,\n${distInfo.fileName}/RECORD,,\n"
    )
}

/** Writes a wheel into this directory, like [writeDistribution] would install it. */
internal fun Path.writeWheel(name: String, version: String, vararg requirements: String) {
    val distribution = name.replace('-', '_')
    val distInfo = "$distribution-$version.dist-info"
    ZipOutputStream(resolve("$distribution-$version-py3-none-any.whl").outputStream()).use { zip ->
        mapOf(
            "$distribution/__init__.py" to version,
            "$distInfo/METADATA" to metadata(name, version, requirements),
            "$distInfo/WHEEL" to "Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
            "$distInfo/RECORD" to
                "$distribution/__init__.py,${hash(version)},${version.length}\n" +
                listOf("$distInfo/METADATA", "$distInfo/WHEEL", "$distInfo/RECORD").joinToString("") { "$it,,\n" }
        ).forEach { (entry, content) ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
}

/** The hash of the [content] of a file, as in a `RECORD` file. */
private fun hash(content: String) = "sha256=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
)

private fun metadata(name: String, version: String, requirements: Array<out String>) =
    "Metadata-Version: 2.1\nName: $name\nVersion: $version\n" +
        requirements.joinToString("") { "Requires-Dist: $it\n" }
