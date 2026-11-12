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
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.reflect.Proxy
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isWritable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
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
        val state = PythonDependencies.installLock.withLock {
            // as if another thread was installing dependencies
            val installer = thread { PythonDependencies.install("x = 1", interpreter) }
            while (installer.state != Thread.State.WAITING && installer.state != Thread.State.TERMINATED) {
                Thread.onSpinWait()
            }
            installer.state
        }

        // then
        assert(state == Thread.State.TERMINATED)
    }

    @Test
    fun `should wait for installation of other process into dependency directory`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory!!
        val distribution = "lock-marker-${UUID.randomUUID()}"
        // the other process "installs" the distribution only after this JVM tries to lock the directory,
        // so an installation not waiting for it would not find it, and fail to install it from the index
        val otherInstallation = startOtherInstallation(directory, distribution)
        try {
            Python().use { python ->
                try {
                    val thread = python.threadName
                    val installation = CompletableFuture.runAsync {
                        python.exec("# /// script\n# dependencies = [\"$distribution\"]\n# ///\nx = 1\n")
                    }

                    // when
                    awaitDoneOrWaiting(installation, thread, ::isFileLock)
                    otherInstallation.finish()
                    val failure = runCatching { installation.get(5, TimeUnit.MINUTES) }.exceptionOrNull()

                    // then
                    assert(failure == null)
                } finally {
                    // before closing, which would wait forever for the Python thread waiting for the lock
                    otherInstallation.destroy()
                }
            }
        } finally {
            @OptIn(ExperimentalPathApi::class)
            directory.resolve("${distribution.replace('-', '_')}-1.0.dist-info").deleteRecursively()
        }
    }

    @Test
    fun `should not wait for installation of other process when code has no metadata`() {
        // given
        PythonEnvironment.initialize()
        val otherInstallation = startOtherInstallation(PythonEnvironment.dependencyDirectory!!, distribution = null)
        Python().use { python ->
            try {
                // when
                val installation = CompletableFuture.runAsync { python.exec("x = 1") }
                val failure = runCatching { installation.get(5, TimeUnit.MINUTES) }.exceptionOrNull()

                // then
                assert(failure == null)
            } finally {
                otherInstallation.destroy()
            }
        }
    }

    @Test
    fun `should not wait for installation of other process when dependencies are installed`() {
        // given
        PythonEnvironment.initialize()
        val otherInstallation = startOtherInstallation(PythonEnvironment.dependencyDirectory!!, distribution = null)
        Python().use { python ->
            try {
                val thread = python.threadName
                // jep is a distribution of the Python environment
                val installation = CompletableFuture.runAsync {
                    python.exec("# /// script\n# dependencies = [\"jep\"]\n# ///\nx = 1\n")
                }

                // when
                val locking = awaitDoneOrWaiting(installation, thread, ::isFileLock)
                otherInstallation.destroy()
                val failure = runCatching { installation.get(5, TimeUnit.MINUTES) }.exceptionOrNull()

                // then
                assert(!locking && failure == null)
            } finally {
                otherInstallation.destroy()
            }
        }
    }

    @Test
    fun `should not wait for other Python waiting for installation of other process when dependencies are installed`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory!!
        val distribution = "lock-marker-${UUID.randomUUID()}"
        val otherInstallation = startOtherInstallation(directory, distribution)
        try {
            Python().use { waitingPython ->
                Python().use { python ->
                    try {
                        val waitingThread = waitingPython.threadName
                        val waitingInstallation = CompletableFuture.runAsync {
                            waitingPython.exec("# /// script\n# dependencies = [\"$distribution\"]\n# ///\nx = 1\n")
                        }
                        check(awaitDoneOrWaiting(waitingInstallation, waitingThread, ::isFileLock)) {
                            "The other Python did not wait for the installation of the other process"
                        }
                        val thread = python.threadName
                        // jep is a distribution of the Python environment
                        val installation = CompletableFuture.runAsync {
                            python.exec("# /// script\n# dependencies = [\"jep\"]\n# ///\nx = 1\n")
                        }

                        // when
                        val waiting = awaitDoneOrWaiting(installation, thread, ::isInstallLock)
                        otherInstallation.finish()
                        val failure = runCatching { installation.get(5, TimeUnit.MINUTES) }.exceptionOrNull()

                        // then
                        assert(!waiting && failure == null)
                    } finally {
                        otherInstallation.destroy()
                    }
                }
            }
        } finally {
            @OptIn(ExperimentalPathApi::class)
            directory.resolve("${distribution.replace('-', '_')}-1.0.dist-info").deleteRecursively()
        }
    }

    @Test
    fun `should create lock file writable for other users`() {
        // given
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews(), "POSIX permissions only")
        PythonEnvironment.initialize()
        // e.g. for users of a group sharing the dependency directory
        val lockFile = PythonEnvironment.dependencyDirectory!!.createDirectories()
            .resolve(PythonDependencies.LOCK_FILE_NAME)
        lockFile.deleteIfExists()
        Python().use { python ->
            // when
            // failing to install, but locking the directory first
            assertFailsWith<PythonDependencyException> {
                python.exec("# /// script\n# dependencies = [\"nonexistent-dependency\"]\n# ///\nx = 1\n")
            }

            // then
            assert(lockFile.getPosixFilePermissions() == PosixFilePermissions.fromString("rw-rw-rw-"))
        }
    }

    @Test
    fun `should run code with installed dependencies when lock file is not writable`() {
        // given
        PythonEnvironment.initialize()
        // e.g. created by another user, or in a read-only directory of a container image
        val lockFile = readOnlyLockFile()
        try {
            Python().use { python ->
                // when
                // jep is a distribution of the Python environment
                python.exec("# /// script\n# dependencies = [\"jep\"]\n# ///\nx = 1\n")

                // then
                assert(python["x"] == 1L)
            }
        } finally {
            lockFile.toFile().setWritable(true)
        }
    }

    @Test
    fun `should fail with dependency exception when lock file is not writable for missing dependency`() {
        // given
        PythonEnvironment.initialize()
        val lockFile = readOnlyLockFile()
        try {
            Python().use { python ->
                // when
                val exception = assertFailsWith<PythonDependencyException> {
                    python.exec("# /// script\n# dependencies = [\"nonexistent-dependency\"]\n# ///\nx = 1\n")
                }

                // then
                assert(exception.message!!.startsWith("Cannot lock the dependency directory with $lockFile"))
            }
        } finally {
            lockFile.toFile().setWritable(true)
        }
    }

    private fun readOnlyLockFile(): Path {
        val lockFile = PythonEnvironment.dependencyDirectory!!.createDirectories()
            .resolve(PythonDependencies.LOCK_FILE_NAME)
        if (!lockFile.exists()) lockFile.createFile()
        // e.g. root can write it anyway
        assumeTrue(lockFile.toFile().setWritable(false) && !lockFile.isWritable(), "Cannot make lock file read-only")
        return lockFile
    }

    /**
     * Starts a Python process locking the dependency [directory], like an installation of another JVM,
     * which, on [finish], "installs" the [distribution], if any, and unlocks the directory.
     *
     * It locks with POSIX `fcntl.lockf`, like `FileChannel.lock` on Linux and macOS, so the test is skipped on Windows.
     */
    private fun startOtherInstallation(directory: Path, distribution: String?): Process {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "fcntl.lockf is POSIX only")
        directory.createDirectories()
        val process = ProcessBuilder(
            PythonEnvironment.executable, "-c", OTHER_INSTALLATION,
            directory.resolve(PythonDependencies.LOCK_FILE_NAME).toString(),
            distribution ?: ""
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val line = process.inputStream.bufferedReader().readLine()
        check(line == "locked") { "The other installation did not lock the directory: $line" }
        return process
    }

    private fun Process.finish() {
        outputStream.use { it.write('\n'.code) }
        check(waitFor(5, TimeUnit.MINUTES) && exitValue() == 0) { "The other installation did not finish" }
    }

    /** The name of the thread of this [Python], which is busy until the calls submitted before are done. */
    private val Python.threadName: String
        get() = eval("__import__('java.lang', fromlist=['Thread']).Thread.currentThread().getName()")

    /**
     * Waits until the [installation] is done, or the [thread] waits in a stack frame matching [waitsIn],
     * at most for minutes, so that a regression fails instead of hanging.
     *
     * @return whether the [thread] waits.
     */
    private fun awaitDoneOrWaiting(
        installation: CompletableFuture<*>,
        thread: String,
        waitsIn: (StackTraceElement) -> Boolean
    ): Boolean {
        fun isWaiting() = Thread.getAllStackTraces().any { (it, stackTrace) ->
            it.name == thread && stackTrace.any(waitsIn)
        }
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        while (!installation.isDone && System.nanoTime() < deadline) {
            if (isWaiting()) return true
            Thread.sleep(10)
        }
        return isWaiting()
    }

    private fun isFileLock(frame: StackTraceElement) =
        frame.className == "sun.nio.ch.FileChannelImpl" && frame.methodName == "lock"

    private fun isInstallLock(frame: StackTraceElement) =
        frame.className == ReentrantLock::class.java.name && frame.methodName.startsWith("lock")

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
    fun `should plan when Python writes to standard error without line break`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        // like a warning written to the standard error, before the block-buffered standard output is flushed,
        // which would be joined with the first line of the plan, if the streams were merged
        val command = pythonCommand(
            PythonEnvironment.executable,
            "import sys; sys.stderr.write('warning: '); sys.stderr.flush()\n" + PythonDependencies.PLAN,
            directory
        )

        // when
        val plan = PythonDependencies.plan(
            "dependencies = [\"nonexistent-dependency\"]\n", directory, emptyList(), command
        )

        // then
        assert(plan.filter { it.first == "install" } == listOf("install" to "nonexistent-dependency"))
    }

    @Test
    fun `should fail plan with message written to standard error`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()

        // when
        val exception = assertFailsWith<PythonDependencyException> {
            PythonDependencies.plan("dependencies = \n", directory, emptyList())
        }

        // then
        assert(exception.message!!.startsWith("Invalid '# /// script' metadata: "))
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
        val plan = PythonDependencies.plan(
            "dependencies = [\"nonexistent-dependency\"]\n",
            directory,
            emptyList(),
            pythonCommand(PythonEnvironment.executable, code, directory)
        )

        // then
        assert(plan.filter { it.first == "install" } == listOf("install" to "nonexistent-dependency"))
    }

    @Test
    fun `should fail plan on invalid metadata without tomllib of Python before 3_11`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        val code = "import sys; sys.modules.update(dict.fromkeys(['tomllib', 'tomli'], None))\n" +
            PythonDependencies.PLAN

        // when
        val exception = assertFailsWith<PythonDependencyException> {
            PythonDependencies.plan(
                "dependencies = \n",
                directory,
                emptyList(),
                pythonCommand(PythonEnvironment.executable, code, directory)
            )
        }

        // then
        assert(exception.message!!.startsWith("Invalid '# /// script' metadata: "))
    }

    @Test
    fun `should fail plan with clear message without any TOML parser`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        val code = "import sys; sys.modules.update(dict.fromkeys(['tomllib', 'tomli', 'pip'], None))\n" +
            PythonDependencies.PLAN

        // when
        val exception = assertFailsWith<PythonDependencyException> {
            PythonDependencies.plan(
                "dependencies = [\"nonexistent-dependency\"]\n",
                directory,
                emptyList(),
                pythonCommand(PythonEnvironment.executable, code, directory)
            )
        }

        // then
        assert(exception.message == PythonDependencies.NO_TOML_PARSER_MESSAGE)
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan in working directory with modules shadowing the ones it imports`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        val workingDirectory = createTempDirectory("python-shadowing")
        for (module in listOf("json", "tomllib", "tomli", "importlib", "re")) {
            workingDirectory.resolve("$module.py").writeText("raise SystemExit('shadowing $module imported')\n")
        }

        try {
            // when
            val (exitCode, output) = runProcess(
                PythonDependencies.planCommand(directory),
                input = """{"metadata": "dependencies = [\"nonexistent-dependency\"]\n", "modules": []}""",
                mergeErrorStream = false,
                directory = workingDirectory
            )

            // then
            assert(exitCode == 0 && "install nonexistent-dependency" in output.lines())
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should run pip in working directory with pip package shadowing it`() {
        // given
        PythonEnvironment.initialize()
        val workingDirectory = createTempDirectory("python-shadowing")
        val pip = workingDirectory.resolve("pip").createDirectory()
        pip.resolve("__init__.py").writeText("raise SystemExit('shadowing pip imported')\n")
        pip.resolve("__main__.py").writeText("raise SystemExit('shadowing pip run')\n")

        try {
            // when
            val (exitCode, output) = runProcess(
                PythonDependencies.pipCommand("--version"),
                mergeErrorStream = false,
                directory = workingDirectory
            )

            // then
            assert(exitCode == 0 && output.startsWith("pip "))
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    @Test
    fun `should plan git dependency as installed for the same URL and revision`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""

        // when
        val installs = planInstalls("direct-url-probe @ git+https://example.com/probe.git@v1", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan git dependency as installed for the same URL without revision`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT"}}"""

        // when
        val installs = planInstalls("direct-url-probe @ git+https://example.com/probe.git", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan git dependency as installed for the installed commit`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "main"}}"""

        // when
        val installs = planInstalls("direct-url-probe @ git+https://example.com/probe.git@$COMMIT", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan git dependency as installed with credentials and subdirectory`() {
        // given
        // direct_url.json holds the URL without credentials, and the subdirectory separately
        val directUrl = """{"url": "https://example.com/probe.git", "subdirectory": "python", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""

        // when
        val installs = planInstalls(
            "direct-url-probe @ git+https://user:secret@example.com/probe.git@v1#subdirectory=python", directUrl
        )

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan git dependency as missing for another revision of the same version`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/probe.git@feature"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan git dependency as missing for another commit`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "$COMMIT"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/probe.git@${COMMIT.reversed()}"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan git dependency as missing for another repository`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/fork/probe.git@v1"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan git dependency as missing for another subdirectory`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/probe.git@v1#subdirectory=python"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan archive dependency as installed for the same URL`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {}}"""

        // when
        val installs = planInstalls("direct-url-probe @ https://user:secret@example.com/probe-1.0.tar.gz", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan archive dependency as installed for the same URL and hash`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {"hash": "sha256=$HASH", "hashes": {"sha256": "$HASH"}}}"""

        // when
        val installs = planInstalls("direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha256=$HASH", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan archive dependency as installed for the same legacy hash`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {"hash": "sha256=$HASH"}}"""

        // when
        val installs = planInstalls("direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha256=$HASH", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan archive dependency as missing for another hash`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {"hashes": {"sha256": "$HASH"}}}"""
        val dependency = "direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha256=${HASH.reversed()}"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan local archive dependency as missing for hash not recorded on installation`() {
        // given
        val directUrl = """{"url": "file:///home/user/probe-1.0.tar.gz", "archive_info": {}}"""
        val dependency = "direct-url-probe @ file:///home/user/probe-1.0.tar.gz#sha256=$HASH"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan archive dependency as missing for another URL`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {}}"""
        val dependency = "direct-url-probe @ https://example.com/probe-1.1.tar.gz"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan archive dependency as missing when installed from git`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT"}}"""
        val dependency = "direct-url-probe @ https://example.com/probe.git"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency as installed for unchanged content`() {
        // given
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("archive content")
            val url = archive.toUri().toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            val directUrl = """{"url": "$url", "archive_info": {"hashes": {"sha256": "$hash"}}}"""

            // when
            val installs = planInstalls("direct-url-probe @ $url", directUrl)

            // then
            assert(installs.isEmpty())
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency as missing for changed content`() {
        // given
        // a local archive can be rebuilt in the same version, without changing its URL
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("rebuilt archive content")
            val dependency = "direct-url-probe @ ${archive.toUri()}"
            val directUrl = """{"url": "${archive.toUri()}", "archive_info": {"hashes": {"sha256": "$HASH"}}}"""

            // when
            val installs = planInstalls(dependency, directUrl)

            // then
            assert(installs == listOf(dependency))
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency installed by uv as installed for unchanged change time`() {
        // given
        // uv records no hash of a local archive, but its change time in uv_cache.json, the last write time on Windows
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("archive content")
            // a modification time other than the change time
            archive.setLastModifiedTime(FileTime.from(Instant.ofEpochSecond(1_700_000_000, 123_456_700)))
            val url = archive.toUri().toString()
            val directUrl = """{"url":"$url","archive_info":{}}"""
            val uvCache = uvCache(uvTimestamp(archive))

            // when
            val installs = planInstalls("direct-url-probe @ $url", directUrl, uvCache)

            // then
            assert(installs.isEmpty())
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency installed by uv as missing for changed change time`() {
        // given
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("rebuilt archive content")
            val dependency = "direct-url-probe @ ${archive.toUri()}"
            val directUrl = """{"url":"${archive.toUri()}","archive_info":{}}"""
            // as if installed before the archive was rebuilt
            val uvCache = uvCache(uvTimestamp(archive).minusSeconds(1))

            // when
            val installs = planInstalls(dependency, directUrl, uvCache)

            // then
            assert(installs == listOf(dependency))
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @Test
    fun `should plan local archive dependency as missing without recorded hash or modification time`() {
        // given
        val directUrl = """{"url": "file:///home/user/probe-1.0.tar.gz", "archive_info": {}}"""
        val dependency = "direct-url-probe @ file:///home/user/probe-1.0.tar.gz"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan hashed local archive dependency installed by uv as installed`() {
        // given
        // uv keeps the hash fragment in the URL of a local archive, and records no hashes
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("archive content")
            val hash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            val url = "${archive.toUri()}#sha256=$hash"
            val directUrl = """{"url":"$url","archive_info":{}}"""

            // when
            val installs = planInstalls("direct-url-probe @ $url", directUrl)

            // then
            assert(installs.isEmpty())
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @Test
    fun `should plan hashed remote archive dependency installed by uv as installed`() {
        // given
        // uv drops the hash fragment from the URL of a remote archive, and records no hashes
        val directUrl = """{"url":"https://example.com/probe-1.0.tar.gz","archive_info":{}}"""

        // when
        val installs = planInstalls("direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha256=$HASH", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan archive dependency as installed for recorded hash differing from URL fragment`() {
        // given
        // the hashes recorded for the archive are the ones verified on installation, not the URL fragment
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz#sha256=${HASH.reversed()}", "archive_info": {"hashes": {"sha256": "$HASH"}}}"""

        // when
        val installs = planInstalls("direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha256=$HASH", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan archive dependency as missing for hash of another algorithm than recorded`() {
        // given
        // pip records only the sha256 hash, so a requested hash of another algorithm cannot be verified
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {"hashes": {"sha256": "$HASH"}}}"""
        val dependency = "direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha512=$HASH$HASH"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan archive dependency as missing next to stale dist-info of another version`() {
        // given
        // pip install --target --upgrade to another version leaves the dist-info of the previous one,
        // so it cannot be known which of them the files on disk belong to
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {"hashes": {"sha256": "$HASH"}}}"""
        val dependency = "direct-url-probe @ https://example.com/probe-1.0.tar.gz"

        // when
        val installs = planInstalls(dependency, directUrl, otherVersion = "2.0")

        // then
        assert(installs == listOf(dependency))
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency with unverifiable hash as missing`() {
        // given
        // a shake digest has no fixed length to compare
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("archive content")
            val url = "${archive.toUri()}#shake_256=$HASH"
            val dependency = "direct-url-probe @ $url"
            val directUrl = """{"url":"$url","archive_info":{}}"""

            // when
            val installs = planInstalls(dependency, directUrl)

            // then
            assert(installs == listOf(dependency))
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @Test
    fun `should plan installed URL dependency for installation with missing dependency`() {
        // given
        // pip resolves the missing dependency without the dependency directory,
        // so a distribution required by it would be installed from the index over the one installed from the URL
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/probe.git@v1"

        // when
        val installs = planInstalls(dependency, directUrl, otherDependencies = listOf("nonexistent-dependency"))

        // then
        assert(installs == listOf(dependency, "nonexistent-dependency"))
    }

    @Test
    fun `should plan URL dependency with extras as missing`() {
        // given
        // the dependencies of the extras are not checked, see #47
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe[extra] @ git+https://example.com/probe.git@v1"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan local directory dependency as missing even for the same URL`() {
        // given
        // the content of a local directory can change without changing its URL
        val directUrl = """{"url": "file:///home/user/probe", "dir_info": {}}"""
        val dependency = "direct-url-probe @ file:///home/user/probe"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan URL dependency as missing when installed from index`() {
        // given
        val dependency = "direct-url-probe @ https://example.com/probe-1.0.tar.gz"

        // when
        val installs = planInstalls(dependency, directUrl = null)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan URL dependency as missing for invalid direct_url_json`() {
        // given
        val dependency = "direct-url-probe @ https://example.com/probe-1.0.tar.gz"

        // when
        val installs = planInstalls(dependency, directUrl = "not JSON")

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan version dependency as installed when installed from URL`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT"}}"""

        // when
        val installs = planInstalls("direct-url-probe>=1.0", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan version dependency as missing next to dist-info of version it replaced`() {
        withReplacedVersion(installed = "2.0", replaced = "1.0") { directory ->
            // when
            val installs = planInstalls(directory, "replaced-probe==1.0")

            // then
            assert(installs == listOf("replaced-probe==1.0"))
        }
    }

    @Test
    fun `should plan version dependency as missing next to dist-info of other version`() {
        // given
        // it is not known which of them the installed files belong to, so the installation writes a new one
        withReplacedVersion(installed = "1.0", replaced = "2.0") { directory ->
            // when
            val installs = planInstalls(directory, "replaced-probe")

            // then
            assert(installs == listOf("replaced-probe"))
        }
    }

    @Test
    fun `should plan installed dependency as missing when its requirement is replaced by unsatisfying version`() {
        withDistInfos { directory ->
            // given
            // required-probe 2.0 installed for requiring-probe, then replaced by 1.0 declared by another snippet
            writeDistInfo(directory, "1.0", name = "requiring-probe", requires = listOf("required-probe>=2"))
            writeDistInfo(directory, "1.0", name = "required-probe")

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs == listOf("requiring-probe"))
        }
    }

    @Test
    fun `should plan installed dependency as installed when its requirements are satisfied`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(directory, "1.0", name = "requiring-probe", requires = listOf("required-probe>=2"))
            writeDistInfo(directory, "2.0", name = "required-probe")

            // when
            val installs = planInstalls(directory, "requiring-probe==1.0")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should plan installed dependency as missing when its requirement is not installed`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(directory, "1.0", name = "requiring-probe", requires = listOf("required-probe"))

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs == listOf("requiring-probe"))
        }
    }

    @Test
    fun `should plan installed dependency as missing when requirement of its requirement is not satisfied`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(directory, "1.0", name = "requiring-probe", requires = listOf("middle-probe"))
            writeDistInfo(directory, "1.0", name = "middle-probe", requires = listOf("required-probe>=2"))
            writeDistInfo(directory, "1.0", name = "required-probe")

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs == listOf("requiring-probe"))
        }
    }

    @Test
    fun `should plan installed dependency as installed with requirements whose markers do not apply`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(
                directory, "1.0", name = "requiring-probe",
                requires = listOf("required-probe>=2; python_version < \"3\"", "other-probe; extra == \"fast\"")
            )
            writeDistInfo(directory, "1.0", name = "required-probe")

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should plan installed dependency as installed with requirement of empty extra`() {
        withDistInfos { directory ->
            // given
            // uv and pip up to 23.0 never install it, so it would be installed again on every use
            writeDistInfo(
                directory, "1.0", name = "requiring-probe",
                requires = listOf("required-probe>=2; extra == \"\"", "other-probe; extra == \"\" or python_version < \"3\"")
            )
            writeDistInfo(directory, "1.0", name = "required-probe")

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should plan version dependency as installed in version not of PEP 440`() {
        withDistInfos { directory ->
            // given
            // like a Debian package, which packaging 22 to 24 fails to compare with InvalidVersion
            writeDistInfo(directory, "1.1build1", name = "legacy-probe")

            // when
            val installs = planInstalls(directory, "legacy-probe>=1")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should plan installed dependency as installed with requirement installed in version not of PEP 440`() {
        withDistInfos { directory ->
            // given
            // like a Debian package, e.g. 1.1build1, which an installer does not replace either
            writeDistInfo(
                directory, "1.0", name = "requiring-probe", requires = listOf("legacy-probe>=1", "other-legacy-probe")
            )
            writeDistInfo(directory, "1.1build1", name = "legacy-probe")
            writeDistInfo(directory, "1.1build1", name = "other-legacy-probe")

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should plan installed dependency as installed with requirement of requested extra not installed`() {
        withDistInfos { directory ->
            // given
            // the requirements of extras are not checked (#47), as an installer can tell a requested extra
            // by another normalization, or not at all without Provides-Extra, which would install it on every use
            writeDistInfo(directory, "1.0", name = "requiring-probe", requires = listOf("other-probe; extra == \"Foo_Bar\""))

            // when
            val installs = planInstalls(directory, "requiring-probe[foo-bar]")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should plan installed dependency as installed with requirement of extra requested by its requirement not installed`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(directory, "1.0", name = "requiring-probe", requires = listOf("required-probe[fast]"))
            writeDistInfo(directory, "1.0", name = "required-probe", requires = listOf("other-probe; extra == \"fast\""))

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should plan installed dependencies requiring each other as installed`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(directory, "1.0", name = "requiring-probe", requires = listOf("required-probe"))
            writeDistInfo(directory, "1.0", name = "required-probe", requires = listOf("requiring-probe>=1"))

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should plan installed dependency as installed with requirement satisfied by environment`() {
        withDistInfos { directory ->
            // given
            // jep is installed in the environment of the tests, not in the dependency directory
            writeDistInfo(directory, "1.0", name = "requiring-probe", requires = listOf("jep>=1"))

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should plan installed dependency as missing when its URL requirement is installed from other URL`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(
                directory, "1.0", name = "requiring-probe",
                requires = listOf("required-probe @ https://example.com/required_probe-2.0-py3-none-any.whl")
            )
            writeDistInfo(
                directory, "1.0", name = "required-probe",
                directUrl = """{"url": "https://example.com/required_probe-1.0-py3-none-any.whl", "archive_info": {}}"""
            )

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs == listOf("requiring-probe"))
        }
    }

    @Test
    fun `should plan installed dependency as missing when its URL requirement is installed from index`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(
                directory, "1.0", name = "requiring-probe",
                requires = listOf("required-probe @ https://example.com/required_probe-1.0-py3-none-any.whl")
            )
            writeDistInfo(directory, "1.0", name = "required-probe")

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs == listOf("requiring-probe"))
        }
    }

    @Test
    fun `should plan installed dependency as installed when its URL requirements are installed from same URLs`() {
        withDistInfos { directory ->
            // given
            // whatever the revision, the content or the credentials, as the declared dependency installed again
            // would install them again also when they cannot change, e.g. from a local directory,
            // or from an archive with a hash not recorded on installation, so on every use
            writeDistInfo(
                directory, "1.0", name = "requiring-probe",
                requires = listOf(
                    "required-probe @ git+https://user@example.com/probe.git@main#subdirectory=python",
                    "directory-probe @ file:///home/user/probe",
                    "archive-probe @ https://example.com/archive_probe-1.0.tar.gz#md5=0123456789abcdef0123456789abcdef"
                )
            )
            writeDistInfo(
                directory, "1.0", name = "required-probe",
                directUrl = """{"url": "https://example.com/probe.git", "subdirectory": "python", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
            )
            writeDistInfo(
                directory, "1.0", name = "directory-probe",
                directUrl = """{"url": "file:///home/user/probe", "dir_info": {}}"""
            )
            writeDistInfo(
                directory, "1.0", name = "archive-probe",
                directUrl = """{"url": "https://example.com/archive_probe-1.0.tar.gz", "archive_info": {"hashes": {"sha256": "$HASH"}}}"""
            )

            // when
            val installs = planInstalls(directory, "requiring-probe")

            // then
            assert(installs.isEmpty())
        }
    }

    @Test
    fun `should constrain imported distribution to version of its installed modules next to dist-info of other version`() {
        // when
        val constraints = listOf("1.0" to "2.0", "2.0" to "1.0").map { (installed, replaced) ->
            withReplacedVersion(installed = installed, replaced = replaced) { directory ->
                PythonDependencies.plan(
                    "dependencies = ['nonexistent-dependency']\n", directory.toString(), listOf("replaced_probe")
                ).filter { it.first == "constraint" && it.second.startsWith("replaced-probe==") }.map { it.second }
            }
        }

        // then
        assert(constraints == listOf(listOf("replaced-probe==1.0"), listOf("replaced-probe==2.0")))
    }

    @Test
    fun `should constrain imported distribution to version recording most of its installed modules`() {
        // given
        // 2.0 only added a module, so the files recorded by 1.0 are installed too
        withDistInfos { directory ->
            val modules = mapOf(
                "replaced_probe/__init__.py" to "X = 1\n",
                "replaced_probe/extra.py" to "Y = 2\n"
            )
            modules.forEach { (path, content) -> directory.resolve(path).createParentDirectories().writeText(content) }
            writeDistInfo(directory, "2.0", modules)
            writeDistInfo(directory, "1.0", modules.filterKeys { it.endsWith("__init__.py") })

            // when
            val constraints = importedConstraints(directory)

            // then
            assert(constraints == listOf("replaced-probe==2.0"))
        }
    }

    @Test
    fun `should constrain imported distribution to highest version recording same installed modules`() {
        // given
        withDistInfos { directory ->
            val modules = mapOf("replaced_probe/__init__.py" to "X = 1\n")
            modules.forEach { (path, content) -> directory.resolve(path).createParentDirectories().writeText(content) }
            // neither the first nor the last by name
            writeDistInfo(directory, "1.0", modules)
            writeDistInfo(directory, "10.0", modules)
            writeDistInfo(directory, "2.0", modules)

            // when
            val constraints = importedConstraints(directory)

            // then
            assert(constraints == listOf("replaced-probe==10.0"))
        }
    }

    @Test
    fun `should constrain imported package of distribution without top_level_txt`() {
        // given
        // like a wheel built by hatchling or flit, which Python 3.10 tells the modules of only by top_level.txt
        withDistInfos { directory ->
            val modules = mapOf(
                "replaced_probe/__init__.py" to "X = 1\n",
                "../../bin/replaced-probe" to ""
            )
            directory.resolve("replaced_probe/__init__.py").createParentDirectories().writeText("X = 1\n")
            writeDistInfo(directory, "1.0", modules, topLevel = false)

            // when
            val constraints = importedConstraints(directory)

            // then
            assert(constraints == listOf("replaced-probe==1.0"))
        }
    }

    @Test
    fun `should constrain imported single-file module of distribution without top_level_txt`() {
        // given
        withDistInfos { directory ->
            val modules = mapOf("replaced_probe.py" to "X = 1\n")
            modules.forEach { (path, content) -> directory.resolve(path).writeText(content) }
            writeDistInfo(directory, "1.0", modules, topLevel = false)

            // when
            val constraints = importedConstraints(directory)

            // then
            assert(constraints == listOf("replaced-probe==1.0"))
        }
    }

    @Test
    fun `should not constrain distribution without top_level_txt of modules not imported`() {
        // given
        // its .pth file, scripts and data, and files outside the directory are no modules
        withDistInfos { directory ->
            val files = mapOf(
                "other_probe/__init__.py" to "X = 1\n",
                "replaced_probe.pth" to "",
                "replaced_probe-1.0.data/scripts/replaced_probe" to "",
                "../../bin/replaced_probe" to ""
            )
            directory.resolve("other_probe/__init__.py").createParentDirectories().writeText("X = 1\n")
            writeDistInfo(directory, "1.0", files, topLevel = false)

            // when
            val constraints = importedConstraints(directory)

            // then
            assert(constraints.isEmpty())
        }
    }

    @Test
    fun `should remove dist-info of other version by name when identities are unknown`() {
        withDistInfos { directory ->
            // given
            // e.g. on FAT, without inode numbers
            writeDistInfo(directory, "1.0")
            writeDistInfo(directory, "2.0")
            val snapshot = """{"replaced_probe-1.0.dist-info": null, "replaced_probe-2.0.dist-info": null}"""
            writeDistInfo(directory, "3.0")

            // when
            PythonDependencies.removeReplacedDistInfo(directory.toString(), snapshot)

            // then
            assert(directory.distInfoNames() == listOf("replaced_probe-3.0.dist-info"))
        }
    }

    @Test
    fun `should keep dist-info of other version when identity of version written again is unknown`() {
        withReplacedVersion(installed = "2.0", replaced = "1.0") { directory ->
            // given
            val snapshot = """{"replaced_probe-1.0.dist-info": null, "replaced_probe-2.0.dist-info": null}"""

            // when
            PythonDependencies.removeReplacedDistInfo(directory.toString(), snapshot)

            // then
            assert(directory.distInfoNames() == listOf("replaced_probe-1.0.dist-info", "replaced_probe-2.0.dist-info"))
        }
    }

    @Test
    fun `should remove dist-info of other version when installation writes new one`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(directory, "1.0")
            val snapshot = distInfoSnapshot(directory)
            writeDistInfo(directory, "2.0")

            // when
            PythonDependencies.removeReplacedDistInfo(directory.toString(), snapshot)

            // then
            assert(directory.distInfoNames() == listOf("replaced_probe-2.0.dist-info"))
        }
    }

    @Test
    fun `should remove dist-info of other version when installation writes same version again`() {
        withReplacedVersion(installed = "2.0", replaced = "1.0") { directory ->
            // given
            val snapshot = distInfoSnapshot(directory)
            // like pip, which writes it elsewhere, removes the previous one, and moves it into the directory
            val written = directory.resolveSibling("${directory.name}-written").createDirectory()
            writeDistInfo(written, "2.0")
            @OptIn(ExperimentalPathApi::class)
            directory.resolve("replaced_probe-2.0.dist-info").deleteRecursively()
            Files.move(written.resolve("replaced_probe-2.0.dist-info"), directory.resolve("replaced_probe-2.0.dist-info"))
            written.deleteIfExists()

            // when
            PythonDependencies.removeReplacedDistInfo(directory.toString(), snapshot)

            // then
            assert(directory.distInfoNames() == listOf("replaced_probe-2.0.dist-info"))
            assert(directory.resolve("replaced_probe/__init__.py").readText() == "VERSION = '2.0'\n")
        }
    }

    @Test
    fun `should keep dist-info of other versions when installation writes none of distribution`() {
        withReplacedVersion(installed = "2.0", replaced = "1.0") { directory ->
            // given
            val snapshot = distInfoSnapshot(directory)
            writeDistInfo(directory, "1.0", name = "other-probe")

            // when
            PythonDependencies.removeReplacedDistInfo(directory.toString(), snapshot)

            // then
            assert(
                directory.distInfoNames() == listOf(
                    "other_probe-1.0.dist-info",
                    "replaced_probe-1.0.dist-info",
                    "replaced_probe-2.0.dist-info"
                )
            )
        }
    }

    @Test
    fun `should keep dist-info of other version when installation writes several of distribution`() {
        withDistInfos { directory ->
            // given
            writeDistInfo(directory, "1.0")
            val snapshot = distInfoSnapshot(directory)
            writeDistInfo(directory, "2.0")
            writeDistInfo(directory, "3.0")

            // when
            PythonDependencies.removeReplacedDistInfo(directory.toString(), snapshot)

            // then
            assert(
                directory.distInfoNames() == listOf(
                    "replaced_probe-1.0.dist-info",
                    "replaced_probe-2.0.dist-info",
                    "replaced_probe-3.0.dist-info"
                )
            )
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should remove dist-info of version replaced by pip install of other distribution`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory!!
        val wheels = createTempDirectory("python-wheels")
        try {
            val version1 = writeWheel(wheels, "pip-replaced-probe", "1.0")
            val version2 = writeWheel(wheels, "pip-replaced-probe", "2.0")
            val dependent = writeWheel(
                wheels, "pip-replaced-probe-dependent", "1.0", requires = listOf("pip-replaced-probe @ $version2")
            )
            Python().use { python ->
                python.exec("# /// script\n# dependencies = [\"pip-replaced-probe @ $version1\"]\n# ///\nx = 1\n")

                // when
                python.exec("# /// script\n# dependencies = [\"pip-replaced-probe-dependent @ $dependent\"]\n# ///\nx = 1\n")
            }

            // then
            assert(directory.distInfoNames().filter { it.startsWith("pip_replaced_probe-") } == listOf("pip_replaced_probe-2.0.dist-info"))
            assert(planInstalls(directory, "pip-replaced-probe==1.0") == listOf("pip-replaced-probe==1.0"))
            assert(planInstalls(directory, "pip-replaced-probe==2.0").isEmpty())
        } finally {
            wheels.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should install again dependency whose requirement was replaced by pip install of other version`() {
        // given
        PythonEnvironment.initialize()
        val wheels = createTempDirectory("python-wheels")
        try {
            val version1 = writeWheel(wheels, "pip-required-probe", "1.0")
            val version2 = writeWheel(wheels, "pip-required-probe", "2.0")
            val requiring = writeWheel(
                wheels, "pip-requiring-probe", "1.0", requires = listOf("pip-required-probe @ $version2")
            )
            val code = "# /// script\n# dependencies = [\"pip-requiring-probe @ $requiring\"]\n# ///\nx = 1\n"
            Python().use { python ->
                python.exec(code)
                python.exec("# /// script\n# dependencies = [\"pip-required-probe @ $version1\"]\n# ///\nx = 1\n")

                // when
                python.exec(code)
            }

            // then
            val version = Python().use { python ->
                python.exec("import pip_required_probe")
                python.eval<String>("pip_required_probe.VERSION")
            }
            assert(version == "2.0")
        } finally {
            wheels.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should remove dist-info of version with scripts and data replaced by pip install of other distribution`() {
        // given
        // pip install --target records the scripts and the data files outside the directory, e.g. ../../bin/
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory!!
        val wheels = createTempDirectory("python-wheels")
        try {
            val version1 = writeWheel(wheels, "pip-scripts-probe", "1.0", scriptsAndData = true)
            val version2 = writeWheel(wheels, "pip-scripts-probe", "2.0", scriptsAndData = true)
            val dependent = writeWheel(
                wheels, "pip-scripts-probe-dependent", "1.0", requires = listOf("pip-scripts-probe @ $version2")
            )
            Python().use { python ->
                python.exec("# /// script\n# dependencies = [\"pip-scripts-probe @ $version1\"]\n# ///\nx = 1\n")

                // when
                python.exec("# /// script\n# dependencies = [\"pip-scripts-probe-dependent @ $dependent\"]\n# ///\nx = 1\n")
            }

            // then
            assert(directory.distInfoNames().filter { it.startsWith("pip_scripts_probe-") } == listOf("pip_scripts_probe-2.0.dist-info"))
            assert(planInstalls(directory, "pip-scripts-probe").isEmpty())
            assert(planInstalls(directory, "pip-scripts-probe==1.0") == listOf("pip-scripts-probe==1.0"))
        } finally {
            wheels.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should remove dist-info of other version left by earlier installation when installing again`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory!!
        val wheels = createTempDirectory("python-wheels")
        try {
            val wheel = writeWheel(wheels, "pip-left-probe", "1.0", scriptsAndData = true)
            val code = "# /// script\n# dependencies = [\"pip-left-probe @ $wheel\"]\n# ///\nx = 1\n"
            Python().use { python ->
                python.exec(code)
                // e.g. by an earlier version of this library
                writeDistInfo(directory, "0.9", name = "pip-left-probe")

                // when
                python.exec(code)
            }

            // then
            assert(directory.distInfoNames().filter { it.startsWith("pip_left_probe-") } == listOf("pip_left_probe-1.0.dist-info"))
            assert(planInstalls(directory, "pip-left-probe==1.0").isEmpty())
        } finally {
            wheels.deleteRecursively()
        }
    }

    /**
     * Runs the [block] with a dependency directory holding the files of `replaced-probe` [installed] version,
     * with its dist-info, and the dist-info of the [replaced] version, recording files of other content,
     * as `pip install --target --upgrade` leaves it, both also recording a script outside the directory,
     * like `pip install --target` does.
     */
    private fun <T> withReplacedVersion(
        installed: String,
        replaced: String,
        block: (Path) -> T
    ): T = withDistInfos { directory ->
        val module = "replaced_probe/__init__.py"
        directory.resolve(module).createParentDirectories().writeText("VERSION = '$installed'\n")
        listOf(replaced, installed).forEach { version ->
            writeDistInfo(
                directory, version, mapOf(module to "VERSION = '$version'\n", "../../bin/replaced-probe" to version)
            )
        }
        block(directory)
    }

    @OptIn(ExperimentalPathApi::class)
    private fun <T> withDistInfos(block: (Path) -> T): T {
        PythonEnvironment.initialize()
        val directory = createTempDirectory("python-dependencies")
        try {
            return block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Writes the dist-info directory of the [name] [version] into the [directory],
     * with a `RECORD` of the [files] with the hashes of the given content, whatever the [directory] holds,
     * with a `Requires-Dist` of each of the [requires], and the `direct_url.json` of the [directUrl], if given.
     */
    private fun writeDistInfo(
        directory: Path,
        version: String,
        files: Map<String, String> = emptyMap(),
        name: String = "replaced-probe",
        topLevel: Boolean = true,
        requires: List<String> = emptyList(),
        directUrl: String? = null
    ) {
        val distInfo = "${name.replace('-', '_')}-$version.dist-info"
        val metadata = mapOf(
            "$distInfo/METADATA" to "Metadata-Version: 2.1\nName: $name\nVersion: $version\n" +
                requires.joinToString("") { "Requires-Dist: $it\n" }
        ) + (if (directUrl != null) mapOf("$distInfo/direct_url.json" to directUrl) else emptyMap()) + if (topLevel) mapOf(
            // as importlib.metadata.packages_distributions of Python 3.10 tells the modules only by it
            "$distInfo/top_level.txt" to "${name.replace('-', '_')}\n"
        ) else emptyMap()
        metadata.forEach { (path, content) -> directory.resolve(path).createParentDirectories().writeText(content) }
        directory.resolve("$distInfo/RECORD").writeText(record(files + metadata, distInfo))
    }

    private fun Path.distInfoNames(): List<String> =
        listDirectoryEntries("*.dist-info").map { it.name }.sorted()

    /**
     * The URL of the wheel of the [name] [version], written into the [directory], with a module of the version,
     * and with a console script and a data file, if [scriptsAndData].
     */
    private fun writeWheel(
        directory: Path,
        name: String,
        version: String,
        requires: List<String> = emptyList(),
        scriptsAndData: Boolean = false
    ): String {
        val distribution = name.replace('-', '_')
        val distInfo = "$distribution-$version.dist-info"
        val files = mapOf(
            "$distribution/__init__.py" to "VERSION = '$version'\ndef main():\n    print(VERSION)\n",
            "$distInfo/METADATA" to "Metadata-Version: 2.1\nName: $name\nVersion: $version\n" +
                requires.joinToString("") { "Requires-Dist: $it\n" },
            "$distInfo/WHEEL" to "Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n"
        ) + if (scriptsAndData) mapOf(
            "$distInfo/entry_points.txt" to "[console_scripts]\n$name = $distribution:main\n",
            "$distribution-$version.data/data/share/$distribution/version.txt" to "$version\n"
        ) else emptyMap()
        val wheel = directory.resolve("$distribution-$version-py3-none-any.whl")
        ZipOutputStream(wheel.outputStream()).use { zip ->
            (files + ("$distInfo/RECORD" to record(files, distInfo))).forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return wheel.toUri().toString()
    }

    /** The `RECORD` of the [files] of the [distInfo] directory, with their sha256 hashes. */
    private fun record(files: Map<String, String>, distInfo: String): String =
        files.entries.joinToString("") { (path, content) ->
            val bytes = content.toByteArray()
            val hash = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
            "$path,sha256=$hash,${bytes.size}\n"
        } + "$distInfo/RECORD,,\n"

    /** The `dist-info` snapshot of the plan of the [directory], printed when something is to be installed. */
    private fun distInfoSnapshot(directory: Path): String =
        PythonDependencies.plan("dependencies = ['nonexistent-dependency']\n", directory.toString(), emptyList())
            .single { it.first == "dist-info" }.second

    /** The constraints of `replaced-probe` planned with its module imported, for a missing dependency. */
    private fun importedConstraints(directory: Path): List<String> =
        PythonDependencies.plan(
            "dependencies = ['nonexistent-dependency']\n", directory.toString(), listOf("replaced_probe")
        ).filter { it.first == "constraint" && it.second.startsWith("replaced-probe==") }.map { it.second }

    private fun planInstalls(directory: Path, dependency: String): List<String> =
        PythonDependencies.plan("dependencies = ['$dependency']\n", directory.toString(), emptyList())
            .filter { it.first == "install" }
            .map { it.second }

    /**
     * The requirements to install, planned for the [dependency]
     * with the dependency directory holding `direct-url-probe 1.0` installed from the [directUrl] (PEP 610),
     * or from an index when `null`,
     * and also the `dist-info` directory of its [otherVersion], if given, with the same `direct_url.json`,
     * so that whichever of them `importlib.metadata` finds first matches the [dependency].
     */
    @OptIn(ExperimentalPathApi::class)
    private fun planInstalls(
        dependency: String,
        directUrl: String?,
        uvCache: String? = null,
        otherDependencies: List<String> = emptyList(),
        otherVersion: String? = null
    ): List<String> {
        PythonEnvironment.initialize()
        val directory = createTempDirectory("python-dependencies")
        try {
            val distInfo = directory.resolve("direct_url_probe-1.0.dist-info").createDirectories()
            distInfo.resolve("METADATA").writeText("Metadata-Version: 2.1\nName: direct-url-probe\nVersion: 1.0\n")
            if (directUrl != null) distInfo.resolve("direct_url.json").writeText(directUrl)
            if (uvCache != null) distInfo.resolve("uv_cache.json").writeText(uvCache)
            if (otherVersion != null) {
                val otherDistInfo = directory.resolve("direct_url_probe-$otherVersion.dist-info").createDirectories()
                otherDistInfo.resolve("METADATA")
                    .writeText("Metadata-Version: 2.1\nName: direct-url-probe\nVersion: $otherVersion\n")
                if (directUrl != null) otherDistInfo.resolve("direct_url.json").writeText(directUrl)
            }
            val dependencies = (listOf(dependency) + otherDependencies).joinToString(", ") { "'$it'" }
            return PythonDependencies.plan("dependencies = [$dependencies]\n", directory.toString(), emptyList())
                .filter { it.first == "install" }
                .map { it.second }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** The time of the [archive] which uv records in `uv_cache.json`: its change time, or last write time on Windows. */
    private fun uvTimestamp(archive: Path): Instant =
        if (System.getProperty("os.name").startsWith("Windows")) archive.getLastModifiedTime().toInstant()
        else (Files.getAttribute(archive, "unix:ctime") as FileTime).toInstant()

    private fun uvCache(timestamp: Instant): String =
        """{"timestamp":{"secs_since_epoch":${timestamp.epochSecond},"nanos_since_epoch":${timestamp.nano}},""" +
            """"commit":null,"tags":null,"env":{},"directories":{}}"""

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        const val HASH = "427f82ccf8f86e95585ffde082ac93c543fa3582c45b40b303148abd537d002e"

        val OTHER_INSTALLATION = """
            import fcntl, os, sys

            lock_file, distribution = sys.argv[1], sys.argv[2]
            fd = os.open(lock_file, os.O_RDWR | os.O_CREAT, 0o666)
            fcntl.lockf(fd, fcntl.LOCK_EX)
            print("locked", flush=True)
            sys.stdin.read()
            if distribution:
                info = os.path.join(
                    os.path.dirname(lock_file), distribution.replace("-", "_") + "-1.0.dist-info"
                )
                os.makedirs(info)
                with open(os.path.join(info, "METADATA"), "w") as metadata:
                    metadata.write(f"Metadata-Version: 2.1\nName: {distribution}\nVersion: 1.0\n")
            fcntl.lockf(fd, fcntl.LOCK_UN)
        """.trimIndent()

    }

}
