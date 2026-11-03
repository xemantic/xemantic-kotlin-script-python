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
import org.intellij.lang.annotations.Language
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
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

@OptIn(ExperimentalPathApi::class)
class PythonDependenciesTest {

    private val python = Path(System.getenv("VIRTUAL_ENV"), "bin", "python").toString()

    private val directory = createTempDirectory("python-dependencies")

    private val constraints = createTempFile("python-constraints", ".txt")

    private val sources = createTempDirectory("python-sources")

    @AfterTest
    fun deleteFiles() {
        directory.deleteRecursively()
        constraints.deleteRecursively()
        sources.deleteRecursively()
    }

    private fun run(
        command: List<String>,
        environment: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment().putAll(environment) }
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return process.waitFor() to output
    }

    private fun pipInstall(
        vararg requirements: String,
        environment: Map<String, String> = emptyMap()
    ): Pair<Int, String> = run(
        PythonDependencies.pipInstallCommand(python, directory.toString(), constraints.toString(), requirements.toList()),
        environment
    )

    /** Runs the [code] with the dependency directory, as `directory`, first on `sys.path`. */
    private fun pythonOfDirectory(@Language("python") code: String): String {
        val (exitCode, output) = run(
            listOf(
                python, "-c",
                "import sys; directory = sys.argv[1]; sys.path.insert(0, directory)\n$code",
                directory.toString()
            )
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
                    "\nimport json, sys\nprint(pinned_requirement(json.load(sys.stdin), requested_urls(sys.argv[1:])))"
            ) + requirements,
            input = distribution
        )
        assert(exitCode == 0, output)
        return output
    }

    private fun Path.fileKey(): Any? = readAttributes<BasicFileAttributes>().fileKey()

    /**
     * pip does not see the distributions of a `--target` directory as installed,
     * so it would install them again, replacing the files of the modules, which might be already loaded.
     */
    @Test
    fun `should keep distribution of dependency directory when pip installs dependency requiring it`() {
        // given
        val installed = pipInstall("idna==3.10")
        val idnaModule = directory.resolve("idna").resolve("__init__.py")
        val idnaKey = idnaModule.fileKey()

        // when
        val (exitCode, output) = pipInstall("requests")

        // then
        assert(installed.first == 0)
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
        val installed = pipInstall("idna==3.6")

        // when
        val (exitCode, output) = pipInstall("idna>=3.7")

        // then
        assert(installed.first == 0)
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
        val installed = pipInstall("jaraco.functools")

        // when
        val (exitCode, output) = pipInstall("jaraco.context")

        // then
        assert(installed.first == 0)
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
            val (gitExitCode, gitOutput) = run(git + command)
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
        val (wheelExitCode, wheelOutput) = run(listOf(python, "-c", BUILD_WHEEL, wheel.toString()))
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

    /** The installation report of pip, resolving the distributions to install, exists since pip 22.2. */
    @Test
    fun `should report minimum pip version when pip is older`() {
        // given
        val oldPip = sources.resolve("old-pip")
        oldPip.resolve("pip").createDirectories().resolve("__init__.py").writeText("__version__ = \"22.1.2\"\n")

        // when
        val (exitCode, output) = pipInstall("idna", environment = mapOf("PYTHONPATH" to oldPip.toString()))

        // then
        assert(exitCode != 0)
        assert(output.contains("pip 22.2"), output)
        assert(output.contains("22.1.2"), output)
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
