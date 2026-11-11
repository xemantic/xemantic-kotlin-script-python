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
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * The embedded CPython loading the shared `libpython` of its Python without `LD_LIBRARY_PATH`,
 * and importing a C extension module which, like the ones of numpy, is not linked to `libpython`,
 * but needs its symbols to be global,
 * in a JVM of its own, as the environment of the dynamic loader is read when a process starts.
 *
 * It tests the loading only with a Python outside the system library directories,
 * e.g. installed by uv or by `actions/setup-python`, which sets `LD_LIBRARY_PATH` itself,
 * as the test venv of such a base Python, see `-PbasePython` in DEVELOPMENT.md,
 * while with a Python of the system it passes anyway.
 */
class PythonLibraryTest {

    private val venvPython = Path(System.getenv("VIRTUAL_ENV"), "bin", "python").toString()

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should import C extension not linked to libpython without LD_LIBRARY_PATH`() {
        // given
        val directory = createTempDirectory("python-library")
        try {
            compileExtension(directory)

            // when
            val (exitCode, output) = runInJvm(
                EmbeddedPythonMain::class.java.name,
                input = "import xemantic_extension\nprint(xemantic_extension.VALUE, flush=True)",
                environment = mapOf("LD_LIBRARY_PATH" to null, "PYTHONPATH" to directory.toString())
            )

            // then
            assert(output == "imported")
            assert(exitCode == 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should import C extension not linked to libpython at startup of Python without LD_LIBRARY_PATH`() {
        // given
        val directory = createTempDirectory("python-library")
        try {
            compileExtension(directory)
            // imported by the site module at the startup, like a .pth file of the site-packages does, e.g. of coverage
            directory.resolve("sitecustomize.py").writeText(
                """
                |import sys
                |try:
                |    import xemantic_extension
                |    sys.xemantic_startup_import = xemantic_extension.VALUE
                |except ImportError as e:
                |    sys.xemantic_startup_import = repr(e)
                |""".trimMargin()
            )

            // when
            val (exitCode, output) = runInJvm(
                EmbeddedPythonMain::class.java.name,
                input = "import sys\nprint(sys.xemantic_startup_import, flush=True)",
                environment = mapOf("LD_LIBRARY_PATH" to null, "PYTHONPATH" to directory.toString())
            )

            // then
            assert(output == "imported")
            assert(exitCode == 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should import C extension not linked to libpython without LD_LIBRARY_PATH and foreign linker`() {
        // given
        val directory = createTempDirectory("python-library")
        try {
            compileExtension(directory)

            // when
            val (exitCode, output) = runInJvm(
                EmbeddedPythonMain::class.java.name,
                input = "import xemantic_extension\nprint(xemantic_extension.VALUE, flush=True)",
                environment = mapOf("LD_LIBRARY_PATH" to null, "PYTHONPATH" to directory.toString()),
                // like on Java 17 to 21, without the foreign linker
                jvmArguments = listOf("-D${NO_FOREIGN_LINKER_PROPERTY}=true")
            )

            // then
            assert(output == "imported")
            assert(exitCode == 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Compiles the `xemantic_extension` C extension module of the test venv Python into the [directory],
     * not linked to `libpython`, like the C extensions on Linux are,
     * skipping the test if not on Linux, or without a C compiler, or the Python headers.
     */
    private fun compileExtension(directory: Path) {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "the libpython is loaded so only on Linux")
        val (include, suffix) = runProcess(
            pythonCommand(
                venvPython,
                "import sysconfig\nprint(sysconfig.get_paths()['include'])\nprint(sysconfig.get_config_var('EXT_SUFFIX'))"
            ),
            mergeErrorStream = false
        ).output.lines()
        assumeTrue(Path(include, "Python.h").exists(), "no Python headers in $include")
        val source = directory.resolve("xemantic_extension.c")
        source.writeText(
            """
            |#include <Python.h>
            |
            |static struct PyModuleDef definition = {PyModuleDef_HEAD_INIT, "xemantic_extension", NULL, -1, NULL};
            |
            |PyMODINIT_FUNC PyInit_xemantic_extension(void) {
            |    PyObject *module = PyModule_Create(&definition);
            |    if (module != NULL) PyModule_AddStringConstant(module, "VALUE", "imported");
            |    return module;
            |}
            |""".trimMargin()
        )
        val extension = directory.resolve("xemantic_extension$suffix")
        val compiled = try {
            runProcess(listOf("cc", "-shared", "-fPIC", "-I$include", source.toString(), "-o", extension.toString()))
        } catch (_: IOException) {
            null
        }
        assumeTrue(compiled?.exitCode == 0, "cannot compile a C extension with cc: ${compiled?.output}")
    }

}
