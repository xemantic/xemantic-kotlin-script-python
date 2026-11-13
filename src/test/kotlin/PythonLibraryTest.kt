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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        fun cache(vararg paths: String) =
            ("ld.so-1.7.0\u0000" + paths.joinToString("") { "${Path(it).fileName}\u0000$it\u0000" })
                .toByteArray(filesystemCharset)
        // a cache of another library of the same directory, so that it is read, but does not list the library
        val withoutLibrary = cache("/usr/lib/libxemantic-test.so.1")
        val withLibrary = cache("/usr/lib/libxemantic-test.so.1", "$library")

        // when
        val found = loaderFinds(library, libraryPath = null, cache = { withoutLibrary })
        val foundInCache = loaderFinds(library, libraryPath = null, cache = { withLibrary })

        // then
        assert(!found && foundInCache)
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
    fun `should advise Java 22 or preloading libpython only for JVM when Jep does not make its symbols global`() {
        // given
        // Jep compiled in the name of the static library, which existed when it was built
        val library = Path("/opt/python/lib/libpython3.12.so.1.0")

        // when
        val advice = pythonLibraryGlobalAdvice(library, "libpython3.12.a", javaVersion = 21)

        // then
        assert(
            advice == "run the JVM on Java 22 or newer with --enable-native-access=ALL-UNNAMED, " +
                "which loads /opt/python/lib/libpython3.12.so.1.0 with global symbols before Python starts, " +
                "or put it on LD_PRELOAD, as Jep does not make its symbols global with this Python, " +
                "set only for the launch of the JVM, e.g. LD_PRELOAD=/opt/python/lib/libpython3.12.so.1.0 java ..., " +
                "as the processes started by the Python code inherit it, e.g. with subprocess, " +
                "unlike the ones started by this library"
        )
    }

    @Test
    fun `should advise preloading libpython conditionally when Jep might make its symbols global`() {
        // given
        // the name by which Jep makes them global cannot be read from its native library
        val library = Path("/opt/python/lib/libpython3.13.so.1.0")

        // when
        val advice = pythonLibraryGlobalAdvice(library, "", javaVersion = 21)

        // then
        assert(
            advice == "run the JVM on Java 22 or newer with --enable-native-access=ALL-UNNAMED, " +
                "which loads /opt/python/lib/libpython3.13.so.1.0 with global symbols before Python starts, " +
                "or put it on LD_PRELOAD, if Jep does not make its symbols global with this Python, " +
                "which its native library does not tell for certain, " +
                "set only for the launch of the JVM, e.g. LD_PRELOAD=/opt/python/lib/libpython3.13.so.1.0 java ..., " +
                "as the processes started by the Python code inherit it, e.g. with subprocess, " +
                "unlike the ones started by this library"
        )
    }

    @Test
    fun `should not advise Java 22 to make symbols of libpython global when JVM already is`() {
        // given
        val library = Path("/opt/python/lib/libpython3.12.so.1.0")

        // when
        val failed = pythonLibraryGlobalAdvice(library, "libpython3.12.a", javaVersion = 25, foreignLinker = true)
        val disabled = pythonLibraryGlobalAdvice(library, "libpython3.12.a", javaVersion = 22, foreignLinker = false)

        // then
        assert(
            failed.startsWith(
                "run the JVM with --enable-native-access=ALL-UNNAMED, as the foreign linker failed to load " +
                    "/opt/python/lib/libpython3.12.so.1.0 with global symbols before Python starts, " +
                    "or put it on LD_PRELOAD, "
            ) &&
                disabled.startsWith(
                    "run the JVM without -D$NO_FOREIGN_LINKER_PROPERTY=true, with which the foreign linker loads " +
                        "/opt/python/lib/libpython3.12.so.1.0 with global symbols before Python starts, " +
                        "or put it on LD_PRELOAD, "
                ) &&
                "Java 22" !in failed + disabled
        )
    }

    @Test
    fun `should tell that Jep does not load libpython globally by static development name`() {
        // given
        val library = Path("/opt/conda/lib/libpython3.12.so.1.0")

        // when
        val loadsGlobally = jepLoadsGlobally(
            library,
            jepDynamic = { ElfDynamic(listOf("libpython3.12.so.1.0"), searchPath = false) },
            developmentName = { "libpython3.12.a" },
            finds = { true }
        )

        // then
        assert(!loadsGlobally)
    }

    @Test
    fun `should tell that Jep does not load libpython globally when its native library has search path of its own`() {
        // given
        // e.g. the RUNPATH of a conda build, searched before ld.so.cache, or an RPATH, even before LD_LIBRARY_PATH
        val library = Path("/usr/lib/libpython3.13.so.1.0")
        fun loadsGlobally(dynamic: ElfDynamic?) = jepLoadsGlobally(
            library, jepDynamic = { dynamic }, developmentName = { "libpython3.13.so" }, finds = { true }
        )

        // when
        val withSearchPath = loadsGlobally(ElfDynamic(listOf("libpython3.13.so.1.0"), searchPath = true))
        val unreadable = loadsGlobally(null)
        val withoutSearchPath = loadsGlobally(ElfDynamic(listOf("libpython3.13.so.1.0"), searchPath = false))

        // then
        assert(!withSearchPath && !unreadable && withoutSearchPath)
    }

    @Test
    fun `should not read native library of Jep when dynamic loader does not find libpython by its soname`() {
        // given
        val library = Path("/opt/python/lib/libpython3.13.so.1.0")

        // when
        val loadsGlobally = jepLoadsGlobally(
            library,
            jepDynamic = { error("dynamic section read") },
            developmentName = { error("native library scanned") },
            finds = { false }
        )

        // then
        assert(!loadsGlobally)
    }

    @Test
    fun `should read shared libraries needed by ELF library and whether it has search path of its own`() =
        withLibraryDirectory { directory ->
            // given
            fun library(name: String, bytes: ByteArray) = directory.resolve(name).apply { writeBytes(bytes) }
            val needed = listOf("libjvm.so", "libpython3.12.so.1.0", "libc.so.6")
            val plain = library("plain.so", elfLibrary(needed))
            val withRunpath = library("runpath.so", elfLibrary(needed, runpath = "\$ORIGIN/../../.."))
            val withRpath = library("rpath.so", elfLibrary(needed, rpath = "/opt/conda/lib"))
            val bigEndian32 = library("be32.so", elfLibrary(needed, is64 = false, order = ByteOrder.BIG_ENDIAN))

            // when
            val dynamics = listOf(plain, withRunpath, withRpath, bigEndian32).map { readElfDynamic(it) }

            // then
            assert(
                dynamics.map { it?.needed } == List(4) { needed } &&
                    dynamics.map { it?.searchPath } == listOf(false, true, true, false)
            )
        }

    @Test
    fun `should not read dynamic section of file which is not ELF library`() =
        withLibraryDirectory { directory ->
            // given
            fun library(name: String, bytes: ByteArray) = directory.resolve(name).apply { writeBytes(bytes) }
            val complete = elfLibrary(listOf("libpython3.12.so.1.0"))
            // e.g. a linker script
            val text = library("text.so", "INPUT(libpython3.12.so.1.0)".toByteArray())
            val truncated = library("truncated.so", complete.copyOf(complete.size - 20))
            val headerOnly = library("header.so", complete.copyOf(64))
            val missing = directory.resolve("missing.so")

            // when
            val dynamics = listOf(text, truncated, headerOnly, missing).map { readElfDynamic(it) }

            // then
            assert(dynamics == listOf(null, null, null, null))
        }

    @Test
    fun `should read libpython needed by native library of Jep of test venv`() {
        // given
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "an ELF library only on Linux")
        val probe = PythonEnvironment.probePython(venvPython)
        val jepLibrary = PythonEnvironment.jepLibrary(Path(probe.jepLocation), venvPython, probe.externallyManaged)
        val shared = pythonLibrary(probe.libraryDirectory, probe.librarySoname)
        assumeTrue(shared != null, "the Python is not built with a shared libpython")

        // when
        val dynamic = readElfDynamic(jepLibrary)

        // then
        assert(dynamic != null && shared!!.fileName.toString() in dynamic.needed)
    }

    @Test
    fun `should find shared libpython needed by Jep of Python with static soname`() =
        withLibraryDirectory { directory ->
            // given
            // e.g. of conda-forge, whose INSTSONAME is libpython3.12.a, which it does not ship, unlike the shared one
            val library = directory.resolve("libpython3.12.so.1.0").createFile()
            directory.resolve("libpython3.12.so").createSymbolicLinkPointingTo(library.fileName)

            // when
            val found = pythonLibrary(
                directory.toString(), "libpython3.12.a", jepNeeded = { listOf("libc.so.6", "libpython3.12.so.1.0") }
            )

            // then
            assert(found == library)
        }

    @Test
    fun `should not find shared libpython for static soname unless Jep needs one of same version present`() =
        withLibraryDirectory { directory ->
            // given
            directory.resolve("libpython3.12.so.1.0").createFile()
            directory.resolve("libpython3.11.so.1.0").createFile()
            directory.resolve("libpython3.12t.so.1.0").createFile()
            fun found(needed: List<String>?) =
                pythonLibrary(directory.toString(), "libpython3.12.a", jepNeeded = { needed })

            // when
            val found = listOf(
                // of a Jep built for another Python
                found(listOf("libpython3.11.so.1.0")),
                // of another ABI
                found(listOf("libpython3.12t.so.1.0")),
                // of an unreadable native library of Jep
                found(null),
                found(emptyList()),
                // ambiguous
                found(listOf("libpython3.12.so.1.0", "libpython3.12.so")),
                // missing in the library directory
                found(listOf("libpython3.12.so.2.0"))
            )

            // then
            assert(found == List(6) { null })
        }

    @Test
    fun `should not find shared libpython of soname which Jep does not need`() =
        withLibraryDirectory { directory ->
            // given
            val library = directory.resolve("libpython3.13.so.1.0").createFile()
            fun found(needed: List<String>?) =
                pythonLibrary(directory.toString(), "libpython3.13.so.1.0", jepNeeded = { needed })

            // when
            // of a Jep built for another Python, which would not load this libpython anyway
            val otherVersion = found(listOf("libpython3.12.so.1.0", "libc.so.6"))
            val same = found(listOf("libpython3.13.so.1.0", "libc.so.6"))
            // of an unreadable native library of Jep, or one not linked to libpython, which then only opens it
            val unreadable = found(null)
            val notLinked = found(listOf("libc.so.6"))

            // then
            assert(otherVersion == null && same == library && unreadable == library && notLinked == library)
        }

    @Test
    fun `should not read too long name or too many libraries needed by ELF library`() =
        withLibraryDirectory { directory ->
            // given
            fun library(name: String, bytes: ByteArray) = directory.resolve(name).apply { writeBytes(bytes) }
            val longName = library("long.so", elfLibrary(listOf("libpython" + "3".repeat(5000) + ".so.1.0")))
            val manyNeeded = library("many.so", elfLibrary(List(2000) { "libxemantic$it.so" }))
            val limits = library("limits.so", elfLibrary(List(1000) { "libxemantic$it.so" } + "x".repeat(4000)))

            // when
            val dynamics = listOf(longName, manyNeeded, limits).map { readElfDynamic(it) }

            // then
            assert(dynamics[0] == null && dynamics[1] == null && dynamics[2]?.needed?.size == 1001)
        }

    @Test
    fun `should tell directories of RPATH of JVM executable which glibc searches before LD_LIBRARY_PATH`() =
        withLibraryDirectory { directory ->
            // given
            // like the java launcher, with RPATH $ORIGIN:$ORIGIN/../lib, inherited by the libraries it loads
            val bin = directory.resolve("jdk/bin").createDirectories().toRealPath()
            val java = bin.resolve("java")
            java.writeBytes(elfLibrary(listOf("libc.so.6"), rpath = "\$ORIGIN:\${ORIGIN}/../lib"))
            // e.g. /usr/bin/java, linked to the launcher of the JDK
            val link = directory.resolve("java").createSymbolicLinkPointingTo(java)
            val withToken = directory.resolve("token")
            withToken.writeBytes(elfLibrary(emptyList(), runpath = "/opt/\$LIB"))
            val relative = directory.resolve("relative")
            relative.writeBytes(elfLibrary(emptyList(), rpath = "lib"))
            val javaHome = directory.resolve("jdk").toString()

            // when
            val directories = jvmSearchDirectories(link, javaHome)
            val unknown = listOf(withToken, relative, directory.resolve("missing")).map {
                jvmSearchDirectories(it, javaHome)
            }

            // then
            assert(
                directories == listOf(bin, bin.resolve("../lib"), Path(javaHome, "lib")) &&
                    unknown == listOf(null, null, null)
            )
        }

    @Test
    fun `should tell directories of RPATH of this JVM`() {
        // given
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "an ELF executable only on Linux")

        // when
        val directories = jvmSearchDirectories()

        // then
        assert(directories != null && Path(System.getProperty("java.home"), "lib") in directories)
    }

    @Test
    fun `should tell that dynamic loader does not find libpython with another one in directory of JVM`() =
        withLibraryDirectory { directory ->
            // given
            val jvmDirectory = directory.resolve("jvm").createDirectory()
            val library = directory.resolve("libxemantic-test.so.1").createFile()
            fun found(jvmDirectories: List<Path>?) =
                loaderFinds(library, libraryPath = "$directory", cache = { null }, jvmDirectories = { jvmDirectories })

            // when
            val withoutIt = found(listOf(jvmDirectory))
            val unknown = found(null)
            jvmDirectory.resolve("libxemantic-test.so.1").createFile()
            val withAnother = found(listOf(jvmDirectory))
            val withSame = found(listOf(directory))

            // then
            assert(withoutIt && !unknown && !withAnother && withSame)
        }

    /**
     * The bytes of a minimal ELF shared library which needs the [needed] libraries, with the [rpath] and [runpath], if any:
     * a `PT_LOAD` segment of the whole file at another address than its offset, and the `PT_DYNAMIC` segment.
     */
    private fun elfLibrary(
        needed: List<String>,
        rpath: String? = null,
        runpath: String? = null,
        is64: Boolean = true,
        order: ByteOrder = ByteOrder.LITTLE_ENDIAN
    ): ByteArray {
        val strings = ByteArrayOutputStream().apply { write(0) }
        fun string(value: String): Long = strings.size().toLong().also {
            strings.write(value.toByteArray())
            strings.write(0)
        }
        // DT_NEEDED, DT_RPATH, DT_RUNPATH
        val entries = mutableListOf<Pair<Long, Long>>()
        needed.forEach { entries += 1L to string(it) }
        rpath?.let { entries += 15L to string(it) }
        runpath?.let { entries += 29L to string(it) }
        val headerSize = if (is64) 64 else 52
        val programHeaderSize = if (is64) 56 else 32
        val entrySize = if (is64) 16 else 8
        val stringTableOffset = headerSize + 2 * programHeaderSize
        val stringTable = strings.toByteArray()
        val dynamicOffset = (stringTableOffset + stringTable.size + 7) / 8 * 8
        val base = 0x400000L
        // DT_STRTAB, DT_STRSZ, DT_NULL
        entries += 5L to base + stringTableOffset
        entries += 10L to stringTable.size.toLong()
        entries += 0L to 0L
        val size = dynamicOffset + entries.size * entrySize
        val buffer = ByteBuffer.allocate(size).order(order)
        fun word(at: Int, value: Long) {
            if (is64) buffer.putLong(at, value) else buffer.putInt(at, value.toInt())
        }
        buffer.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        buffer.put(if (is64) 2 else 1)
        buffer.put(if (order == ByteOrder.LITTLE_ENDIAN) 1 else 2)
        buffer.put(1)
        // ET_DYN
        buffer.putShort(16, 3)
        word(if (is64) 32 else 28, headerSize.toLong())
        buffer.putShort(if (is64) 54 else 42, programHeaderSize.toShort())
        buffer.putShort(if (is64) 56 else 44, 2)
        fun programHeader(index: Int, type: Int, offset: Long, fileSize: Long) {
            val at = headerSize + index * programHeaderSize
            buffer.putInt(at, type)
            if (is64) {
                buffer.putLong(at + 8, offset)
                buffer.putLong(at + 16, base + offset)
                buffer.putLong(at + 32, fileSize)
            } else {
                buffer.putInt(at + 4, offset.toInt())
                buffer.putInt(at + 8, (base + offset).toInt())
                buffer.putInt(at + 16, fileSize.toInt())
            }
        }
        // PT_LOAD, PT_DYNAMIC
        programHeader(0, 1, 0, size.toLong())
        programHeader(1, 2, dynamicOffset.toLong(), (entries.size * entrySize).toLong())
        buffer.put(stringTableOffset, stringTable)
        entries.forEachIndexed { index, (tag, value) ->
            word(dynamicOffset + index * entrySize, tag)
            word(dynamicOffset + index * entrySize + entrySize / 2, value)
        }
        return buffer.array()
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
