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
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeBytes
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
    fun `should tell that dynamic loader does not find libpython listed in its cache with another one of same name`() =
        withLibraryDirectory { directory ->
            // given
            // the order of the string table of the cache is not the one in which the loader takes its entries
            val other = directory.resolve("other").createDirectory().resolve("libxemantic-test.so.1").createFile()
            val library = directory.resolve("libxemantic-test.so.1").createFile()
            val cache = ("ld.so-1.7.0\u0000libxemantic-test.so.1\u0000$library\u0000" +
                "libxemantic-test.so.1\u0000$other\u0000").toByteArray(filesystemCharset)

            // when
            val found = loaderFinds(library, libraryPath = null, cache = { cache })

            // then
            assert(!found)
        }

    @Test
    fun `should tell that dynamic loader finds libpython listed in its cache twice as the same file`() =
        withLibraryDirectory { directory ->
            // given
            val library = directory.resolve("libxemantic-test.so.1").createFile()
            val link = directory.resolve("link").createDirectory().resolve("libxemantic-test.so.1")
                .createSymbolicLinkPointingTo(library)
            val cache = ("ld.so-1.7.0\u0000libxemantic-test.so.1\u0000$link\u0000" +
                "libxemantic-test.so.1\u0000$library\u0000").toByteArray(filesystemCharset)

            // when
            val found = loaderFinds(library, libraryPath = null, cache = { cache })

            // then
            assert(found)
        }

    @Test
    fun `should tell that dynamic loader does not find libpython with dynamic string token in LD_LIBRARY_PATH`() =
        withLibraryDirectory { directory ->
            // given
            // glibc expands $ORIGIN, $LIB and $PLATFORM, e.g. ${PLATFORM} to x86_64
            val library = directory.resolve("libxemantic-test.so.1").createFile()
            val cache = "ld.so-1.7.0\u0000libxemantic-test.so.1\u0000$library\u0000".toByteArray(filesystemCharset)

            // when
            val found = listOf("$directory/\${PLATFORM}", "\$ORIGIN", "/nonexistent:$directory/\$LIB").map {
                loaderFinds(library, libraryPath = it, cache = { cache })
            }

            // then
            assert(found == listOf(false, false, false))
        }

    @Test
    fun `should tell that dynamic loader does not find libpython only in directory it might not search by default`() {
        // given
        // e.g. /usr/lib, which glibc searches by default on Debian, but not on Fedora, where it searches /usr/lib64
        val library = Path("/usr/lib/os-release")
        assumeTrue(library.isRegularFile(), "no $library")

        // when
        val found = loaderFinds(library, libraryPath = null, cache = { null })

        // then
        assert(!found)
    }

    @Test
    fun `should tell that dynamic loader does not find libpython with invalid path in LD_LIBRARY_PATH`() =
        withLibraryDirectory { directory ->
            // given
            // like a path which is not valid in the encoding of the file system, e.g. of an ISO-8859-1 locale
            val library = directory.resolve("libxemantic-test.so.1").createFile()

            // when
            val found = loaderFinds(library, libraryPath = "/invalid\u0000path:$directory", cache = { null })

            // then
            assert(!found)
        }

    @Test
    fun `should take name of libpython which Jep opens from its native library`() =
        withLibraryDirectory { directory ->
            // given
            // the soname needed by the native library of Jep, and the name of its PYTHON_LDLIBRARY, among other strings
            val jepLibrary = directory.resolve("libjep.so")
            jepLibrary.writeBytes(
                ("\u007fELF\u0000libjvm.so\u0000libpython3.13.so.1.0\u0000libc.so.6\u0000\u0000\u0000" +
                    "jep/__init__.py\u0000libpython3.13.so\u0000Jep %s\u0000").toByteArray(Charsets.ISO_8859_1)
            )

            // when
            val name = jepLibraryName(jepLibrary)

            // then
            assert(name == "libpython3.13.so")
        }

    @Test
    fun `should take static libpython which Jep opens from its native library`() =
        withLibraryDirectory { directory ->
            // given
            // e.g. of conda-forge, when the static libpython3.12.a existed when Jep was built
            val jepLibrary = directory.resolve("libjep.so")
            jepLibrary.writeBytes(
                "\u007fELF\u0000libpython3.12.so.1.0\u0000\u0000libpython3.12.a\u0000".toByteArray(Charsets.ISO_8859_1)
            )

            // when
            val name = jepLibraryName(jepLibrary)

            // then
            assert(name == "libpython3.12.a")
        }

    @Test
    fun `should not take name of libpython which Jep opens when its native library does not tell it for certain`() =
        withLibraryDirectory { directory ->
            // given
            fun jepLibrary(name: String, content: String) = directory.resolve(name).apply {
                writeBytes(content.toByteArray(Charsets.ISO_8859_1))
            }
            // e.g. built without PYTHON_LDLIBRARY, with only the soname it needs
            val withoutName = jepLibrary("without.so", "\u007fELF\u0000libpython3.13.so.1.0\u0000")
            val twoNames = jepLibrary("two.so", "\u007fELF\u0000libpython3.13.so\u0000libpython3.12.so\u0000")
            // e.g. a part of a longer string
            val notStandalone = jepLibrary("part.so", "\u007fELF\u0000/opt/libpython3.13.so\u0000")
            val missing = directory.resolve("missing.so")

            // when
            val names = listOf(withoutName, twoNames, notStandalone, missing).map { jepLibraryName(it) }

            // then
            assert(names == listOf("", "", "", ""))
        }

    @Test
    fun `should advise preloading libpython to make its symbols global when Jep cannot`() {
        // given
        val library = Path("/opt/conda/lib/libpython3.12.so.1.0")

        // when
        val advice = listOf("libpython3.12.a", "").map { pythonLibraryGlobalAdvice(library, it) }

        // then
        assert(
            advice == List(2) {
                "put /opt/conda/lib/libpython3.12.so.1.0 on LD_PRELOAD before the JVM starts, " +
                    "as Jep does not make its symbols global with this Python"
            }
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
