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

import java.io.IOException
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSameFileAs
import kotlin.io.path.readBytes

/**
 * The system property which, set to `true`, keeps [loadPythonLibrary] from using the foreign linker of Java 22,
 * a test seam of loading `libpython` like on Java 17 to 21.
 */
internal const val NO_FOREIGN_LINKER_PROPERTY = "xemantic.kotlin.script.python.noForeignLinker"

/** Whether [loadPythonLibrary] may use the foreign linker, unless the [NO_FOREIGN_LINKER_PROPERTY] is `true`. */
internal fun foreignLinkerEnabled(): Boolean = !java.lang.Boolean.getBoolean(NO_FOREIGN_LINKER_PROPERTY)

/**
 * The shared `libpython` of the [soname] in the library [directory] of a Python, see [PythonEnvironment.probeCommand],
 * or `null` if there is none, e.g. of a Python linked to `libpython` statically, with `libpython3.X.a` as its soname.
 *
 * The `LIBDIR` of `sysconfig` is the one where the Python was built, at its [buildExecPrefix],
 * so a Python moved since reports a stale one, e.g. `/install/lib` of a python-build-standalone not installed by uv,
 * while its `libpython` moved with it, to its [baseExecPrefix], `sys.base_exec_prefix`, which CPython tells from its executable.
 * When the two differ, the [directory] is taken moved to the [baseExecPrefix] instead, e.g. also `lib64`,
 * and not as it is, where a library of the same soname might be the one of another installation.
 * Otherwise, or when the moved [directory] has none, e.g. not under the [buildExecPrefix],
 * it is the one of the `lib` directory of the [baseExecPrefix].
 * Only a library of the [soname] is taken, not e.g. `libpython3.X.so` or `libpython3.so`,
 * as the native library of Jep is linked to the soname, which the dynamic loader then finds already loaded,
 * and so never the library of another Python version.
 */
internal fun pythonLibrary(
    directory: String,
    soname: String,
    baseExecPrefix: String = "",
    buildExecPrefix: String = ""
): Path? {
    if (soname.isEmpty() || soname.endsWith(".a")) return null
    val relocated = baseExecPrefix.isNotEmpty() && buildExecPrefix.isNotEmpty() &&
        Path(baseExecPrefix).normalize() != Path(buildExecPrefix).normalize()
    val libraryDirectory = when {
        directory.isEmpty() -> null
        !relocated -> Path(directory)
        else -> Path(directory).normalize().takeIf { it.startsWith(Path(buildExecPrefix).normalize()) }?.let {
            Path(baseExecPrefix).resolve(Path(buildExecPrefix).normalize().relativize(it))
        }
    }
    val inPrefixLib = if (baseExecPrefix.isEmpty()) null else Path(baseExecPrefix, "lib")
    return listOfNotNull(libraryDirectory, inPrefixLib).map { it.resolve(soname) }.firstOrNull { it.isRegularFile() }
}

/**
 * The result of [loadPythonLibrary].
 *
 * @property failure the failure to load the library, e.g. of another architecture, or denied by the JVM,
 * kept to tell why Jep cannot load its native library, if it cannot.
 * @property localSymbols whether the library is loaded with local symbols, to be made global once Python runs,
 * see [EmbeddedPythonStarter].
 */
internal class PythonLibraryLoading(
    val failure: Throwable? = null,
    val localSymbols: Boolean = false
)

/**
 * Loads the shared `libpython` [library] of the Python on Linux, before Jep loads its native library, which depends on it,
 * when the dynamic loader would not find it, see [loaderFinds].
 *
 * The dynamic loader finds the `libpython3.X.so.1.0` of the native library of Jep by its soname
 * in the directories of `LD_LIBRARY_PATH`, read when the JVM starts, of `ld.so.cache`, and the system ones,
 * so not the one of a Python installed elsewhere, e.g. by uv, pyenv, or `actions/setup-python`;
 * once loaded by its path, the loader takes the library of the same soname instead.
 *
 * Its symbols must be global as well, as a C extension module on Linux is not linked to `libpython`,
 * e.g. the ones of numpy, or of the standard library of most Pythons, `_ctypes` among them,
 * and would fail to import with `undefined symbol: PyExc_ValueError`.
 * Jep makes `libpython` global itself, but finds it by its file name, which the loader cannot find, like its soname.
 * The JVM loads a native library with local symbols (`RTLD_LOCAL`),
 * so on Java 22 or newer, the library is loaded with `dlopen` and `RTLD_GLOBAL`, called with the foreign linker,
 * before CPython starts, also for a C extension imported at its startup, e.g. by a `.pth` file of the `site-packages`.
 * On Java 17 to 21, it is loaded with [System.load], and made global in the first interpreter,
 * so only after the startup of CPython.
 * The [load] of the library is a test seam of [System.load].
 */
internal fun loadPythonLibrary(
    library: Path,
    load: (String) -> Unit = System::load
): PythonLibraryLoading {
    if (foreignLinkerEnabled() && loadGlobally(library)) return PythonLibraryLoading()
    return try {
        load(library.toString())
        PythonLibraryLoading(localSymbols = true)
    } catch (e: UnsatisfiedLinkError) {
        PythonLibraryLoading(failure = e)
    } catch (e: RuntimeException) {
        // e.g. an IllegalCallerException with --illegal-native-access=deny,
        // which then fails loading the native library of Jep as well, reported as a failure of the startup
        PythonLibraryLoading(failure = e)
    }
}

/**
 * Whether the dynamic loader finds the [library] by its soname, its file name, as the same file,
 * so that it needs not be loaded by its path, see [loadPythonLibrary], e.g. of a Python of the system.
 *
 * Like the loader, it takes the first file of the soname in the directories of the [libraryPath], `LD_LIBRARY_PATH`,
 * then in the [cache] of the loader, `/etc/ld.so.cache`, which lists the full paths of the libraries,
 * of the directories configured in `/etc/ld.so.conf`, read only if needed, and then in the system directories.
 * It does not know the `RUNPATH` of the native library of Jep, e.g. of conda,
 * with which the library would be loaded when not needed.
 */
internal fun loaderFinds(
    library: Path,
    libraryPath: String? = System.getenv("LD_LIBRARY_PATH"),
    cache: () -> ByteArray? = ::readLoaderCache
): Boolean {
    val soname = library.fileName.toString()
    val candidates = libraryPath.orEmpty().split(':').asSequence().filter { it.isNotEmpty() }.map { Path(it, soname) } +
        sequence { yieldAll(cachedLibraries(cache(), soname)) } +
        SYSTEM_LIBRARY_DIRECTORIES.asSequence().map { Path(it, soname) }
    val found = candidates.firstOrNull { it.isRegularFile() } ?: return false
    return try {
        found.isSameFileAs(library)
    } catch (_: IOException) {
        false
    }
}

/** The directories which the dynamic loader of glibc searches last, also without `ld.so.cache`. */
private val SYSTEM_LIBRARY_DIRECTORIES = listOf("/lib64", "/usr/lib64", "/lib", "/usr/lib")

/** The `/etc/ld.so.cache` of the dynamic loader, or `null` if it cannot be read, e.g. of musl, which has none. */
private fun readLoaderCache(): ByteArray? = try {
    Path("/etc/ld.so.cache").takeIf { it.isRegularFile() }?.readBytes()
} catch (_: IOException) {
    null
}

/** The paths of the libraries of the [soname] in the [cache] of the dynamic loader, its null-terminated strings. */
private fun cachedLibraries(cache: ByteArray?, soname: String): List<Path> {
    if (cache == null) return emptyList()
    // ISO-8859-1 maps each byte to a char, so that the indexes are the ones of the bytes
    val text = String(cache, Charsets.ISO_8859_1)
    val suffix = "/" + String(soname.toByteArray(filesystemCharset), Charsets.ISO_8859_1) + "\u0000"
    val paths = mutableListOf<Path>()
    var end = text.indexOf(suffix)
    while (end != -1) {
        val start = text.lastIndexOf('\u0000', end) + 1
        val bytes = text.substring(start, end + suffix.length - 1).toByteArray(Charsets.ISO_8859_1)
        String(bytes, filesystemCharset).takeIf { it.startsWith("/") }?.let { paths.add(Path(it)) }
        end = text.indexOf(suffix, end + 1)
    }
    return paths
}

/** `RTLD_LAZY | RTLD_GLOBAL` of glibc and musl. */
private const val RTLD_LAZY_GLOBAL = 0x101

/**
 * Loads the [library] with `dlopen` and `RTLD_GLOBAL`, called with the foreign linker of Java 22 or newer,
 * through reflection, as the library is compiled for Java 17.
 *
 * @return `true` if loaded, `false` if the foreign linker is not available, or `dlopen` fails.
 */
private fun loadGlobally(library: Path): Boolean = try {
    val arenaClass = Class.forName("java.lang.foreign.Arena")
    // of Java 22, as the foreign linker of Java 21 is a preview,
    // looked up before calling a restricted method, which would print a warning without --enable-native-access
    val allocateFrom = arenaClass.getMethod("allocateFrom", String::class.java)
    val linkerClass = Class.forName("java.lang.foreign.Linker")
    val segmentClass = Class.forName("java.lang.foreign.MemorySegment")
    val layoutClass = Class.forName("java.lang.foreign.MemoryLayout")
    val valueLayoutClass = Class.forName("java.lang.foreign.ValueLayout")
    val descriptorClass = Class.forName("java.lang.foreign.FunctionDescriptor")
    val optionClass = Class.forName("java.lang.foreign.Linker\$Option")
    val linker = linkerClass.getMethod("nativeLinker").invoke(null)
    val lookup = linkerClass.getMethod("defaultLookup").invoke(linker)
    val dlopen = (Class.forName("java.lang.foreign.SymbolLookup").getMethod("find", String::class.java)
        .invoke(lookup, "dlopen") as Optional<*>).orElse(null)
    if (dlopen == null) {
        false
    } else {
        val address = valueLayoutClass.getField("ADDRESS").get(null)
        val int = valueLayoutClass.getField("JAVA_INT").get(null)
        val arguments = java.lang.reflect.Array.newInstance(layoutClass, 2).also {
            java.lang.reflect.Array.set(it, 0, address)
            java.lang.reflect.Array.set(it, 1, int)
        }
        val descriptor = descriptorClass.getMethod("of", layoutClass, arguments.javaClass)
            .invoke(null, address, arguments)
        val noOptions = java.lang.reflect.Array.newInstance(optionClass, 0)
        val handle = linkerClass.getMethod("downcallHandle", segmentClass, descriptorClass, noOptions.javaClass)
            .invoke(linker, dlopen, descriptor, noOptions) as MethodHandle
        (arenaClass.getMethod("ofConfined").invoke(null) as AutoCloseable).use { arena ->
            val path = allocateFrom.invoke(arena, library.toString())
            val result = handle.invokeWithArguments(path, RTLD_LAZY_GLOBAL)
            segmentClass.getMethod("address").invoke(result) != 0L
        }
    }
} catch (_: ReflectiveOperationException) {
    false
} catch (_: RuntimeException) {
    false
}
