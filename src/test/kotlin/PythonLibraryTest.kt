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
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
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

    @Test
    fun `should import C extension not linked to libpython without LD_LIBRARY_PATH`() = withExtension { directory ->
        // when
        val (exitCode, output) = importExtension(directory, libraryPath = null)

        // then
        assert(output == "imported")
        assert(exitCode == 0)
    }

    @Test
    fun `should import C extension not linked to libpython at startup of Python without LD_LIBRARY_PATH`() {
        // given
        // on Java 17 to 21, the symbols of libpython become global only after the startup, see loadPythonLibrary
        assumeTrue(Runtime.version().feature() >= 22, "the foreign linker is of Java 22")
        withExtension { directory ->
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
            val (exitCode, output) = importExtension(
                directory,
                libraryPath = null,
                input = "import sys\nprint(sys.xemantic_startup_import, flush=True)"
            )

            // then
            assert(output == "imported")
            assert(exitCode == 0)
        }
    }

    @Test
    fun `should import C extension not linked to libpython without LD_LIBRARY_PATH and foreign linker`() =
        withExtension { directory ->
            // when
            val (exitCode, output) = importExtension(directory, libraryPath = null, foreignLinker = false)

            // then
            assert(output == "imported")
            assert(exitCode == 0)
        }

    @Test
    fun `should import C extension not linked to libpython found by loader without its development symlink`() =
        withExtension { directory ->
            // given
            val libraryPath = sonameOnlyLibraryPath(directory)

            // when
            val (exitCode, output) = importExtension(directory, libraryPath.toString())

            // then
            assert(output == "imported")
            assert(exitCode == 0)
        }

    @Test
    fun `should import C extension not linked to libpython found by loader without its development symlink and foreign linker`() =
        withExtension { directory ->
            // given
            val libraryPath = sonameOnlyLibraryPath(directory)

            // when
            val (exitCode, output) = importExtension(directory, libraryPath.toString(), foreignLinker = false)

            // then
            assert(output == "imported")
            assert(exitCode == 0)
        }

    @Test
    fun `should tell that dynamic loader does not find libpython behind empty entry of LD_LIBRARY_PATH with another libpython in working directory`() =
        withLibraryDirectory { directory ->
            // given
            // glibc takes an empty entry for the working directory, which has another libpython of the same soname
            val workingDirectory = directory.resolve("working").createDirectory()
            workingDirectory.resolve("libxemantic-test.so.1").createFile()
            val library = directory.resolve("libxemantic-test.so.1").createFile()
            val cache = "ld.so-1.7.0\u0000libxemantic-test.so.1\u0000$library\u0000".toByteArray(filesystemCharset)

            // when
            // a trailing empty entry before the library listed in the cache
            val found = listOf(":$directory", "/nonexistent:", "/nonexistent::$directory").map { libraryPath ->
                loaderFinds(library, libraryPath = libraryPath, cache = { cache }, workingDirectory = workingDirectory)
            }

            // then
            assert(found == listOf(false, false, false))
        }

    @Test
    fun `should tell that dynamic loader does not find libpython in working directory of empty entry of LD_LIBRARY_PATH`() =
        withLibraryDirectory { directory ->
            // given
            // glibc would find it there, but musl skips an empty entry, so that it is not certain
            val library = directory.resolve("libxemantic-test.so.1").createFile()

            // when
            val found = loaderFinds(library, libraryPath = ":/nonexistent", cache = { null }, workingDirectory = directory)

            // then
            assert(!found)
        }

    @Test
    fun `should tell that dynamic loader finds libpython behind empty entry of LD_LIBRARY_PATH without it in working directory`() =
        withLibraryDirectory { directory ->
            // given
            val workingDirectory = directory.resolve("working").createDirectory()
            val library = directory.resolve("libxemantic-test.so.1").createFile()

            // when
            val found = loaderFinds(
                library, libraryPath = ":$directory:", cache = { null }, workingDirectory = workingDirectory
            )

            // then
            assert(found)
        }

    @Test
    fun `should tell that dynamic loader finds libpython in relative entry of LD_LIBRARY_PATH of working directory`() =
        withLibraryDirectory { directory ->
            // given
            val library = directory.resolve("lib").createDirectory().resolve("libxemantic-test.so.1").createFile()

            // when
            val found = loaderFinds(library, libraryPath = "lib", cache = { null }, workingDirectory = directory)
            val notFound = loaderFinds(library, libraryPath = "other", cache = { null }, workingDirectory = directory)

            // then
            assert(found && !notFound)
        }

    @Test
    fun `should tell that dynamic loader does not find libpython with LD_LIBRARY_PATH split at semicolon`() =
        withLibraryDirectory { directory ->
            // given
            // glibc splits LD_LIBRARY_PATH also at a semicolon, unlike musl
            val library = directory.resolve("libxemantic-test.so.1").createFile()

            // when
            val found = loaderFinds(library, libraryPath = "/nonexistent;$directory:$directory", cache = { null })

            // then
            assert(!found)
        }

    @Test
    fun `should tell that dynamic loader does not find libpython with another one in glibc-hwcaps subdirectory`() =
        withLibraryDirectory { directory ->
            // given
            // glibc searches first the subdirectory of the most capable CPU level it supports
            val library = directory.resolve("libxemantic-test.so.1").createFile()
            directory.resolve("glibc-hwcaps/x86-64-v3").createDirectories().resolve("libxemantic-test.so.1").createFile()

            // when
            val found = loaderFinds(library, libraryPath = "$directory", cache = { null })

            // then
            assert(!found)
        }

    @Test
    fun `should tell that dynamic loader does not find libpython with glibc-hwcaps one in its cache`() =
        withLibraryDirectory { directory ->
            // given
            val library = directory.resolve("libxemantic-test.so.1").createFile()
            val hwcapsLibrary = directory.resolve("glibc-hwcaps/x86-64-v3").createDirectories()
                .resolve("libxemantic-test.so.1").createFile()
            val cache = ("ld.so-1.7.0\u0000libxemantic-test.so.1\u0000$library\u0000" +
                "libxemantic-test.so.1\u0000$hwcapsLibrary\u0000").toByteArray(filesystemCharset)

            // when
            val found = loaderFinds(library, libraryPath = null, cache = { cache })

            // then
            assert(!found)
        }

    @Test
    fun `should take development symlink of shared libpython as the name Jep opens`() =
        withLibraryDirectory { directory ->
            // when
            val name = jepLibraryName("$directory", "libpython3.13.so", "x86_64-linux-gnu")

            // then
            assert(name == "libpython3.13.so")
        }

    @Test
    fun `should take shared libpython of static development name as the name Jep opens`() =
        withLibraryDirectory { directory ->
            // given
            // e.g. of conda-forge, without the static libpython3.12.a
            val libraryDirectory = directory.resolve("lib").createDirectory()
            libraryDirectory.resolve("libpython3.12.so").createFile()
            directory.resolve("multiarch").createDirectory().resolve("libpython3.12.so").createFile()

            // when
            val name = jepLibraryName("$libraryDirectory", "libpython3.12.a", "x86_64-linux-gnu")
            val multiarchName = jepLibraryName("$directory", "libpython3.12.a", "multiarch")

            // then
            assert(name == "libpython3.12.so" && multiarchName == "libpython3.12.so")
        }

    @Test
    fun `should not take static libpython which Jep opens as development name`() =
        withLibraryDirectory { directory ->
            // given
            // Jep opens the static libpython3.12.a, which it cannot, when it exists, also in the multiarch subdirectory
            val static = directory.resolve("static").createDirectory()
            static.resolve("libpython3.12.a").createFile()
            static.resolve("libpython3.12.so").createFile()
            val multiarchStatic = directory.resolve("multiarch-static").createDirectory()
            multiarchStatic.resolve("libpython3.12.so").createFile()
            multiarchStatic.resolve("x86_64-linux-gnu").createDirectory().resolve("libpython3.12.a").createFile()
            val neither = directory.resolve("neither").createDirectory()

            // when
            val names = listOf(static, multiarchStatic, neither).map {
                jepLibraryName("$it", "libpython3.12.a", "x86_64-linux-gnu")
            }
            val unknownDirectory = jepLibraryName("", "libpython3.12.a", "x86_64-linux-gnu")

            // then
            assert(names == listOf("", "", "") && unknownDirectory == "")
        }

    @Test
    fun `should not advise static libpython to make its symbols global`() {
        // given
        val library = Path("/opt/conda/lib/libpython3.12.so.1.0")

        // when
        val advice = pythonLibraryGlobalAdvice(library, "libpython3.12.a")

        // then
        assert(
            advice == "put /opt/conda/lib on LD_LIBRARY_PATH before the JVM starts, " +
                "unless it is a directory the dynamic loader searches anyway"
        )
    }

    @Test
    fun `should tell that Jep does not load libpython globally by static development name`() {
        // given
        val library = Path("/opt/conda/lib/libpython3.12.so.1.0")

        // when
        val loadsGlobally = jepLoadsGlobally(library, "libpython3.12.a", finds = { true })

        // then
        assert(!loadsGlobally)
    }

    /** Runs the [block] with a new temporary directory, deleted afterwards. */
    @OptIn(ExperimentalPathApi::class)
    private fun withLibraryDirectory(block: (directory: Path) -> Unit) {
        val directory = createTempDirectory("python-library")
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Runs the [block] with a new temporary directory, deleted afterwards,
     * into which the `xemantic_extension` is compiled, see [compileExtension].
     */
    @OptIn(ExperimentalPathApi::class)
    private fun withExtension(block: (directory: Path) -> Unit) {
        val directory = createTempDirectory("python-library")
        try {
            compileExtension(directory)
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * Runs the [input], importing the `xemantic_extension` compiled into the [directory] by default,
     * in the embedded Python of a JVM of its own, with the [libraryPath] as its `LD_LIBRARY_PATH`, none if `null`,
     * and without the foreign linker unless [foreignLinker], like on Java 17 to 21.
     *
     * @return the exit code and the output of the JVM.
     */
    private fun importExtension(
        directory: Path,
        libraryPath: String?,
        input: String = "import xemantic_extension\nprint(xemantic_extension.VALUE, flush=True)",
        foreignLinker: Boolean = true
    ): Pair<Int, String> = runInJvm(
        EmbeddedPythonMain::class.java.name,
        input = input,
        environment = mapOf("LD_LIBRARY_PATH" to libraryPath, "PYTHONPATH" to directory.toString()),
        jvmArguments = if (foreignLinker) emptyList() else listOf("-D${NO_FOREIGN_LINKER_PROPERTY}=true")
    )

    /**
     * A directory, in the [directory], for `LD_LIBRARY_PATH`, with a symbolic link of the soname of the `libpython`
     * of the test venv Python to it, but without its development symlink, e.g. `libpython3.13.so`,
     * like a Python of the system without its development package, e.g. `libpython3.13-dev`,
     * so that the dynamic loader finds this `libpython` for the native library of Jep, but Jep does not find it by this name.
     * It tests nothing with a Python whose development symlink the loader finds anyway, e.g. a Python of the system.
     */
    private fun sonameOnlyLibraryPath(directory: Path): Path {
        val (libraryDirectory, soname) = runProcess(
            pythonCommand(
                venvPython,
                "import sysconfig\nprint(sysconfig.get_config_var('LIBDIR'))\nprint(sysconfig.get_config_var('INSTSONAME'))"
            ),
            mergeErrorStream = false
        ).output.lines()
        val library = pythonLibrary(libraryDirectory, soname)
        assumeTrue(library != null, "the Python is not built with a shared libpython")
        val libraryPath = directory.resolve("lib").createDirectory()
        libraryPath.resolve(soname).createSymbolicLinkPointingTo(library!!)
        return libraryPath
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
