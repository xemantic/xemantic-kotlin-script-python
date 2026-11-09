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
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PythonEnvironmentTest {

    private val venvPython = Path(System.getenv("VIRTUAL_ENV"), "bin", "python").toString()

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should probe Python which writes to stderr`() {
        // given
        val directory = createTempDirectory("python-probe")
        val python = directory.resolve("python")
        python.writeText(
            """
            |#!/bin/sh
            |echo "Could not find platform libraries" >&2
            |exec "$venvPython" "${'$'}@"
            |""".trimMargin()
        )
        python.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))
        val expected = PythonEnvironment.probePython(venvPython)

        try {
            // when
            val probe = PythonEnvironment.probePython(python.toString())

            // then
            assert(probe == expected)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should report both stdout and stderr of failing probe`() {
        // given
        val directory = createTempDirectory("python-probe")
        val python = directory.resolve("python")
        python.writeText(
            """
            |#!/bin/sh
            |echo "shim failure on stdout"
            |echo "shim failure on stderr" >&2
            |exit 1
            |""".trimMargin()
        )
        python.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.probePython(python.toString())
            }

            // then
            val message = exception.message!!
            assert("shim failure on stdout" in message && "shim failure on stderr" in message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `should run process with stderr separate from stdout`() {
        // when
        val result = runProcess(
            listOf(venvPython, "-c", "import sys; print('out'); print('err', file=sys.stderr)"),
            mergeErrorStream = false
        )

        // then
        assert(result == ProcessResult(exitCode = 0, output = "out", error = "err"))
    }

    @Test
    fun `should run process with stderr merged into stdout`() {
        // when
        val result = runProcess(
            listOf(venvPython, "-c", "import sys; print('out', flush=True); print('err', file=sys.stderr)")
        )

        // then
        assert(result == ProcessResult(exitCode = 0, output = "out\nerr", error = ""))
    }

    @Test
    fun `should run process writing much to both stdout and stderr`() {
        // given
        val size = 1_000_000

        // when
        val result = runProcess(
            listOf(
                venvPython, "-c",
                "import sys; sys.stderr.write('e' * $size); sys.stderr.flush(); sys.stdout.write('o' * $size)"
            ),
            mergeErrorStream = false
        )

        // then
        assert(result.exitCode == 0)
        assert(result.output.length == size)
        assert(result.error.length == size)
    }

    @Test
    fun `should exchange non-ASCII text with process under non-UTF-8 locale`() {
        // given
        val nonUtf8Locale = mapOf("LC_ALL" to "C", "PYTHONUTF8" to "0", "PYTHONCOERCECLOCALE" to "0")

        // when
        val result = runProcess(
            listOf(venvPython, "-c", "import sys; print(sys.stdin.read().upper())"),
            input = "zażółć gęślą jaźń 日本",
            environment = nonUtf8Locale
        )

        // then
        assert(result == ProcessResult(exitCode = 0, output = "ZAŻÓŁĆ GĘŚLĄ JAŹŃ 日本", error = ""))
    }

    @Test
    fun `should run process writing much to stdout before consuming its stdin`() {
        // given
        val size = 10_000_000

        // when
        val (exitCode, output) = runProcess(
            listOf(venvPython, "-c", "import shutil, sys; shutil.copyfileobj(sys.stdin, sys.stdout)"),
            input = "x".repeat(size)
        )

        // then
        // counted, as power-assert would render the whole output on failure
        val length = output.length
        val notEchoed = output.count { it != 'x' }
        assert(exitCode == 0 && length == size && notEchoed == 0)
    }

    @Test
    fun `should return exit code and output of process ending before consuming its stdin`() {
        // when
        val result = runProcess(
            listOf(venvPython, "-c", "import sys; print('failure before reading stdin'); sys.exit(3)"),
            input = "x".repeat(10_000_000)
        )

        // then
        assert(result == ProcessResult(exitCode = 3, output = "failure before reading stdin", error = ""))
    }

    @Test
    fun `should destroy process when interrupted while waiting for it`() {
        // given
        val pidFile = createTempFile("python-pid", ".txt")
        val script = "import os, sys, time; open(sys.argv[1] + '.tmp', 'w').write(str(os.getpid())); " +
            "os.replace(sys.argv[1] + '.tmp', sys.argv[1]); time.sleep(600)"
        pidFile.deleteIfExists()
        var failure: Throwable? = null
        val runner = thread {
            try {
                runProcess(listOf(venvPython, "-c", script, pidFile.toString()), input = "x".repeat(10_000_000))
            } catch (e: Throwable) {
                failure = e
            }
        }

        try {
            // the runner checked, so that the test fails, instead of waiting forever, when it fails to start Python
            while (!pidFile.exists() && runner.isAlive) Thread.sleep(10)
            assert(pidFile.exists())
            val pid = pidFile.readText().toLong()

            // when
            runner.interrupt()
            runner.join()

            // then
            assert(failure is InterruptedException)
            assert(ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == false)
        } finally {
            pidFile.deleteIfExists()
        }
    }

}
