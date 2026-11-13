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
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The code of a `sitecustomize` module removing the `site-packages`, where jep is installed, from `sys.path`. */
private const val WITHOUT_SITE_PACKAGES =
    "import sys\nsys.path = [path for path in sys.path if 'site-packages' not in path]\n"

class PythonEnvironmentTest {

    private val venvPython = Path(System.getenv("VIRTUAL_ENV"), "bin", "python").toString()

    /** The version of `org.ninia:jep` in the version catalog of the project, the working directory of the tests. */
    private val catalogJepVersion = Regex("""(?m)^jep = "(.+)"$""")
        .find(Path("gradle", "libs.versions.toml").readText())!!
        .groupValues[1]

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
        val output = "noise\n${probePayload("/venv/bin/python", "cpython-313", "/venv", "/venv/jep", "", "", "")}\nmore noise"

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
            probePayload("/venv/bin/python", "cpython-313", "/venv", "/venv/jep", "", "", "")

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == PythonProbe("/venv/bin/python", "cpython-313", "/venv", "/venv/jep"))
    }

    @Test
    fun `should parse probe output with empty first value`() {
        // given
        val output = probePayload("", "cpython-313", "/venv", "/venv/jep", "", "", "")

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == PythonProbe("", "cpython-313", "/venv", "/venv/jep"))
    }

    @Test
    fun `should parse probe output with values containing spaces and non-ASCII`() {
        // given
        val output = probePayload("/My Zażółć/bin/python", "cpython-313", "/My Zażółć", "/My Zażółć/jep", "", "", "")

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == PythonProbe("/My Zażółć/bin/python", "cpython-313", "/My Zażółć", "/My Zażółć/jep"))
    }

    @Test
    fun `should parse probe output with library directory and soname of libpython`() {
        // given
        val output = probePayload(
            "/venv/bin/python", "cpython-313", "/venv", "/venv/jep", "1",
            "/opt/python/lib", "libpython3.13.so.1.0"
        )

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(
            probe == PythonProbe(
                "/venv/bin/python", "cpython-313", "/venv", "/venv/jep",
                externallyManaged = true,
                libraryDirectory = "/opt/python/lib",
                librarySoname = "libpython3.13.so.1.0"
            )
        )
    }

    @Test
    fun `should probe shared libpython of Python built with it`() {
        // given
        val shared = runProcess(
            pythonCommand(venvPython, "import sysconfig\nprint(sysconfig.get_config_var('Py_ENABLE_SHARED'))"),
            mergeErrorStream = false
        ).output
        assumeTrue(shared == "1", "the Python is not built with a shared libpython")

        // when
        val probe = PythonEnvironment.probePython(venvPython)

        // then
        val library = pythonLibrary(probe.libraryDirectory, probe.librarySoname)
        assert(library != null && library.isRegularFile())
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
        val output = probePayload("/venv/bin/python", "cpython-313", "/venv", "/venv/jep", "", "", "")
            .removeSuffix("\n${PythonEnvironment.PROBE_END}")

        // when
        val probe = PythonEnvironment.parseProbeOutput(output)

        // then
        assert(probe == null)
    }

    @Test
    fun `should not parse probe output without exactly seven values`() {
        // given
        val tooFew = probePayload("/venv/bin/python", "cpython-313", "/venv", "/venv/jep", "", "")
        val tooMany = probePayload("/venv/bin/python", "cpython-313", "/venv", "/venv/jep", "", "", "", "extra")

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
        val python = pythonWithSitecustomize(directory, WITHOUT_SITE_PACKAGES)

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.probePython(python)
            }

            // then
            val message = exception.message!!
            // the probe tells that jep is missing, instead of failing with a traceback,
            // and how to install it, pinned to the version of the org.ninia:jep library,
            // into this virtual environment, which might hold the other packages of the user
            assert(
                message.startsWith("Cannot find the jep package of $python") &&
                    "$python -m pip install jep==$catalogJepVersion" in message &&
                    "-m venv" !in message &&
                    "Traceback" !in message &&
                    !message.endsWith("\n")
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should report missing jep package of externally managed Python`() {
        // given
        val directory = createTempDirectory("python-probe")
        val python = pythonWithSitecustomize(directory, WITHOUT_SITE_PACKAGES + externallyManaged(directory))

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.probePython(python)
            }

            // then
            val message = exception.message!!
            // pip refuses to install into it, only into a virtual environment
            assert(
                message.startsWith("Cannot find the jep package of $python") &&
                    "$python -m venv" in message &&
                    "-m pip install jep==$catalogJepVersion" in message &&
                    "$python -m pip install" !in message &&
                    "VIRTUAL_ENV" in message &&
                    "PythonEnvironment.pythonExecutable" in message
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should probe whether Python is externally managed`() {
        // given
        val directory = createTempDirectory("python-probe")
        val managedPython = pythonWithSitecustomize(directory, externallyManaged(directory))

        try {
            // when
            val venvProbe = PythonEnvironment.probePython(venvPython)
            val managedProbe = PythonEnvironment.probePython(managedPython)

            // then
            // a virtual environment is never externally managed, even if its base Python is
            assert(!venvProbe.externallyManaged && managedProbe.externallyManaged)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `should read version of org ninia jep library matching version catalog`() {
        // when
        val version = PythonEnvironment.jepVersion

        // then
        assert(version == catalogJepVersion)
    }

    @Test
    fun `should not read version of jep library from malformed properties`() {
        // given
        val properties = "version=\\u12".byteInputStream()

        // when
        val version = readJepVersion(properties)

        // then
        assert(version == null)
    }

    @Test
    fun `should advise installing jep with pip of Python which is not externally managed`() {
        // when
        val advice = jepInstallAdvice(
            python = "/venv/bin/python",
            externallyManaged = false,
            jepVersion = "4.3.2",
            javaHome = null,
            userHome = "/home/jane",
            windows = false
        )

        // then
        assert(
            "  /venv/bin/python -m pip install jep==4.3.2\n" in advice &&
                "-m venv" !in advice &&
                "jep is built from its source" in advice &&
                "apt install python3-dev" in advice &&
                "python3-venv" !in advice &&
                "javac on the PATH" in advice &&
                // e.g. a virtual environment of uv, which has no pip
                "uv pip install --python /venv/bin/python jep==4.3.2" in advice
        )
    }

    @Test
    fun `should advise installing jep into virtual environment of externally managed Python`() {
        // when
        val advice = jepInstallAdvice(
            python = "/usr/bin/python3",
            externallyManaged = true,
            jepVersion = "4.3.2",
            javaHome = null,
            userHome = "/home/jane",
            windows = false
        )

        // then
        // the absolute path, also for VIRTUAL_ENV, which is not expanded outside a shell, e.g. in an IDE
        assert(
            "  /usr/bin/python3 -m venv /home/jane/.venvs/jep\n" in advice &&
                "  /home/jane/.venvs/jep/bin/python -m pip install jep==4.3.2\n" in advice &&
                "VIRTUAL_ENV to /home/jane/.venvs/jep" in advice &&
                "PythonEnvironment.pythonExecutable" in advice &&
                "~" !in advice &&
                // the venv module, without which the first step fails, is a separate package on Debian/Ubuntu
                "apt install python3-dev python3-venv" in advice
        )
    }

    @Test
    fun `should advise installing jep into virtual environment on Windows quoting paths with spaces`() {
        // when
        val advice = jepInstallAdvice(
            python = "C:\\Program Files\\Python313\\python.exe",
            externallyManaged = true,
            jepVersion = "4.3.2",
            javaHome = null,
            userHome = "C:\\Users\\Jane Doe",
            windows = true
        )

        // then
        assert(
            "  \"C:\\Program Files\\Python313\\python.exe\" -m venv \"C:\\Users\\Jane Doe\\.venvs\\jep\"\n" in advice &&
                "  \"C:\\Users\\Jane Doe\\.venvs\\jep\\Scripts\\python.exe\" -m pip install jep==4.3.2\n" in advice &&
                "%" !in advice &&
                "JAVA_HOME (not set)" in advice &&
                // PowerShell would take a quoted command for a string
                "In PowerShell, prefix a quoted command with &" in advice
        )
    }

    @Test
    fun `should advise installing jep without version when version of library is unknown`() {
        // when
        val advice = jepInstallAdvice(
            python = "python3",
            externallyManaged = false,
            jepVersion = null,
            javaHome = null,
            userHome = "/home/jane",
            windows = false
        )

        // then
        assert(
            "  python3 -m pip install jep\n" in advice &&
                "the version of the org.ninia:jep library" in advice
        )
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should advise reinstalling jep when its native library is missing`() {
        // given
        val jepDirectory = createTempDirectory("jep")

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.jepLibrary(jepDirectory, "/venv/bin/python", externallyManaged = false)
            }

            // then
            // a plain pip install would tell that the requirement is already satisfied, and change nothing
            val message = exception.message!!
            assert(
                message.startsWith("No native Jep library in $jepDirectory") &&
                    "/venv/bin/python -m pip install --force-reinstall --no-cache-dir jep==$catalogJepVersion" in message
            )
        } finally {
            jepDirectory.deleteRecursively()
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
            assert(length <= PythonEnvironment.PROBE_ERROR_LIMIT && startsWithWarning && truncated)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should limit stderr kept of successful probe without splitting surrogate pair`() {
        // given
        val directory = createTempDirectory("python-probe")
        val python = directory.resolve("python")
        // an odd character before the surrogate pairs of 😀, so that the even limit falls within one of them
        python.writeText(
            """
            |#!/bin/sh
            |"$venvPython" -c "import sys; sys.stderr.write('x' + '\U0001F600' * 100000)"
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
            val wellFormed = probe.error.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == probe.error
            val truncated = probe.error.endsWith("[truncated]")
            assert(wellFormed)
            assert(length <= PythonEnvironment.PROBE_ERROR_LIMIT && truncated)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should report probe output without values and without output in one line`() {
        // given
        val directory = createTempDirectory("python-probe")
        val python = directory.resolve("python")
        python.writeText("#!/bin/sh\n")
        python.setPosixFilePermissions(PosixFilePermissions.fromString("rwx------"))

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.probePython(python.toString())
            }

            // then
            val message = exception.message!!
            assert(python.toString() in message && "\n" !in message)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should report jep package directory which cannot be listed`() {
        // given
        val directory = createTempDirectory("python-probe")
        val jepDirectory = directory.resolve("missing-jep")

        try {
            // when
            val exception = assertFailsWith<IllegalStateException> {
                PythonEnvironment.jepLibrary(jepDirectory, "/venv/bin/python", externallyManaged = false)
            }

            // then
            assert(jepDirectory.toString() in exception.message!!)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should find shared libpython in its library directory on Linux`() {
        // given
        val directory = createTempDirectory("python-library")
        val library = directory.resolve("libpython3.13.so.1.0").createFile()

        try {
            // when
            val found = pythonLibrary(directory.toString(), "libpython3.13.so.1.0")

            // then
            assert(found == library)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should not find static libpython`() {
        // given
        // a Python built without --enable-shared, e.g. by pyenv by default, has its libpython linked statically
        val directory = createTempDirectory("python-library")
        directory.resolve("libpython3.13.a").createFile()

        try {
            // when
            val found = pythonLibrary(directory.toString(), "libpython3.13.a")

            // then
            assert(found == null)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should not find libpython missing from its library directory`() {
        // given
        val directory = createTempDirectory("python-library")

        try {
            // when
            val missing = pythonLibrary(directory.toString(), "libpython3.13.so.1.0")
            val unknownSoname = pythonLibrary(directory.toString(), "")
            val unknownDirectory = pythonLibrary("", "libpython3.13.so.1.0")

            // then
            assert(missing == null && unknownSoname == null && unknownDirectory == null)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should tell that dynamic loader finds libpython in directory of LD_LIBRARY_PATH`() {
        // given
        val directory = createTempDirectory("python-library")
        val other = directory.resolve("other").createDirectory()
        val library = directory.resolve("libxemantic-test.so.1").createFile()

        try {
            // when
            val found = loaderFinds(library, libraryPath = "$other:$directory", cache = { null })

            // then
            assert(found)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should tell that dynamic loader finds libpython listed in its cache`() {
        // given
        val directory = createTempDirectory("python-library")
        val library = directory.resolve("libxemantic-test.so.1").createFile()
        val cache = "ld.so-1.7.0\u0000libxemantic-test.so.1\u0000$library\u0000/usr/lib/libc.so.6\u0000"
            .toByteArray(filesystemCharset)

        try {
            // when
            val found = loaderFinds(library, libraryPath = null, cache = { cache })

            // then
            assert(found)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should not read cache of dynamic loader when libpython is found in directory of LD_LIBRARY_PATH`() {
        // given
        val directory = createTempDirectory("python-library")
        val library = directory.resolve("libxemantic-test.so.1").createFile()

        try {
            // when
            val found = loaderFinds(library, libraryPath = "$directory", cache = { error("cache read") })

            // then
            assert(found)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `should keep failure of loading libpython denied by JVM`() {
        // given
        // like System.load with --illegal-native-access=deny
        val denied = IllegalCallerException("Illegal native access from: unnamed module")

        // when
        val loading = withSystemProperty(NO_FOREIGN_LINKER_PROPERTY, "true") {
            loadPythonLibrary(Path("/opt/python/lib/libpython3.13.so.1.0"), load = { throw denied })
        }

        // then
        assert(loading.failure === denied && !loading.localSymbols)
    }

    @Test
    fun `should use foreign linker unless disabled by system property set to true`() {
        // when
        val usage = listOf(null, "false", "true", "TRUE").map { value ->
            withSystemProperty(NO_FOREIGN_LINKER_PROPERTY, value) { foreignLinkerEnabled() }
        }

        // then
        assert(usage == listOf(true, true, false, false))
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should tell that dynamic loader does not find libpython`() {
        // given
        val directory = createTempDirectory("python-library")
        val library = directory.resolve("libxemantic-test.so.1").createFile()
        val cache = "ld.so-1.7.0\u0000libc.so.6\u0000/usr/lib/libc.so.6\u0000".toByteArray(filesystemCharset)

        try {
            // when
            val found = loaderFinds(library, libraryPath = "", cache = { cache })

            // then
            assert(!found)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should tell that dynamic loader finds development symlink of libpython by its name`() {
        // given
        val directory = createTempDirectory("python-library")
        val library = directory.resolve("libxemantic-test.so.1").createFile()
        directory.resolve("libxemantic-test.so").createSymbolicLinkPointingTo(library.fileName)
        val sonameOnly = directory.resolve("soname-only").createDirectory()
        sonameOnly.resolve("libxemantic-test.so.1").createSymbolicLinkPointingTo(library)

        try {
            // when
            val found = loaderFinds(library, "libxemantic-test.so", libraryPath = "$directory", cache = { null })
            val missing = loaderFinds(library, "libxemantic-test.so", libraryPath = "$sonameOnly", cache = { null })

            // then
            assert(found && !missing)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `should tell that Jep loads libpython globally only when loader finds its soname and development symlink`() {
        // given
        val library = Path("/usr/lib/libpython3.13.so.1.0")
        fun loadsGlobally(foundNames: Set<String>, developmentName: String = "libpython3.13.so") =
            jepLoadsGlobally(
                library,
                jepDynamic = { ElfDynamic(listOf("libpython3.13.so.1.0"), searchPath = false) },
                developmentName = { developmentName },
                finds = { name -> name in foundNames }
            )

        // when
        val both = loadsGlobally(setOf("libpython3.13.so.1.0", "libpython3.13.so"))
        // e.g. of a Python of the system without its development package
        val sonameOnly = loadsGlobally(setOf("libpython3.13.so.1.0"))
        val developmentSymlinkOnly = loadsGlobally(setOf("libpython3.13.so"))
        val unknownDevelopmentName = loadsGlobally(setOf("libpython3.13.so.1.0", ""), developmentName = "")

        // then
        assert(both && !sonameOnly && !developmentSymlinkOnly && !unknownDevelopmentName)
    }

    @Test
    fun `should advise development package providing development symlink of libpython to make its symbols global`() {
        // given
        val library = Path("/usr/lib/x86_64-linux-gnu/libpython3.12.so.1.0")

        // when
        val advice = pythonLibraryGlobalAdvice(library, "libpython3.12.so")

        // then
        assert(
            advice == "put /usr/lib/x86_64-linux-gnu on LD_LIBRARY_PATH before the JVM starts, " +
                "unless it is a directory the dynamic loader searches anyway, " +
                "and if libpython3.12.so is missing there, install the development package of this Python " +
                "which provides it, e.g. libpython3.X-dev on Debian or Ubuntu"
        )
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should tell that dynamic loader finds another libpython of same soname first`() {
        // given
        // e.g. of another installation of the same Python version
        val directory = createTempDirectory("python-library")
        val other = directory.resolve("other").createDirectory()
        other.resolve("libxemantic-test.so.1").createFile()
        val library = directory.resolve("libxemantic-test.so.1").createFile()

        try {
            // when
            val found = loaderFinds(library, libraryPath = "$other:$directory", cache = { null })

            // then
            assert(!found)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `should advise library directory of libpython when native library of Jep cannot load it`() {
        // given
        val starter = EmbeddedPythonStarter(
            "/venv/bin/python", "", pythonLibrary = Path("/opt/python/lib/libpython3.13.so.1.0")
        )
        val cause = UnsatisfiedLinkError(
            "/venv/lib/python3.13/site-packages/jep/libjep.so: " +
                "libpython3.13.so.1.0: cannot open shared object file: No such file or directory"
        )

        // when
        val exception = assertFailsWith<IllegalStateException> {
            starter.createInterpreter(create = { throw cause }, check = {})
        }

        // then
        val message = exception.message!!
        assert(
            exception.cause === cause &&
                message.startsWith("Cannot initialize the embedded Python /venv/bin/python: ${cause.message}\n") &&
                "/opt/python/lib" in message &&
                "LD_LIBRARY_PATH" in message
        )
    }

    @Test
    fun `should tell failure to load libpython instead of advising its directory`() {
        // given
        // LD_LIBRARY_PATH would not help, as the loader would fail to load the library the same way
        val preloadFailure = UnsatisfiedLinkError("/opt/python/lib/libpython3.13.so.1.0: wrong ELF class: ELFCLASS32")
        val starter = EmbeddedPythonStarter(
            "/venv/bin/python", "",
            pythonLibrary = Path("/opt/python/lib/libpython3.13.so.1.0"),
            libraryPreloadFailure = preloadFailure
        )
        val cause = UnsatisfiedLinkError(
            "/venv/lib/python3.13/site-packages/jep/libjep.so: " +
                "libpython3.13.so.1.0: cannot open shared object file: No such file or directory"
        )

        // when
        val exception = assertFailsWith<IllegalStateException> {
            starter.createInterpreter(create = { throw cause }, check = {})
        }

        // then
        val message = exception.message!!
        assert(
            exception.cause === cause &&
                exception.suppressed.toList() == listOf(preloadFailure) &&
                message == "Cannot initialize the embedded Python /venv/bin/python: ${cause.message}\n" +
                "Cannot load the libpython of this Python: ${preloadFailure.message}"
        )
    }

    @Test
    fun `should advise library directory of libpython when initialization of embedded Python is retried`() {
        // given
        val starter = EmbeddedPythonStarter(
            "/venv/bin/python", "", pythonLibrary = Path("/opt/python/lib/libpython3.13.so.1.0")
        )
        val first = UnsatisfiedLinkError(
            "/venv/lib/python3.13/site-packages/jep/libjep.so: " +
                "libpython3.13.so.1.0: cannot open shared object file: No such file or directory"
        )
        // like jep's MainInterpreter, rethrowing the failure of the first attempt
        val cause = Error("The main Python interpreter previously failed to initialize.", first)

        // when
        val exception = assertFailsWith<IllegalStateException> {
            starter.createInterpreter(create = { throw cause }, check = {})
        }

        // then
        val message = exception.message!!
        assert(exception.cause === cause && "/opt/python/lib" in message && "LD_LIBRARY_PATH" in message)
    }

    @Test
    fun `should not advise library directory of libpython on other failure of embedded Python startup`() {
        // given
        val starter = EmbeddedPythonStarter(
            "/venv/bin/python", "", pythonLibrary = Path("/opt/python/lib/libpython3.13.so.1.0")
        )
        val wrongClass = UnsatisfiedLinkError("/venv/lib/python3.13/site-packages/jep/libjep.so: wrong ELF class: ELFCLASS32")
        // of libpython found, but of another Python
        val undefinedSymbol = UnsatisfiedLinkError(
            "/venv/lib/python3.13/site-packages/jep/libjep.so: undefined symbol: PyFoo, version libpython3.13.so.1.0"
        )
        // of jep built for another Python
        val anotherVersion = UnsatisfiedLinkError(
            "/venv/lib/python3.13/site-packages/jep/libjep.so: " +
                "libpython3.12.so.1.0: cannot open shared object file: No such file or directory"
        )

        // when
        val messages = listOf(wrongClass, undefinedSymbol, anotherVersion).map { cause ->
            assertFailsWith<IllegalStateException> {
                starter.createInterpreter(create = { throw cause }, check = {})
            }.message
        }

        // then
        assert(
            messages == listOf(
                "Cannot initialize the embedded Python /venv/bin/python: ${wrongClass.message}",
                "Cannot initialize the embedded Python /venv/bin/python: ${undefinedSymbol.message}",
                "Cannot initialize the embedded Python /venv/bin/python: ${anotherVersion.message}"
            )
        )
    }

    @Test
    fun `should not advise library directory of Python without shared libpython`() {
        // given
        val starter = EmbeddedPythonStarter("/venv/bin/python", "")
        val cause = UnsatisfiedLinkError(
            "/venv/lib/python3.13/site-packages/jep/libjep.so: " +
                "libpython3.13.so.1.0: cannot open shared object file: No such file or directory"
        )

        // when
        val exception = assertFailsWith<IllegalStateException> {
            starter.createInterpreter(create = { throw cause }, check = {})
        }

        // then
        assert(exception.message == "Cannot initialize the embedded Python /venv/bin/python: ${cause.message}")
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
        // the message of the Error is the class and message of its cause, which the headline does not repeat
        assert(
            exception.cause === error &&
                message.startsWith(
                    "Cannot initialize the embedded Python /venv/bin/python: " +
                        "failed to get the Python codec of the filesystem encoding\n"
                ) &&
                "Could not find platform libraries <exec_prefix>" in message
        )
    }

    @Test
    fun `should not report stderr of probe on Error after embedded Python started`() {
        // given
        val starter = EmbeddedPythonStarter("/venv/bin/python", "a warning")
        starter.createInterpreter(create = { FakeInterpreter() }, check = {}).close()
        // the embedded CPython starts once per JVM, so an Error afterward is not a failure of its startup
        val error = NoClassDefFoundError("some/Class")

        // when
        val exception = assertFailsWith<NoClassDefFoundError> {
            starter.createInterpreter(create = { throw error }, check = {})
        }

        // then
        assert(exception === error)
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
    private fun <T> withSystemProperty(name: String, value: String?, block: () -> T): T {
        val previous = System.getProperty(name)
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        try {
            return block()
        } finally {
            if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
        }
    }

    private fun probePayload(vararg values: String): String {
        val hexValues = values.joinToString(" ") { it.toByteArray(filesystemCharset).toHexString() }
        return "${PythonEnvironment.PROBE_BEGIN}\n$hexValues\n${PythonEnvironment.PROBE_END}"
    }

    /**
     * The code of a `sitecustomize` module making the venv Python look like an externally managed base Python,
     * with an `EXTERNALLY-MANAGED` file in its standard library directory, created in the [directory].
     */
    private fun externallyManaged(directory: Path): String {
        val stdlib = directory.resolve("stdlib").createDirectory()
        stdlib.resolve("EXTERNALLY-MANAGED").writeText("[externally-managed]\n")
        return """
            |import sys, sysconfig
            |sys.base_prefix = sys.prefix
            |get_path = sysconfig.get_path
            |sysconfig.get_path = lambda name, *args, **kwargs: (
            |    ${pythonPath(stdlib.toString())} if name == 'stdlib' else get_path(name, *args, **kwargs)
            |)
            |""".trimMargin()
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
