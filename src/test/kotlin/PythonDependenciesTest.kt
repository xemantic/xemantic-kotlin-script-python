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
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isWritable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readLines
import kotlin.io.path.setPosixFilePermissions
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

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should run uv with constraint option known to every uv version`() {
        // given
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the fake uv is a shell script")
        PythonEnvironment.initialize()
        val uvDirectory = createTempDirectory("python-fake-uv")
        try {
            val record = uvDirectory.resolve("record.txt")
            // records its arguments, followed by the content of the file of the --constraint option
            val uv = uvDirectory.resolve("uv")
            uv.writeText(
                """
                |#!/bin/sh
                |printf '%s\n' "$@" > '$record'
                |while [ $# -gt 0 ]; do
                |  if [ "$1" = --constraint ]; then cat "$2" >> '$record'; fi
                |  shift
                |done
                |""".trimMargin()
            )
            uv.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))

            // when
            PythonDependencies.installMissing(
                listOf("fake-dependency"),
                listOf("kept-dependency==1.0"),
                uvDirectory.resolve("dependencies").toString(),
                uv = uv.toString()
            )

            // then
            val lines = record.readLines()
            // --constraints, its alias since uv 0.5.4, is rejected by older uv
            assert("--constraints" !in lines)
            assert("--constraint" in lines && lines.last() == "kept-dependency==1.0")
        } finally {
            uvDirectory.deleteRecursively()
        }
    }

    @Test
    fun `should install with uv on PATH satisfying constraint`() {
        // when
        val (dependencies, failure) = installFakeDependencyWithUv(constraint = "fake-dependency==1.0")

        // then
        assert(failure == null && "fake_dependency.py" in dependencies)
    }

    @Test
    fun `should fail to install with uv on PATH violating constraint`() {
        // when
        val (dependencies, failure) = installFakeDependencyWithUv(constraint = "fake-dependency==2.0")

        // then
        // the constraint is applied, not only accepted as an option
        assert(failure is PythonDependencyException && "unexpected argument" !in failure.message!!)
        assert("fake_dependency.py" !in dependencies)
    }

    /**
     * Installs the `fake-dependency` 1.0, of a local wheel, so that uv needs no network,
     * with the real uv on the `PATH`, keeping the [constraint], or skips the test without uv.
     *
     * @return the files of the dependency directory, and the failure of the installation, if any.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun installFakeDependencyWithUv(constraint: String): Pair<List<String>, Exception?> {
        val uv = PythonDependencies.findOnPath("uv")
        assumeTrue(uv != null, "uv is not on the PATH")
        PythonEnvironment.initialize()
        val directory = createTempDirectory("python-uv-install")
        try {
            val wheel = directory.resolve("fake_dependency-1.0-py3-none-any.whl")
            writeFakeWheel(wheel)
            val dependencies = directory.resolve("dependencies")
            val failure = try {
                PythonDependencies.installMissing(
                    listOf("fake-dependency @ ${wheel.toUri()}"),
                    listOf(constraint),
                    dependencies.toString(),
                    uv = uv
                )
                null
            } catch (e: PythonDependencyException) {
                e
            }
            val files = if (dependencies.exists()) dependencies.listDirectoryEntries().map { it.name } else emptyList()
            return files to failure
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Writes the [wheel] of the `fake-dependency` distribution 1.0, of the `fake_dependency` module. */
    private fun writeFakeWheel(wheel: Path) {
        ZipOutputStream(wheel.outputStream()).use { zip ->
            mapOf(
                "fake_dependency.py" to "VALUE = 'installed'\n",
                "fake_dependency-1.0.dist-info/METADATA" to
                    "Metadata-Version: 2.1\nName: fake-dependency\nVersion: 1.0\n",
                "fake_dependency-1.0.dist-info/WHEEL" to
                    "Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
                "fake_dependency-1.0.dist-info/RECORD" to ""
            ).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private companion object {

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
