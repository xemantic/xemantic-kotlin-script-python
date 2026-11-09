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
import jep.JepException
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectory
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
    fun `should probe Python which writes to stderr, keeping it`() {
        // given
        val expected = PythonEnvironment.probePython(venvPython)
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

        try {
            // when
            val probe = PythonEnvironment.probePython(python.toString())

            // then
            // the warning is kept, e.g. for a later failure of the embedded Python
            assert(probe == expected.copy(error = "Could not find platform libraries"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should probe Python whose sitecustomize writes lines to stdout`() {
        // given
        val expected = PythonEnvironment.probePython(venvPython)
        val directory = createTempDirectory("python-probe")
        val python = pythonWithSitecustomize(
            directory,
            "print('sitecustomize noise')\nprint('more noise')\n"
        )

        try {
            // when
            val probe = PythonEnvironment.probePython(python)

            // then
            assert(probe == expected)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should probe Python whose sitecustomize writes to stdout without trailing newline`() {
        // given
        val expected = PythonEnvironment.probePython(venvPython)
        val directory = createTempDirectory("python-probe")
        val python = pythonWithSitecustomize(
            directory,
            "import sys\nsys.stdout.write('noise without newline')\n"
        )

        try {
            // when
            val probe = PythonEnvironment.probePython(python)

            // then
            assert(probe == expected)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should probe Python whose sitecustomize replaces stdout with wrapper without buffer`() {
        // given
        val expected = PythonEnvironment.probePython(venvPython)
        val directory = createTempDirectory("python-probe")
        val python = pythonWithSitecustomize(
            directory,
            """
            |import sys
            |class Wrapper:
            |    def __init__(self, stream): self.stream = stream
            |    def write(self, text): return self.stream.write(text)
            |    def flush(self): self.stream.flush()
            |sys.stdout = Wrapper(sys.stdout)
            |print('noise through wrapper', end='')
            |""".trimMargin()
        )

        try {
            // when
            val probe = PythonEnvironment.probePython(python)

            // then
            assert(probe == expected)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should probe Python whose paths are non-ASCII`() {
        // given
        val expected = PythonEnvironment.probePython(venvPython)
        val directory = createTempDirectory("python-probe")
        val python = pythonWithSitecustomize(directory, "import sys\nsys.prefix = '/zażółć 日本'\n")

        try {
            // when
            val probe = PythonEnvironment.probePython(python)

            // then
            assert(probe == expected.copy(prefix = "/zażółć 日本"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should fail clearly on probe of Python whose path is not valid in file system encoding of JVM`() {
        // given
        assumeTrue(filesystemCharset == Charsets.UTF_8, "the file system encoding of the JVM is not UTF-8")
        val directory = createTempDirectory("python-probe")
        // a path of another locale, e.g. /home/josé in ISO-8859-1, is surrogate-escaped by Python in UTF-8 mode
        val python = pythonWithSitecustomize(directory, "import os, sys\nsys.prefix = os.fsdecode(b'/jos\\xe9')\n")

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.probePython(python)
            }

            // then
            val message = exception.message!!
            assert(python in message && "UTF-8" in message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should fail clearly on probe of Python with empty executable`() {
        // given
        val directory = createTempDirectory("python-probe")
        // Python sets an empty sys.executable when it cannot tell the path of its executable
        val python = pythonWithSitecustomize(directory, "import sys\nsys.executable = ''\n")

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.probePython(python)
            }

            // then
            val message = exception.message!!
            assert("sys.executable is empty" in message && python in message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should report executable, stderr and stdout of probe output without values`() {
        // given
        val directory = createTempDirectory("python-probe")
        val python = directory.resolve("python")
        python.writeText(
            """
            |#!/bin/sh
            |echo "unexpected stdout"
            |echo "unexpected stderr" >&2
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
            assert(
                python.toString() in message && "unexpected stdout" in message && "unexpected stderr" in message
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `should parse probe output with output before and after values`() {
        // given
        val output = "noise\n${probePayload("/venv/bin/python", "cpython-313", "/venv", "/venv/jep")}\nmore noise"

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == PythonProbe("/venv/bin/python", "cpython-313", "/venv", "/venv/jep"))
    }

    @Test
    fun `should parse probe output with marker lines repeated before values`() {
        // given
        val output = "${PythonEnvironment.PROBE_BEGIN}\nfake\n${PythonEnvironment.PROBE_END}\n" +
            "${PythonEnvironment.PROBE_BEGIN}\n" +
            probePayload("/venv/bin/python", "cpython-313", "/venv", "/venv/jep")

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == PythonProbe("/venv/bin/python", "cpython-313", "/venv", "/venv/jep"))
    }

    @Test
    fun `should parse probe output with empty first value`() {
        // given
        val output = probePayload("", "cpython-313", "/venv", "/venv/jep")

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == PythonProbe("", "cpython-313", "/venv", "/venv/jep"))
    }

    @Test
    fun `should parse probe output with values containing spaces and non-ASCII`() {
        // given
        val output = probePayload("/My Zażółć/bin/python", "cpython-313", "/My Zażółć", "/My Zażółć/jep")

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == PythonProbe("/My Zażółć/bin/python", "cpython-313", "/My Zażółć", "/My Zażółć/jep"))
    }

    @Test
    fun `should not parse probe output without values`() {
        // when
        val probe = PythonEnvironment.parseProbeOutput("unexpected output")

        // then
        assert(probe == null)
    }

    @Test
    fun `should not parse probe output with begin marker but without end marker`() {
        // given
        val output = probePayload("/venv/bin/python", "cpython-313", "/venv", "/venv/jep")
            .removeSuffix("\n${PythonEnvironment.PROBE_END}")

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == null)
    }

    @Test
    fun `should not parse probe output without exactly four values`() {
        // given
        val tooFew = probePayload("/venv/bin/python", "cpython-313", "/venv")
        val tooMany = probePayload("/venv/bin/python", "cpython-313", "/venv", "/venv/jep", "extra")

        // when
        val probes = listOf(tooFew, tooMany).map { PythonEnvironment.parseProbeOutput(it) }

        // then
        assert(probes == listOf(null, null))
    }

    @Test
    fun `should not parse probe output with values which are not hex digits`() {
        // given
        val output = "${PythonEnvironment.PROBE_BEGIN}\n/venv/bin/python cpython-313 /venv /venv/jep\n" +
            PythonEnvironment.PROBE_END

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == null)
    }

    @Test
    fun `should not parse probe output with values which are signed hex numbers`() {
        // given
        // each pair of digits, like 2f and -1, is a number, but -1 is not a byte of hex digits
        val output = "${PythonEnvironment.PROBE_BEGIN}\n2f-1 2f 2f 2f\n${PythonEnvironment.PROBE_END}"

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == null)
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should not blame jep for Python which fails to run`() {
        // given
        val directory = createTempDirectory("python-probe")
        val python = directory.resolve("python")
        // like a pyenv shim of a Python version which is not installed
        python.writeText(
            """
            |#!/bin/sh
            |echo "pyenv: version '3.13.0' is not installed" >&2
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
            assert(
                message.startsWith("Cannot run Python $python") &&
                    "exit code 1" in message &&
                    "version '3.13.0' is not installed" in message &&
                    "jep" !in message
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should report missing jep package of Python`() {
        // given
        val directory = createTempDirectory("python-probe")
        // the venv Python, without its site-packages, where jep is installed
        val python = pythonWithSitecustomize(
            directory,
            "import sys\nsys.path = [path for path in sys.path if 'site-packages' not in path]\n"
        )

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.probePython(python)
            }

            // then
            val message = exception.message!!
            // the probe tells that jep is missing, instead of failing with a traceback
            assert(
                message.startsWith("Cannot find the jep package of $python") &&
                    "pip install jep" in message &&
                    "Traceback" !in message &&
                    !message.endsWith("\n")
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should name module shadowing jep package of Python`() {
        // given
        val directory = createTempDirectory("python-probe")
        val python = pythonWithSitecustomize(directory, "")
        // a module on PYTHONPATH, before the site-packages, where the jep package is installed
        val module = directory.resolve("site").resolve("jep.py")
        module.writeText("")

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.probePython(python)
            }

            // then
            val message = exception.message!!
            assert(message.startsWith("Cannot find the jep package of $python") && module.toString() in message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should limit stderr kept of successful probe`() {
        // given
        val directory = createTempDirectory("python-probe")
        val python = directory.resolve("python")
        python.writeText(
            """
            |#!/bin/sh
            |echo "Could not find platform libraries" >&2
            |head -c 1000000 /dev/zero | tr '\0' 'w' >&2
            |exec "$venvPython" "${'$'}@"
            |""".trimMargin()
        )
        python.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))

        try {
            // when
            val probe = PythonEnvironment.probePython(python.toString())

            // then
            // measured, as power-assert would render the whole error on failure
            val length = probe.error.length
            val startsWithWarning = probe.error.startsWith("Could not find platform libraries")
            val truncated = probe.error.endsWith("[truncated]")
            assert(length <= 9000 && startsWithWarning && truncated)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `should report stderr of probe on Error of embedded Python startup`() {
        // given
        val starter = EmbeddedPythonStarter("/venv/bin/python", "Could not find platform libraries <exec_prefix>")
        // like jep's MainInterpreter, rethrowing the failure of initializing CPython
        val error = Error(IllegalStateException("failed to get the Python codec of the filesystem encoding"))

        // when
        val exception = assertFailsWith<IllegalStateException> {
            starter.createInterpreter(create = { throw error }, check = {})
        }

        // then
        val message = exception.message!!
        assert(
            exception.cause === error &&
                message.startsWith("Cannot initialize the embedded Python /venv/bin/python") &&
                "failed to get the Python codec of the filesystem encoding" in message &&
                "Could not find platform libraries <exec_prefix>" in message
        )
    }

    @Test
    fun `should report Error of embedded Python which previously failed to start after another started`() {
        // given
        val starter = EmbeddedPythonStarter("/venv/bin/python", "a warning")
        starter.createInterpreter(create = { FakeInterpreter() }, check = {}).close()
        val error = Error("The main Python interpreter previously failed to initialize.")

        // when
        val exception = assertFailsWith<IllegalStateException> {
            starter.createInterpreter(create = { throw error }, check = {})
        }

        // then
        assert(exception.cause === error && "a warning" in exception.message!!)
    }

    @Test
    fun `should report failure of first embedded Python startup the same way without stderr of probe`() {
        // given
        val starter = EmbeddedPythonStarter("/venv/bin/python", "")
        val cause = JepException("Error importing shared module")

        // when
        val exception = assertFailsWith<IllegalStateException> {
            starter.createInterpreter(create = { throw cause }, check = {})
        }

        // then
        val message = exception.message!!
        assert(
            exception.cause === cause &&
                message == "Cannot initialize the embedded Python /venv/bin/python: Error importing shared module"
        )
    }

    @Test
    fun `should not report stderr of probe on failure of interpreter after embedded Python started`() {
        // given
        val starter = EmbeddedPythonStarter("/venv/bin/python", "a warning")
        starter.createInterpreter(create = { FakeInterpreter() }, check = {}).close()
        val cause = JepException("unrelated failure")

        // when
        val exception = assertFailsWith<JepException> {
            starter.createInterpreter(create = { throw cause }, check = {})
        }

        // then
        assert(exception === cause)
    }

    @Test
    fun `should close interpreter failing its check`() {
        // given
        val starter = EmbeddedPythonStarter("/venv/bin/python", "")
        val interpreter = FakeInterpreter()

        // when
        val exception = assertFailsWith<IllegalStateException> {
            starter.createInterpreter(create = { interpreter }, check = { error("not in UTF-8 mode") })
        }

        // then
        assert(exception.message == "not in UTF-8 mode" && interpreter.closed)
    }

    private class FakeInterpreter : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    /** The output of [PythonEnvironment.probeCommand] writing the [values], trimmed as by [runProcess]. */
    private fun probePayload(vararg values: String): String {
        val hexValues = values.joinToString(" ") { it.toByteArray(filesystemCharset).toHexString() }
        return "${PythonEnvironment.PROBE_BEGIN}\n$hexValues\n${PythonEnvironment.PROBE_END}"
    }

    /**
     * A shim of the venv Python in the [directory], with a `sitecustomize` module of the [code] on its `PYTHONPATH`,
     * which Python imports at startup, before the code it runs.
     */
    private fun pythonWithSitecustomize(directory: Path, code: String): String {
        val sitePath = directory.resolve("site").createDirectory()
        sitePath.resolve("sitecustomize.py").writeText(code)
        val python = directory.resolve("python")
        python.writeText(
            """
            |#!/bin/sh
            |PYTHONPATH="$sitePath" exec "$venvPython" "${'$'}@"
            |""".trimMargin()
        )
        python.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))
        return python.toString()
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

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should probe Python in working directory with jep package shadowing it`() {
        // given
        val expected = runProcess(PythonEnvironment.probeCommand(venvPython), mergeErrorStream = false)
        val workingDirectory = createTempDirectory("python-shadowing")
        workingDirectory.resolve("jep").createDirectory().resolve("__init__.py").writeText("")

        try {
            // when
            val probe = runProcess(
                PythonEnvironment.probeCommand(venvPython),
                mergeErrorStream = false,
                directory = workingDirectory
            )

            // then
            assert(probe == expected)
        } finally {
            workingDirectory.deleteRecursively()
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
    fun `should destroy process and rethrow when reading its output fails`() {
        // given
        val pidFile = createTempFile("python-pid", ".txt")
        // the output is closed after the failure, so the process outlives it, to keep running, like a stuck one
        val script = "import os, sys, time; open(sys.argv[1], 'w').write(str(os.getpid()))\n" +
            "try:\n" +
            "    while True: sys.stdout.write('x' * 65536)\n" +
            "except BrokenPipeError:\n" +
            "    time.sleep(600)"

        try {
            // when
            val exception = assertFailsWith<IOException> {
                runProcess(
                    listOf(venvPython, "-c", script, pidFile.toString()),
                    readOutput = { reader ->
                        reader.read(CharArray(1024))
                        throw IOException("reading output failed")
                    }
                )
            }

            // then
            assert(exception.message == "reading output failed")
            val pid = pidFile.readText().toLong()
            assert(ProcessHandle.of(pid).map { it.isAlive }.orElse(false) == false)
        } finally {
            pidFile.deleteIfExists()
        }
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
