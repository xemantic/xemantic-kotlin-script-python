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
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * The embedded CPython failing to start, after a successful probe of its Python executable,
 * in a JVM of its own, as the embedded CPython can be started only once per JVM.
 */
class PythonStartupFailureTest {

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should report stderr of probe when embedded Python fails to start`() {
        // given
        val directory = createTempDirectory("python-startup")
        // an encodings package, imported by CPython at startup, which warns, like Python of a wrong PYTHONHOME,
        // and fails only in the embedded CPython, which, unlike the probe, is not run with -c,
        // while in the probe it hands over to the real encodings package
        val encodings = directory.resolve("encodings").createDirectories()
        encodings.resolve("__init__.py").writeText(
            """
            |import importlib, os, sys
            |os.write(2, b'fake warning of a broken Python home\n')
            |if '-c' not in sys.orig_argv:
            |    raise LookupError('fake failure of encodings in embedded Python')
            |sys.path[:] = [path for path in sys.path if path != os.fsdecode(bytes.fromhex('${
                directory.toString().toByteArray(filesystemCharset).toHexString()
            }'))]
            |del sys.modules['encodings']
            |sys.modules['encodings'] = importlib.import_module('encodings')
            |""".trimMargin()
        )

        try {
            // when
            val (exitCode, output) = runInJvm(
                EmbeddedPythonMain::class.java.name,
                input = "print('started')",
                environment = mapOf("PYTHONPATH" to directory.toString())
            )

            // then
            assert(exitCode != 0)
            assert(
                "IllegalStateException: Cannot initialize the embedded Python" in output &&
                    "fake warning of a broken Python home" in output
            )
        } finally {
            directory.deleteRecursively()
        }
    }

}
