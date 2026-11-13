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
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSameFileAs
import kotlin.io.path.listDirectoryEntries
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
 */
internal fun pythonLibrary(directory: String, soname: String): Path? {
    if (directory.isEmpty() || soname.isEmpty() || soname.endsWith(".a")) return null
    return Path(directory, soname).takeIf { it.isRegularFile() }
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
 * unless the dynamic loader finds it, with its symbols made global by Jep, see [jepLoadsGlobally].
 *
 * The dynamic loader finds the `libpython3.X.so.1.0` of the native library of Jep by its soname
 * in the directories of `LD_LIBRARY_PATH`, read when the JVM starts, of `ld.so.cache`, and the system ones,
 * so not the one of a Python installed elsewhere, e.g. by uv, pyenv, or `actions/setup-python`;
 * once loaded by its path, the loader takes the library of the same soname instead.
 *
 * Its symbols must be global as well, as a C extension module on Linux is not linked to `libpython`,
 * e.g. the ones of numpy, or of the standard library of most Pythons, `_ctypes` among them,
 * and would fail to import with `undefined symbol: PyExc_ValueError`.
 * Jep makes `libpython` global itself, but finds it by the name of its development symlink,
 * which the loader cannot find, like its soname, or which is missing, also of a Python of the system.
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
 * Whether Jep loads the shared `libpython` [library] with its symbols global, so that it needs not be loaded by its path,
 * see [loadPythonLibrary], e.g. of a Python of the system with its development package, e.g. `libpython3.13-dev`.
 *
 * The dynamic loader must find the [library] by its soname, its file name, for the native library of Jep,
 * and Jep makes it global with `dlopen` of the [developmentName], see [jepLibraryName], e.g. `libpython3.13.so`,
 * the development symlink, missing when its development package is not installed, e.g. removed after building Jep,
 * so that this `dlopen` fails, and the symbols stay local, as they always do with a static `libpython3.X.a`, or none;
 * [finds] tells whether the loader finds the [library] by a name.
 */
internal fun jepLoadsGlobally(
    library: Path,
    developmentName: String,
    finds: (name: String) -> Boolean = loaderFinder(library)
): Boolean = jepOpensShared(developmentName) && finds(library.fileName.toString()) && finds(developmentName)

/**
 * Whether the [developmentName] of [jepLibraryName] is the one of a shared library, which `dlopen` can open,
 * not empty, nor the one of a static library, e.g. `libpython3.12.a`.
 */
private fun jepOpensShared(developmentName: String) = developmentName.isNotEmpty() && !developmentName.endsWith(".a")

/**
 * The name of the `libpython` which the native library of Jep, the [jepLibrary], opens with `dlopen`
 * to make its symbols global, see [jepLoadsGlobally], or empty if it cannot be told for certain.
 *
 * It is `PYTHON_LDLIBRARY`, compiled into the [jepLibrary] as a string, the base name of `get_libpython()`
 * of the build of Jep, `LDLIBRARY` of `sysconfig`, e.g. `libpython3.13.so`, which the files of the Python decide:
 * with a static `libpython3.12.a`, e.g. of conda-forge, the shared `libpython3.12.so` of the same stem,
 * unless the static one existed then, e.g. of the `libpython-static` package, removed since.
 * The [jepLibrary] has it among its null-terminated strings, as the only one of a `libpython` without a version suffix,
 * unlike the soname it needs, e.g. `libpython3.13.so.1.0`.
 */
internal fun jepLibraryName(jepLibrary: Path): String {
    val content = try {
        String(jepLibrary.readBytes(), Charsets.ISO_8859_1)
    } catch (_: IOException) {
        return ""
    }
    return JEP_LIBRARY_NAME.findAll(content).map { it.value }.toSet().singleOrNull() ?: ""
}

/** A null-terminated string of the name of a `libpython`, preceded by the end of another one, see [jepLibraryName]. */
private val JEP_LIBRARY_NAME = Regex("(?<=\u0000)libpython[^\u0000/]*\\.(?:so|a)(?=\u0000)")

/** [loaderFinds] of the [library] by a name, reading the cache of the loader at most once for all the names. */
private fun loaderFinder(library: Path): (name: String) -> Boolean {
    val cache by lazy(::readLoaderCache)
    return { name -> loaderFinds(library, name, cache = { cache }) }
}

/**
 * How to have the symbols of the [library] made global by Jep, before the startup of Python,
 * when [loadPythonLibrary] cannot, or only after the startup, on Java 17 to 21:
 * the dynamic loader has to find the [library] by its soname, and by its [developmentName], see [jepLoadsGlobally].
 * If Jep cannot, without a [developmentName] of a shared library, the [library] has to be preloaded with global symbols.
 */
internal fun pythonLibraryGlobalAdvice(library: Path, developmentName: String): String =
    if (!jepOpensShared(developmentName)) {
        "put $library on LD_PRELOAD before the JVM starts, as Jep does not make its symbols global with this Python"
    } else {
        "put ${library.parent} on LD_LIBRARY_PATH before the JVM starts, " +
            "unless it is a directory the dynamic loader searches anyway, and if $developmentName is missing there, " +
            "install the development package of this Python which provides it, e.g. libpython3.X-dev on Debian or Ubuntu"
    }

/**
 * Whether the dynamic loader finds the [library] by the [name], its soname by default, as the same file,
 * see [jepLoadsGlobally].
 *
 * Like the loader of glibc, it takes the first file of the name in the directories of the [libraryPath], `LD_LIBRARY_PATH`,
 * a relative one in the [workingDirectory], by default the one of the process, not changed by setting `user.dir`,
 * and then in the [cache] of the loader, `/etc/ld.so.cache`, which lists the full paths of the libraries,
 * of the directories configured in `/etc/ld.so.conf`, read only if needed.
 *
 * Where the loader might take another file, it tells that it does not find the [library],
 * which costs only loading it when not needed, see [loadPythonLibrary]:
 * - an empty entry of `LD_LIBRARY_PATH`, leading, trailing, or of `::`, with a file of the [name] in the [workingDirectory],
 *   as glibc takes the entry for the working directory, but musl skips it;
 * - a `;` in `LD_LIBRARY_PATH`, at which glibc splits it as well, but musl does not;
 * - a `$` in `LD_LIBRARY_PATH`, of `$ORIGIN`, `$LIB` or `$PLATFORM`, which glibc expands;
 * - a path which is not valid in the JVM, e.g. not in the encoding of the file system;
 * - a file of the [name] in a subdirectory of `glibc-hwcaps` of a searched directory, also in the cache,
 *   which glibc takes first, if the CPU supports its level, e.g. `x86-64-v3`;
 * - different files of the [name] in the cache, whose order is not the one of their priority to the loader;
 * - a file of the [name] only in a directory which the loader searches last by default,
 *   as they differ between distributions, e.g. `/usr/lib/x86_64-linux-gnu` of Debian, `/usr/lib64` of Fedora.
 *
 * It does not know the other rules of the loaders, with which it might tell that it finds a library it would not:
 * the `RUNPATH` of the native library of Jep, e.g. of conda, with which the library would be loaded when not needed,
 * the legacy hwcaps subdirectories of glibc before 2.37, e.g. `tls` or `haswell`,
 * and the default directories of musl, with `/usr/local/lib`, or the ones of its `/etc/ld-musl-*.path`,
 * as telling musl from glibc is not certain either, e.g. the loader of musl can be installed on a glibc system.
 * Each matters only with another file of the same [name] in such a directory, loaded instead.
 */
internal fun loaderFinds(
    library: Path,
    name: String = library.fileName.toString(),
    libraryPath: String? = System.getenv("LD_LIBRARY_PATH"),
    cache: () -> ByteArray? = ::readLoaderCache,
    workingDirectory: Path = Path("")
): Boolean = try {
    loaderFindsValidPaths(library, name, libraryPath, cache, workingDirectory)
} catch (_: InvalidPathException) {
    false
}

private fun loaderFindsValidPaths(
    library: Path,
    name: String,
    libraryPath: String?,
    cache: () -> ByteArray?,
    workingDirectory: Path
): Boolean {
    if (libraryPath != null && (';' in libraryPath || '$' in libraryPath)) return false
    // an empty LD_LIBRARY_PATH has no entries, rather than an empty one
    val entries = if (libraryPath.isNullOrEmpty()) emptyList() else libraryPath.split(':')
    for (entry in entries) {
        val directory = workingDirectory.resolve(entry)
        if (hasHwcapsLibrary(directory, name)) return false
        val candidate = directory.resolve(name)
        if (candidate.isRegularFile()) return entry.isNotEmpty() && isSameFile(candidate, library)
    }
    val cached = cachedLibraries(cache(), name).filter { it.isRegularFile() }
    return cached.isNotEmpty() &&
        cached.none { path -> path.any { it.toString() == "glibc-hwcaps" } } &&
        cached.all { isSameFile(it, library) }
}

/** Whether a file of the [name] is in a subdirectory of `glibc-hwcaps` of the [directory], or it cannot be told. */
private fun hasHwcapsLibrary(directory: Path, name: String): Boolean {
    val hwcaps = directory.resolve("glibc-hwcaps")
    if (!hwcaps.isDirectory()) return false
    return try {
        hwcaps.listDirectoryEntries().any { it.resolve(name).exists() }
    } catch (_: IOException) {
        true
    }
}

private fun isSameFile(file: Path, other: Path): Boolean = try {
    file.isSameFileAs(other)
} catch (_: IOException) {
    false
}

/** The `/etc/ld.so.cache` of the dynamic loader, or `null` if it cannot be read, e.g. of musl, which has none. */
private fun readLoaderCache(): ByteArray? = try {
    Path("/etc/ld.so.cache").takeIf { it.isRegularFile() }?.readBytes()
} catch (_: IOException) {
    null
}

/** The paths of the libraries of the [name] in the [cache] of the dynamic loader, its null-terminated strings. */
private fun cachedLibraries(cache: ByteArray?, name: String): List<Path> {
    if (cache == null) return emptyList()
    // ISO-8859-1 maps each byte to a char, so that the indexes are the ones of the bytes
    val text = String(cache, Charsets.ISO_8859_1)
    val suffix = "/" + String(name.toByteArray(filesystemCharset), Charsets.ISO_8859_1) + "\u0000"
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
