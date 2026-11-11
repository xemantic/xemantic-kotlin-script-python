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
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isWritable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
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

    private fun pinnedRequirement(@Language("json") distribution: String): String {
        val (exitCode, output) = runProcess(
            listOf(
                python, "-c",
                PythonDependencies.PIP_PINNED_REQUIREMENT +
                    "\nimport json, sys\nprint(pinned_requirement(json.load(sys.stdin)))"
            ),
            input = distribution
        )
        assert(exitCode == 0, output)
        return output
    }

    /**
     * A git repository on the `main` branch, also tagged `v1`,
     * of the project of the distribution [name] 1.0, in the [subdirectory], if given, with its `ANSWER` 42.
     */
    private fun gitRepository(name: String, subdirectory: String? = null): Path {
        val repository = sources.resolve("repository")
        val project = (if (subdirectory != null) repository.resolve(subdirectory) else repository).createDirectories()
        writeProject(project, name)
        git(repository, "init", "--quiet", "-b", "main")
        commit(repository, name)
        git(repository, "tag", "v1")
        return repository
    }

    /** Writes the project of the distribution [name] 1.0 into the [directory], requiring the [dependencies]. */
    private fun writeProject(directory: Path, name: String, dependencies: List<String> = emptyList()) {
        directory.resolve("pyproject.toml").writeText(
            """
            [build-system]
            requires = ["setuptools"]
            build-backend = "setuptools.build_meta"

            [project]
            name = "$name"
            version = "1.0"
            dependencies = [${dependencies.joinToString(", ") { "'$it'" }}]
            """.trimIndent()
        )
        directory.resolve(name).createDirectories().resolve("__init__.py").writeText("ANSWER = 42\n")
    }

    private fun commit(repository: Path, message: String) {
        git(repository, "add", ".")
        git(repository, "commit", "--quiet", "-m", message)
    }

    private fun git(repository: Path, vararg command: String) {
        val (exitCode, output) = runProcess(
            listOf("git", "-C", repository.toString(), "-c", "user.name=test", "-c", "user.email=test@example.com") +
                command
        )
        assert(exitCode == 0, output)
    }

    /** Builds the source archive of the distribution [name] 1.0 into the [directory], built with setuptools. */
    private fun buildSourceArchive(directory: Path, name: String): Path {
        val archive = directory.createDirectories().resolve("$name-1.0.tar.gz")
        val (exitCode, output) = runProcess(listOf(python, "-c", BUILD_SOURCE_ARCHIVE, archive.toString(), name))
        assert(exitCode == 0, output)
        return archive
    }

    /** Builds the wheel of the distribution [name] 1.0 into the [directory], with the [answer] as its `ANSWER`. */
    private fun buildWheel(
        directory: Path,
        name: String,
        answer: Int = 42,
        dependencies: List<String> = emptyList()
    ): Path {
        val wheel = directory.createDirectories().resolve("$name-1.0-py3-none-any.whl")
        val (exitCode, output) = runProcess(
            listOf(python, "-c", BUILD_WHEEL, wheel.toString(), name, answer.toString()) + dependencies
        )
        assert(exitCode == 0, output)
        return wheel
    }

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
        // uv resolves the missing dependency without the dependency directory,
        // so a distribution required by it would be installed from the index over the one installed from the URL
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/probe.git@v1"

        // when
        val entries = planEntries(dependency, directUrl, otherDependencies = listOf("nonexistent-dependency"))

        // then
        assert(entries == listOf("installed" to dependency, "install" to "nonexistent-dependency"))
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

    /** The requirements to install, the `install` entries of [planEntries]. */
    private fun planInstalls(
        dependency: String,
        directUrl: String?,
        uvCache: String? = null,
        otherVersion: String? = null
    ): List<String> = planEntries(dependency, directUrl, uvCache, otherVersion = otherVersion)
        .filter { it.first == "install" }
        .map { it.second }

    /**
     * The entries of the plan, other than the constraints, for the [dependency], and the [otherDependencies],
     * with the dependency directory holding `direct-url-probe 1.0` installed from the [directUrl] (PEP 610),
     * or from an index when `null`,
     * and also the `dist-info` directory of its [otherVersion], if given, with the same `direct_url.json`,
     * so that whichever of them `importlib.metadata` finds first matches the [dependency].
     */
    private fun planEntries(
        dependency: String,
        directUrl: String?,
        uvCache: String? = null,
        otherDependencies: List<String> = emptyList(),
        otherVersion: String? = null
    ): List<Pair<String, String>> {
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
                .filter { it.first != "constraint" }
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

    /**
     * pip does not see the distributions of a `--target` directory as installed,
     * so it would install them again, replacing the files of the modules, which might be already loaded.
     */
    @Test
    fun `should keep distribution of dependency directory when pip installs dependency requiring it`() {
        // given
        pipInstalled("idna==3.10")
        // a reinstalled module would lose the marker
        val idnaModule = directory.resolve("idna").resolve("__init__.py")
        idnaModule.appendText("\n# kept\n")

        // when
        val (exitCode, output) = pipInstall("requests")

        // then
        assert(exitCode == 0, output)
        assert(directory.resolve("requests").exists())
        assert(idnaModule.readText().endsWith("\n# kept\n"))
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
        val repository = gitRepository("subpkg", subdirectory = "libs/subpkg")

        // when
        val (exitCode, output) = pipInstall("subpkg @ git+${repository.toUri()}@main#subdirectory=libs/subpkg")

        // then
        assert(exitCode == 0, output)
        assert(pythonOfDirectory("import subpkg; print(subpkg.ANSWER)") == "42")
    }

    /**
     * pip records the requested revision of a git requirement in its `direct_url.json`,
     * which the plan compares with the one of the dependency,
     * so it has to be installed from the requirement itself, not pinned to the resolved commit.
     */
    @Test
    fun `should plan git dependency on tag installed with pip as installed`() {
        // given
        val dependency = "gitpkg @ git+${gitRepository("gitpkg").toUri().toString().removeSuffix("/")}@v1"
        pipInstalled(dependency)

        // when
        val installs = PythonDependencies.plan("dependencies = ['$dependency']\n", directory.toString(), emptyList())
            .filter { it.first == "install" }

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan git dependency without revision installed with pip as installed`() {
        // given
        val dependency = "gitpkg @ git+${gitRepository("gitpkg").toUri().toString().removeSuffix("/")}"
        pipInstalled(dependency)

        // when
        val installs = PythonDependencies.plan("dependencies = ['$dependency']\n", directory.toString(), emptyList())
            .filter { it.first == "install" }

        // then
        assert(installs.isEmpty())
    }

    /**
     * pip ignores the installed distributions with `--upgrade`, `--ignore-installed`, `--force-reinstall` or `--target`,
     * which can be configured by the environment variables, and pip.conf, whose options they override.
     */
    @Test
    fun `should keep distribution of dependency directory with pip configured to ignore installed ones`() {
        // given
        pipInstalled("idna==3.10")
        // a reinstalled module would lose the marker
        val idnaModule = directory.resolve("idna").resolve("__init__.py")
        idnaModule.appendText("\n# kept\n")

        // when
        val (exitCode, output) = pipInstall(
            "requests",
            environment = mapOf(
                "PIP_UPGRADE" to "1",
                "PIP_IGNORE_INSTALLED" to "1",
                "PIP_FORCE_REINSTALL" to "1",
                "PIP_TARGET" to sources.resolve("target").toString()
            )
        )

        // then
        assert(exitCode == 0, output)
        assert(directory.resolve("requests").exists())
        assert(idnaModule.readText().endsWith("\n# kept\n"))
    }

    /**
     * An installed dependency from a git branch is resolved as installed, at its installed commit,
     * as it is not installed again, so the later commits of the branch, requiring other dependencies, are ignored.
     */
    @Test
    fun `should resolve installed dependency from git branch at installed commit with pip`() {
        // given
        val repository = gitRepository("gitpkg")
        val installed = "gitpkg @ git+${repository.toUri().toString().removeSuffix("/")}@main"
        pipInstalled(installed)
        val required = buildWheel(sources.resolve("wheels"), "requiredpkg")
        writeProject(repository, "gitpkg", dependencies = listOf("requiredpkg @ ${required.toUri()}"))
        commit(repository, "requiring requiredpkg")
        val other = buildWheel(sources.resolve("wheels"), "otherpkg")

        // when
        val (exitCode, output) = runProcess(
            PythonDependencies.pipInstallCommand(
                python, directory.toString(), constraints.toString(),
                requirements = listOf("otherpkg @ ${other.toUri()}"),
                installed = listOf(installed)
            )
        )

        // then
        assert(exitCode == 0, output)
        assert(directory.resolve("otherpkg").exists())
        assert(!directory.resolve("requiredpkg").exists())
    }

    /**
     * pip reuses the last credentials of a host for a URL of the host without them,
     * so a distribution from a URL required by a requested one is installed with the requested URLs, like resolved.
     */
    @Test
    fun `should install required dependency from URL with last credentials of its host with pip`() {
        // given
        val wheels = sources.resolve("wheels")
        val server = ProcessBuilder(python, "-c", AUTHENTICATING_SERVER, wheels.toString(), "a/=u1:t1", "b/=u2:p@ss2")
            .redirectErrorStream(true)
            .start()
        try {
            val port = server.inputStream.bufferedReader().readLine()
            val required = buildWheel(wheels.resolve("a"), "pkga")
            val first = buildWheel(wheels.resolve("b"), "pkgb")
            val second = buildWheel(
                wheels.resolve("a"), "pkgc", dependencies = listOf("pkga @ http://127.0.0.1:$port/a/${required.fileName}")
            )

            // when
            val (exitCode, output) = pipInstall(
                "pkgb @ http://u2:p%40ss2@127.0.0.1:$port/b/${first.fileName}",
                "pkgc @ http://u1:t1@127.0.0.1:$port/a/${second.fileName}"
            )

            // then
            assert(exitCode == 0, output)
            assert(pythonOfDirectory("import pkga, pkgb, pkgc; print(pkga.ANSWER)") == "42")
        } finally {
            server.destroy()
            server.waitFor()
        }
    }

    /**
     * pip strips the credentials from the URL in its installation report,
     * so the distribution resolved from it would be installed without them.
     */
    @Test
    fun `should install dependency from URL requiring credentials with pip`() {
        // given
        val wheels = sources.resolve("wheels")
        val wheel = buildWheel(wheels, "authpkg")
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

    /**
     * pip does not install a distribution from a URL in the version already installed, unless from a local archive,
     * while a missing one, planned so by its `direct_url.json`, is another revision of it, e.g. of a git branch.
     */
    @Test
    fun `should install dependency from other URL in the version already in dependency directory with pip`() {
        // given
        val first = buildWheel(sources.resolve("first"), "urlpkg", answer = 1)
        val second = buildWheel(sources.resolve("second"), "urlpkg", answer = 2)
        pipInstalled("urlpkg @ ${first.toUri()}")

        // when
        val (exitCode, output) = pipInstall("urlpkg @ ${second.toUri()}")

        // then
        assert(exitCode == 0, output)
        assert(pythonOfDirectory("import urlpkg; print(urlpkg.ANSWER)") == "2")
    }

    /**
     * An installed dependency from a URL is passed to the installer along with the missing ones,
     * so that pip resolves with it, but does not reinstall it, also from a local archive.
     */
    @Test
    fun `should keep installed dependency from URL passed with missing one to pip`() {
        // given
        // pip reinstalls a local source archive in the version already installed
        val archive = buildSourceArchive(sources.resolve("archives"), "urlpkg")
        val installed = "urlpkg @ ${archive.toUri()}"
        pipInstalled(installed)
        val module = directory.resolve("urlpkg").resolve("__init__.py")
        // a reinstalled module would lose the marker
        module.appendText("\n# kept\n")
        val other = buildWheel(sources.resolve("wheels"), "otherpkg")

        // when
        val (exitCode, output) = runProcess(
            PythonDependencies.pipInstallCommand(
                python, directory.toString(), constraints.toString(),
                requirements = listOf("otherpkg @ ${other.toUri()}"),
                installed = listOf(installed)
            )
        )

        // then
        assert(exitCode == 0, output)
        assert(pythonOfDirectory("import otherpkg; print(otherpkg.ANSWER)") == "42")
        assert(module.readText().endsWith("\n# kept\n"))
    }

    /** pip keeps the credentials per host, while each URL in the requirements can have its own. */
    @Test
    fun `should install dependencies from URLs of the same host with different credentials with pip`() {
        // given
        val wheels = sources.resolve("wheels")
        val first = buildWheel(wheels, "authpkg")
        val second = buildWheel(wheels, "otherpkg")
        val server = ProcessBuilder(
            python, "-c", AUTHENTICATING_SERVER, wheels.toString(), "authpkg-=user1:secret1", "otherpkg-=user2:secret2"
        ).redirectErrorStream(true).start()
        try {
            val port = server.inputStream.bufferedReader().readLine()

            // when
            val (exitCode, output) = pipInstall(
                "authpkg @ http://user1:secret1@127.0.0.1:$port/${first.fileName}",
                "otherpkg @ http://user2:secret2@127.0.0.1:$port/${second.fileName}"
            )

            // then
            assert(exitCode == 0, output)
            assert(pythonOfDirectory("import authpkg, otherpkg; print(authpkg.ANSWER, otherpkg.ANSWER)") == "42 42")
        } finally {
            server.destroy()
            server.waitFor()
        }
    }

    /** pip and its subprocesses run with `-c`, which would import a `pip` package of the working directory. */
    @Test
    fun `should install with pip in working directory with pip package shadowing it`() {
        // given
        val workingDirectory = sources.resolve("working").createDirectories()
        val pip = workingDirectory.resolve("pip").createDirectory()
        pip.resolve("__init__.py").writeText("raise SystemExit('shadowing pip imported')\n")
        pip.resolve("__main__.py").writeText("raise SystemExit('shadowing pip run')\n")
        val wheel = buildWheel(sources.resolve("wheels"), "urlpkg")

        // when
        val (exitCode, output) = runProcess(
            PythonDependencies.pipInstallCommand(
                python, directory.toString(), constraints.toString(), listOf("urlpkg @ ${wheel.toUri()}")
            ),
            directory = workingDirectory
        )

        // then
        assert(exitCode == 0, output)
        assert(pythonOfDirectory("import urlpkg; print(urlpkg.ANSWER)") == "42")
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
    fun `should not redact URL without credentials with at sign in query`() {
        // when
        val redacted = PythonDependencies.redactCredentials("pkg @ https://host?token=a@b, other @ https://host#x@y")

        // then
        assert(redacted == "pkg @ https://host?token=a@b, other @ https://host#x@y")
    }

    @Test
    fun `should redact whole credentials with at sign in password`() {
        // when
        val redacted = PythonDependencies.redactCredentials("pkg @ https://user:p@ss@host/pkg.whl")

        // then
        assert(redacted == "pkg @ https://****@host/pkg.whl")
    }

    /** A credential of a requested URL must not be sent anywhere else, e.g. to an SSH URL of the same host. */
    @Test
    fun `should pin required distribution from URL to its URL of installation report`() {
        // when
        val requirement = pinnedRequirement(
            """
            {
              "metadata": {"name": "b", "version": "1.0"},
              "is_direct": true,
              "requested": false,
              "download_info": {"url": "ssh://git@github.com/org/b.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT"}}
            }
            """.trimIndent()
        )

        // then
        assert(requirement == "b @ git+ssh://git@github.com/org/b.git@$COMMIT")
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

        /**
         * Builds the wheel of the distribution named by its second argument, in version 1.0,
         * to the path of its first argument, with the `ANSWER` of its third argument,
         * requiring the dependencies of the following ones.
         */
        @Language("python")
        val BUILD_WHEEL = """
            import base64, hashlib, sys, zipfile

            path, name, answer, dependencies = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
            requires = "".join(f"Requires-Dist: {dependency}\n" for dependency in dependencies)
            files = {
                f"{name}/__init__.py": f"ANSWER = {answer}\n".encode(),
                f"{name}-1.0.dist-info/METADATA": f"Metadata-Version: 2.1\nName: {name}\nVersion: 1.0\n{requires}".encode(),
                f"{name}-1.0.dist-info/WHEEL": b"Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
            }
            record = "".join(
                f"{file},sha256={base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b'=').decode()},{len(data)}\n"
                for file, data in files.items()
            ) + f"{name}-1.0.dist-info/RECORD,,\n"
            with zipfile.ZipFile(path, "w") as wheel:
                for file, data in files.items():
                    wheel.writestr(file, data)
                wheel.writestr(f"{name}-1.0.dist-info/RECORD", record)
        """.trimIndent()

        /** Builds the source archive of the distribution named by its second argument, to the path of its first one. */
        @Language("python")
        val BUILD_SOURCE_ARCHIVE = """
            import io, sys, tarfile

            path, name = sys.argv[1:]
            files = {
                "pyproject.toml": (
                    '[build-system]\nrequires = ["setuptools"]\nbuild-backend = "setuptools.build_meta"\n\n'
                    f'[project]\nname = "{name}"\nversion = "1.0"\n'
                ),
                "PKG-INFO": f"Metadata-Version: 2.1\nName: {name}\nVersion: 1.0\n",
                f"{name}/__init__.py": "ANSWER = 42\n",
            }
            with tarfile.open(path, "w:gz") as archive:
                for file, text in files.items():
                    data = text.encode()
                    info = tarfile.TarInfo(f"{name}-1.0/{file}")
                    info.size = len(data)
                    archive.addfile(info, io.BytesIO(data))
        """.trimIndent()

        /**
         * Serves the directory of its first argument, only with basic authentication,
         * printing its port.
         * Each following argument is `<prefix>=<user>:<password>`,
         * the credentials of the files whose names start with the prefix, the first matching one,
         * or just `<user>:<password>`, the credentials of all the files.
         */
        @Language("python")
        val AUTHENTICATING_SERVER = """
            import base64, functools, http.server, sys

            directory = sys.argv[1]
            authorizations = [
                (prefix, "Basic " + base64.b64encode(credentials.encode()).decode())
                for prefix, _, credentials in (
                    argument.partition("=") if "=" in argument else ("", "", argument) for argument in sys.argv[2:]
                )
            ]

            class Handler(http.server.SimpleHTTPRequestHandler):
                def authorized(self):
                    name = self.path.lstrip("/")
                    authorization = next(value for prefix, value in authorizations if name.startswith(prefix))
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
