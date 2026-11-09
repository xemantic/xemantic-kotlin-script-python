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
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import jep.Interpreter
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readAttributes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.intellij.lang.annotations.Language

@OptIn(ExperimentalPathApi::class)
class PythonDependenciesTest {

    private val python by lazy {
        PythonEnvironment.initialize()
        PythonEnvironment.executable
    }

    private val tempPaths = mutableListOf<Path>()

    /** A temporary path, created only by the tests using it, and deleted after them. */
    private fun tempPath(create: () -> Path) = lazy { create().also { tempPaths.add(it) } }

    private val directory by tempPath { createTempDirectory("python-dependencies") }

    private val constraints by tempPath { createTempFile("python-constraints", ".txt") }

    private val sources by tempPath { createTempDirectory("python-sources") }

    @AfterTest
    fun deleteTempPaths() {
        tempPaths.forEach { it.deleteRecursively() }
    }

    private fun pipInstall(
        vararg requirements: String,
        environment: Map<String, String> = emptyMap()
    ): ProcessResult = runProcess(
        PythonDependencies.pipInstallCommand(python, directory.toString(), constraints.toString(), requirements.toList()),
        environment = environment
    )

    /** Installs the [requirements] with [pipInstall], as the setup of a test, which must succeed. */
    private fun pipInstalled(vararg requirements: String) {
        val (exitCode, output) = pipInstall(*requirements)
        assert(exitCode == 0, output)
    }

    /**
     * Runs the [code] with the dependency directory, as `directory`, first on `sys.path`,
     * and the [arguments] following it in `sys.argv`.
     */
    private fun pythonOfDirectory(@Language("python") code: String, vararg arguments: String): String {
        val (exitCode, output) = runProcess(
            listOf(
                python, "-c",
                "import sys; directory = sys.argv[1]; sys.path.insert(0, directory)\n$code",
                directory.toString()
            ) + arguments
        )
        assert(exitCode == 0, output)
        return output
    }

    private fun pinnedRequirement(
        @Language("json") distribution: String,
        vararg requirements: String
    ): String {
        val (exitCode, output) = runProcess(
            listOf(
                python, "-c",
                PythonDependencies.PIP_PINNED_REQUIREMENT +
                    "\nimport json, sys\nprint(pinned_requirement(json.load(sys.stdin), url_credentials(sys.argv[1:])))"
            ) + requirements,
            input = distribution
        )
        assert(exitCode == 0, output)
        return output
    }

    private fun Path.fileKey(): Any? = readAttributes<BasicFileAttributes>().fileKey()

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

    /**
     * pip does not see the distributions of a `--target` directory as installed,
     * so it would install them again, replacing the files of the modules, which might be already loaded.
     */
    @Test
    fun `should keep distribution of dependency directory when pip installs dependency requiring it`() {
        // given
        pipInstalled("idna==3.10")
        val idnaModule = directory.resolve("idna").resolve("__init__.py")
        val idnaKey = idnaModule.fileKey()

        // when
        val (exitCode, output) = pipInstall("requests")

        // then
        assert(exitCode == 0, output)
        assert(directory.resolve("requests").exists())
        assert(idnaModule.fileKey() == idnaKey)
    }

    /**
     * The metadata of the replaced version must be removed,
     * otherwise `importlib.metadata` could still report it, and the dependency would be installed on every use.
     */
    @Test
    fun `should replace distribution of dependency directory when pip installs newer version of it`() {
        // given
        pipInstalled("idna==3.6")

        // when
        val (exitCode, output) = pipInstall("idna>=3.7")

        // then
        assert(exitCode == 0, output)
        val versions = pythonOfDirectory(
            """
            from importlib import metadata
            import idna
            print(idna.__version__, *(it.version for it in metadata.distributions(name="idna", path=[directory])))
            print(metadata.version("idna"))
            """.trimIndent()
        ).lines()
        val (imported, inDirectory) = versions[0].split(" ", limit = 2)
        assert(imported != "3.6")
        assert(inDirectory == imported)
        assert(versions[1] == imported)
        assert(directory.listDirectoryEntries(".staging-*").isEmpty())
    }

    @Test
    fun `should keep sibling packages of namespace package when pip installs another one`() {
        // given
        pipInstalled("jaraco.functools")

        // when
        val (exitCode, output) = pipInstall("jaraco.context")

        // then
        assert(exitCode == 0, output)
        assert(directory.resolve("jaraco").resolve("functools").exists())
        assert(directory.resolve("jaraco").resolve("context").exists())
    }

    @Test
    fun `should install dependency from subdirectory of git repository with pip`() {
        // given
        val repository = sources.resolve("repository")
        val project = repository.resolve("libs").resolve("subpkg").createDirectories()
        project.resolve("pyproject.toml").writeText(
            """
            [build-system]
            requires = ["setuptools"]
            build-backend = "setuptools.build_meta"

            [project]
            name = "subpkg"
            version = "1.0"
            """.trimIndent()
        )
        project.resolve("subpkg").createDirectories().resolve("__init__.py").writeText("ANSWER = 42\n")
        val git = listOf(
            "git", "-C", repository.toString(), "-c", "user.name=test", "-c", "user.email=test@example.com"
        )
        listOf(
            listOf("init", "--quiet", "-b", "main"),
            listOf("add", "."),
            listOf("commit", "--quiet", "-m", "subpkg")
        ).forEach { command ->
            val (gitExitCode, gitOutput) = runProcess(git + command)
            assert(gitExitCode == 0, gitOutput)
        }

        // when
        val (exitCode, output) = pipInstall("subpkg @ git+${repository.toUri()}@main#subdirectory=libs/subpkg")

        // then
        assert(exitCode == 0, output)
        assert(pythonOfDirectory("import subpkg; print(subpkg.ANSWER)") == "42")
    }

    /**
     * pip strips the credentials from the URL in its installation report,
     * so the distribution resolved from it would be installed without them.
     */
    @Test
    fun `should install dependency from URL requiring credentials with pip`() {
        // given
        val wheels = sources.resolve("wheels").createDirectories()
        val wheel = wheels.resolve("authpkg-1.0-py3-none-any.whl")
        val (wheelExitCode, wheelOutput) = runProcess(listOf(python, "-c", BUILD_WHEEL, wheel.toString()))
        assert(wheelExitCode == 0, wheelOutput)
        val server = ProcessBuilder(python, "-c", AUTHENTICATING_SERVER, wheels.toString(), "user:secret")
            .redirectErrorStream(true)
            .start()
        try {
            val port = server.inputStream.bufferedReader().readLine()

            // when
            val (exitCode, output) = pipInstall("authpkg @ http://user:secret@127.0.0.1:$port/${wheel.fileName}")

            // then
            assert(exitCode == 0, output)
            assert(pythonOfDirectory("import authpkg; print(authpkg.ANSWER)") == "42")
        } finally {
            server.destroy()
            server.waitFor()
        }
    }

    @Test
    fun `should remove staging directory left by killed installation`() {
        // given
        val stale = directory.resolve(".staging-stale").createDirectories()
        stale.resolve("leftover.py").writeText("")

        // when
        val (exitCode, output) = pipInstall("idna==3.10")

        // then
        assert(exitCode == 0, output)
        assert(!stale.exists())
        assert(directory.resolve("idna").exists())
    }

    @Test
    fun `should redact credentials of requirements`() {
        // when
        val redacted = PythonDependencies.redactCredentials(
            "pkg @ git+https://oauth2:TOKEN@gitlab.com/org/private.git, " +
                "authpkg @ http://user:secret@127.0.0.1:8765/authpkg-1.0-py3-none-any.whl, idna"
        )

        // then
        assert(
            redacted == "pkg @ git+https://****@gitlab.com/org/private.git, " +
                "authpkg @ http://****@127.0.0.1:8765/authpkg-1.0-py3-none-any.whl, idna"
        )
    }

    @Test
    fun `should redact whole credentials with at sign in password`() {
        // when
        val redacted = PythonDependencies.redactCredentials("pkg @ https://user:p@ss@host/pkg.whl")

        // then
        assert(redacted == "pkg @ https://****@host/pkg.whl")
    }

    /**
     * pip reuses the credentials of a host for all its URLs,
     * so a direct reference required by a requested one, from the same host, has to be pinned with them too.
     */
    @Test
    fun `should pin required distribution from URL with credentials of requirement from the same host`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "b", "version": "1.0"},
              "is_direct": true,
              "requested": false,
              "download_info": {"url": "https://host/b-1.0-py3-none-any.whl", "archive_info": {}}
            }
            """.trimIndent(),
            "a @ https://user:tok@host/a-1.0-py3-none-any.whl"
        )

        // then
        assert(requirement == "b @ https://user:tok@host/b-1.0-py3-none-any.whl")
    }

    /**
     * pip before 26 removes the credentials from the URL in its installation report only up to the first `@`,
     * leaving the rest of a password with `@` as the user.
     */
    @Test
    fun `should pin distribution from URL with password tail left by pip as user with credentials of requirement`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "x", "version": "1.0"},
              "is_direct": true,
              "requested": true,
              "download_info": {"url": "http://ss@127.0.0.1:8765/x-1.0-py3-none-any.whl", "archive_info": {}}
            }
            """.trimIndent(),
            "x @ http://user:p@ss@127.0.0.1:8765/x-1.0-py3-none-any.whl"
        )

        // then
        assert(requirement == "x @ http://user:p@ss@127.0.0.1:8765/x-1.0-py3-none-any.whl")
    }

    @Test
    fun `should pin distribution from URL without credentials of requirement from other host`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "b", "version": "1.0"},
              "is_direct": true,
              "requested": false,
              "download_info": {"url": "https://other/b-1.0-py3-none-any.whl", "archive_info": {}}
            }
            """.trimIndent(),
            "a @ https://user:tok@host/a-1.0-py3-none-any.whl"
        )

        // then
        assert(requirement == "b @ https://other/b-1.0-py3-none-any.whl")
    }

    @Test
    fun `should pin requested distribution from URL with credentials of its requirement`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "pkg", "version": "1.0"},
              "is_direct": true,
              "requested": true,
              "download_info": {
                "url": "https://gitlab.com/org/private.git",
                "vcs_info": {"vcs": "git", "requested_revision": "main", "commit_id": "0123abcd"},
                "subdirectory": "libs/pkg"
              }
            }
            """.trimIndent(),
            "idna",
            "Pkg @ git+https://oauth2:TOKEN@gitlab.com/org/private.git@main#subdirectory=libs/pkg"
        )

        // then
        assert(requirement == "pkg @ git+https://oauth2:TOKEN@gitlab.com/org/private.git@0123abcd#subdirectory=libs/pkg")
    }

    @Test
    fun `should pin distribution resolved from index to its version`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "pkg", "version": "1.0"},
              "is_direct": false,
              "download_info": {"url": "https://example.com/pkg-1.0-py3-none-any.whl", "archive_info": {}}
            }
            """.trimIndent()
        )

        // then
        assert(requirement == "pkg==1.0")
    }

    @Test
    fun `should pin distribution from subdirectory of git repository to its commit and subdirectory`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "pkg", "version": "1.0"},
              "is_direct": true,
              "download_info": {
                "url": "https://example.com/mono.git",
                "vcs_info": {"vcs": "git", "requested_revision": "main", "commit_id": "0123abcd"},
                "subdirectory": "libs/pkg"
              }
            }
            """.trimIndent()
        )

        // then
        assert(requirement == "pkg @ git+https://example.com/mono.git@0123abcd#subdirectory=libs/pkg")
    }

    @Test
    fun `should pin distribution from archive URL with its hash`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "pkg", "version": "1.0"},
              "is_direct": true,
              "download_info": {
                "url": "https://example.com/pkg-1.0.tar.gz",
                "archive_info": {"hash": "sha256=beef", "hashes": {"sha256": "beef"}},
                "subdirectory": "pkg"
              }
            }
            """.trimIndent()
        )

        // then
        assert(requirement == "pkg @ https://example.com/pkg-1.0.tar.gz#sha256=beef&subdirectory=pkg")
    }

    @Test
    fun `should pin distribution from archive URL with its legacy hash`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "pkg", "version": "1.0"},
              "is_direct": true,
              "download_info": {
                "url": "https://example.com/pkg-1.0-py3-none-any.whl",
                "archive_info": {"hash": "sha256=beef"}
              }
            }
            """.trimIndent()
        )

        // then
        assert(requirement == "pkg @ https://example.com/pkg-1.0-py3-none-any.whl#sha256=beef")
    }

    @Test
    fun `should pin distribution from local directory to its URL`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "pkg", "version": "1.0"},
              "is_direct": true,
              "download_info": {"url": "file:///src/pkg", "dir_info": {}}
            }
            """.trimIndent()
        )

        // then
        assert(requirement == "pkg @ file:///src/pkg")
    }

    /**
     * The installation report of pip, resolving the distributions to install, exists since pip 22.2,
     * while an older pip, like 22.0.2 of a virtual environment of Ubuntu 22.04, still has to install the dependencies.
     */
    @Test
    fun `should install with upgrading pip command when pip is older than 22_2`() {
        // given
        val oldPip = sources.resolve("old-pip").resolve("pip").createDirectories()
        oldPip.resolve("__init__.py").writeText("__version__ = \"22.1.2\"\n")
        // records the arguments of the pip command, instead of installing
        oldPip.resolve("__main__.py").writeText(
            "import json, os, sys\n" +
                "with open(os.path.join(os.path.dirname(__file__), 'arguments.json'), 'w') as file:\n" +
                "    json.dump(sys.argv[1:], file)\n"
        )

        // when
        val (exitCode, output) = pipInstall("idna", environment = mapOf("PYTHONPATH" to oldPip.parent.toString()))

        // then
        assert(exitCode == 0, output)
        val arguments = pythonOfDirectory(
            "import json; print(*json.load(open(sys.argv[2])), sep='\\n')\n",
            oldPip.resolve("arguments.json").toString()
        ).lines()
        assert(
            arguments == listOf(
                "install", "--quiet", "--disable-pip-version-check", "--no-input", "--upgrade",
                "--target", directory.toString(), "--constraint", constraints.toString(), "idna"
            )
        )
    }

    /** A `.dist-info` directory without metadata, e.g. left by an interrupted installation. */
    @Test
    fun `should install with pip next to dist-info directory without metadata`() {
        // given
        directory.resolve("broken-1.0.dist-info").createDirectories()

        // when
        val (exitCode, output) = pipInstall(
            "idna==3.10",
            // Python 3.12 deprecates reading a missing metadata field, raising KeyError in the future
            environment = mapOf("PYTHONWARNINGS" to "error::DeprecationWarning:__main__")
        )

        // then
        assert(exitCode == 0, output)
        assert(directory.resolve("idna").exists())
    }

    private companion object {

        /** Builds the wheel of the `authpkg` distribution, to the path of its argument. */
        @Language("python")
        val BUILD_WHEEL = """
            import base64, hashlib, sys, zipfile

            files = {
                "authpkg/__init__.py": b"ANSWER = 42\n",
                "authpkg-1.0.dist-info/METADATA": b"Metadata-Version: 2.1\nName: authpkg\nVersion: 1.0\n",
                "authpkg-1.0.dist-info/WHEEL": b"Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
            }
            record = "".join(
                f"{name},sha256={base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b'=').decode()},{len(data)}\n"
                for name, data in files.items()
            ) + "authpkg-1.0.dist-info/RECORD,,\n"
            with zipfile.ZipFile(sys.argv[1], "w") as wheel:
                for name, data in files.items():
                    wheel.writestr(name, data)
                wheel.writestr("authpkg-1.0.dist-info/RECORD", record)
        """.trimIndent()

        /**
         * Serves the directory of its first argument, only with the basic authentication of its second argument,
         * printing its port.
         */
        @Language("python")
        val AUTHENTICATING_SERVER = """
            import base64, functools, http.server, sys

            directory, authorization = sys.argv[1], "Basic " + base64.b64encode(sys.argv[2].encode()).decode()

            class Handler(http.server.SimpleHTTPRequestHandler):
                def authorized(self):
                    if self.headers.get("Authorization") == authorization:
                        return True
                    self.send_response(401)
                    self.send_header("WWW-Authenticate", 'Basic realm="test"')
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return False

                def do_GET(self):
                    if self.authorized():
                        super().do_GET()

                def do_HEAD(self):
                    if self.authorized():
                        super().do_HEAD()

                def log_message(self, *args):
                    pass

            server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=directory))
            print(server.server_address[1], flush=True)
            server.serve_forever()
        """.trimIndent()

    }

}
