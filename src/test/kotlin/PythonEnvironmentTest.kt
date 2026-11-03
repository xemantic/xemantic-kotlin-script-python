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
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test

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

}
