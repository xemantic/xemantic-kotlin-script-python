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
import kotlin.io.path.listDirectoryEntries
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
        // on Java 17 to 21, the symbols of libpython become global only after the startup, see loadPythonLibrary
        assumeTrue(Runtime.version().feature() >= 22, "the foreign linker is of Java 22")
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

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should load libpython of relocated Python reporting stale library directory without LD_LIBRARY_PATH`() {
        // given
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "the libpython is loaded so only on Linux")
        val directory = createTempDirectory("python-relocated")
        try {
            val basePrefix = relocateBasePython(directory.resolve("python"))
            val venv = directory.resolve("venv")
            val created = runProcess(
                listOf(basePrefix.resolve("bin").resolve("python3").toString(), "-I", "-m", "venv", "--without-pip", venv.toString())
            )
            assert(created.exitCode == 0)
            // jep of the test venv, of the same Python version
            val sitePackages = runProcess(
                pythonCommand(venvPython, "import sysconfig\nprint(sysconfig.get_path('purelib'))"),
                mergeErrorStream = false
            ).output
            venv.resolve("lib").listDirectoryEntries().single().resolve("site-packages").resolve("jep-of-tests.pth")
                .writeText(sitePackages + "\n")

            // when
            val (exitCode, output) = runInJvm(
                EmbeddedPythonMain::class.java.name,
                input = "import sys\nprint(sys.base_prefix, flush=True)",
                environment = mapOf("LD_LIBRARY_PATH" to null, "PYTHONPATH" to null, "VIRTUAL_ENV" to venv.toString()),
                jvmArguments = listOf("-Dxemantic.kotlin.script.python.dependencyDirectory=${directory.resolve("dependencies")}")
            )

            // then
            assert(output == basePrefix.toString())
            assert(exitCode == 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Makes the base Python of the test venv relocated into the [prefix], like a python-build-standalone not patched by uv,
     * whose `sysconfig` reports the `LIBDIR` of `/install/lib`, where it was built,
     * copying its executable and `libpython`, as CPython finds its prefix from their real paths,
     * and linking the rest, but the `_sysconfigdata` module, in which its prefix is replaced by `/install`,
     * skipping the test if the base Python is not a self-contained one with a shared `libpython` in its `lib` directory,
     * e.g. a Python of the system.
     *
     * @return the [prefix].
     */
    private fun relocateBasePython(prefix: Path): Path {
        val result = runProcess(
            pythonCommand(
                venvPython,
                """
                |import glob, os, shutil, sys, sysconfig
                |base = sys.base_prefix
                |libdir = sysconfig.get_config_var('LIBDIR')
                |soname = sysconfig.get_config_var('INSTSONAME') or ''
                |library = os.path.join(base, 'lib', soname)
                |if libdir != os.path.join(base, 'lib') or not soname.endswith('.so.1.0') or not os.path.isfile(library):
                |    print('not a self-contained Python with a shared libpython:', base, libdir, soname)
                |    sys.exit(3)
                |target = sys.argv[1]
                |stdlib = sysconfig.get_path('stdlib')
                |os.makedirs(os.path.join(target, 'bin'))
                |shutil.copy2(os.path.realpath(sys._base_executable), os.path.join(target, 'bin', 'python3'))
                |target_stdlib = os.path.join(target, 'lib', os.path.basename(stdlib))
                |os.makedirs(target_stdlib)
                |shutil.copy2(library, os.path.join(target, 'lib', soname))
                |for name in os.listdir(stdlib):
                |    source = os.path.join(stdlib, name)
                |    if name == '__pycache__':
                |        continue
                |    if name.startswith('_sysconfigdata') and name.endswith('.py'):
                |        with open(source, encoding='utf-8') as file:
                |            content = file.read()
                |        with open(os.path.join(target_stdlib, name), 'w', encoding='utf-8') as file:
                |            file.write(content.replace(base, '/install'))
                |    else:
                |        os.symlink(source, os.path.join(target_stdlib, name))
                |""".trimMargin(),
                prefix.toString()
            )
        )
        assumeTrue(result.exitCode != 3, result.output)
        assert(result.exitCode == 0)
        val libraryDirectory = runProcess(
            pythonCommand(prefix.resolve("bin").resolve("python3").toString(), "import sysconfig\nprint(sysconfig.get_config_var('LIBDIR'))"),
            mergeErrorStream = false
        ).output
        assert(libraryDirectory == "/install/lib")
        return prefix
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
