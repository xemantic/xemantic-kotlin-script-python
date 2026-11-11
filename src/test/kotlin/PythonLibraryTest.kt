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
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

/**
 * The embedded CPython loading the shared `libpython` of its Python without `LD_LIBRARY_PATH`,
 * in a JVM of its own, as the environment of the dynamic loader is read when a process starts.
 *
 * It tests the loading only with a Python outside the system library directories,
 * e.g. installed by uv or by `actions/setup-python`, which sets `LD_LIBRARY_PATH` itself,
 * as the test venv of such a base Python, see `-PbasePython` in DEVELOPMENT.md,
 * while with a Python of the system it passes anyway.
 */
class PythonLibraryTest {

    @Test
    fun `should start embedded Python with symbols of libpython global without LD_LIBRARY_PATH`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "LD_LIBRARY_PATH is the one of Linux")

        // when
        val (exitCode, output) = runInJvm(
            EmbeddedPythonMain::class.java.name,
            // the symbols of the global scope, which a C extension not linked to libpython needs, e.g. of numpy;
            // importing ctypes fails without them, unless it is a built-in module, like in a Python of uv
            input = "import ctypes\nprint(hasattr(ctypes.pythonapi, 'Py_IsInitialized'), flush=True)",
            environment = mapOf("LD_LIBRARY_PATH" to null)
        )

        // then
        assert(output == "True")
        assert(exitCode == 0)
    }

}
