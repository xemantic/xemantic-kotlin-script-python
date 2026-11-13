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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
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
 * or `null` if there is none.
 *
 * A Python linked to `libpython` statically has `libpython3.X.a` as its soname,
 * but might ship the shared library as well, e.g. conda-forge, without the static one,
 * so that the native library of Jep links to the shared one:
 * it is taken if the sonames [jepNeeded] by the native library of Jep, see [readElfDynamic], read only for a static [soname],
 * have exactly one of the version and ABI of the [soname], e.g. `libpython3.12.so.1.0`, and it is in the [directory].
 * A shared [soname] is not taken if the native library of Jep needs another `libpython`, of a Jep built for another Python,
 * which would not load the one of the [soname] anyway.
 */
internal fun pythonLibrary(
    directory: String,
    soname: String,
    jepNeeded: () -> List<String>? = { null }
): Path? {
    if (directory.isEmpty() || soname.isEmpty()) return null
    val needed = jepNeeded()
    val name = if (soname.endsWith(".a")) {
        val stem = soname.removeSuffix(".a")
        needed?.filter { (it == "$stem.so" || it.startsWith("$stem.so.")) && '/' !in it }?.singleOrNull()
            ?: return null
    } else {
        if (needed != null && needed.any { it.startsWith("libpython") } && soname !in needed) return null
        soname
    }
    return Path(directory, name).takeIf { it.isRegularFile() }
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
 * The [jepDynamic] section of the native library of Jep must have no search path of its own, see [ElfDynamic.searchPath],
 * with which the loader might take another file than [finds] tells.
 * The [jepDynamic] and the [developmentName] are read only if the loader finds the [library] by its soname,
 * which usually decides, and the [developmentName] only after the [jepDynamic], as it reads the whole native library.
 */
internal fun jepLoadsGlobally(
    library: Path,
    jepDynamic: () -> ElfDynamic?,
    developmentName: () -> String,
    finds: (name: String) -> Boolean = loaderFinder(library)
): Boolean {
    if (!finds(library.fileName.toString()) || jepDynamic()?.searchPath != false) return false
    val name = developmentName()
    return jepOpensShared(name) && finds(name)
}

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

/**
 * The dynamic section of an ELF library, see [readElfDynamic].
 *
 * @property needed the sonames of the libraries it needs, of its `DT_NEEDED` entries, in their order.
 * @property searchPath whether it has a search path of its own, a `DT_RPATH`, which the dynamic loader searches
 * even before `LD_LIBRARY_PATH`, or a `DT_RUNPATH`, searched before `ld.so.cache`, e.g. of a conda build.
 * @property searchDirectories the entries of its `DT_RPATH` and `DT_RUNPATH`, in this order, as they are.
 */
internal class ElfDynamic(
    val needed: List<String>,
    val searchPath: Boolean,
    val searchDirectories: List<String> = emptyList()
)

/**
 * The dynamic section of the ELF [library], or `null` if it cannot be read, e.g. of a file which is not an ELF library.
 *
 * It is parsed like the dynamic loader does, from the program headers, not the section headers, which can be stripped:
 * the `PT_DYNAMIC` segment, and its string table `DT_STRTAB`, a virtual address, mapped to its offset in the file
 * by the `PT_LOAD` segment which contains it.
 * Only the few parts needed are read, not the whole library.
 */
internal fun readElfDynamic(library: Path): ElfDynamic? = try {
    FileChannel.open(library).use { readElfDynamic(it) }
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    // e.g. a BufferUnderflowException, or an IllegalArgumentException of a negative size, of a malformed file
    null
}

private fun readElfDynamic(channel: FileChannel): ElfDynamic? {
    val identification = channel.readAt(0, 16, ByteOrder.LITTLE_ENDIAN) ?: return null
    if (identification.getInt(0) != 0x464c457f) return null
    val is64 = when (identification.get(4).toInt()) {
        1 -> false
        2 -> true
        else -> return null
    }
    val order = when (identification.get(5).toInt()) {
        1 -> ByteOrder.LITTLE_ENDIAN
        2 -> ByteOrder.BIG_ENDIAN
        else -> return null
    }
    val header = channel.readAt(0, if (is64) 64 else 52, order) ?: return null
    fun ByteBuffer.word(at: Int): Long = if (is64) getLong(at) else getInt(at).toUInt().toLong()
    val programHeaderOffset = header.word(if (is64) 32 else 28)
    val programHeaderSize = header.getShort(if (is64) 54 else 42).toUShort().toInt()
    val programHeaderCount = header.getShort(if (is64) 56 else 44).toUShort().toInt()
    if (programHeaderSize < (if (is64) 56 else 32)) return null
    val programHeadersSize = (programHeaderSize.toLong() * programHeaderCount).toIntSize() ?: return null
    val programHeaders = channel.readAt(programHeaderOffset, programHeadersSize, order) ?: return null
    // offset, virtual address, size in the file
    val loads = mutableListOf<Triple<Long, Long, Long>>()
    var dynamic: Pair<Long, Long>? = null
    for (index in 0 until programHeaderCount) {
        val at = index * programHeaderSize
        val offset = programHeaders.word(at + if (is64) 8 else 4)
        val address = programHeaders.word(at + if (is64) 16 else 8)
        val size = programHeaders.word(at + if (is64) 32 else 16)
        when (programHeaders.getInt(at)) {
            PT_LOAD -> loads += Triple(offset, address, size)
            PT_DYNAMIC -> dynamic = offset to size
        }
    }
    val (dynamicOffset, dynamicSize) = dynamic ?: return null
    val entries = channel.readAt(dynamicOffset, dynamicSize.toIntSize() ?: return null, order) ?: return null
    val entrySize = if (is64) 16 else 8
    val neededOffsets = mutableListOf<Long>()
    var rpathOffset: Long? = null
    var runpathOffset: Long? = null
    var stringTableAddress: Long? = null
    var stringTableSize: Long? = null
    for (at in 0..entries.limit() - entrySize step entrySize) {
        val value = entries.word(at + entrySize / 2)
        when (entries.word(at)) {
            DT_NULL -> break
            DT_NEEDED -> if (neededOffsets.size < MAX_ELF_NEEDED) neededOffsets += value else return null
            DT_STRTAB -> stringTableAddress = value
            DT_STRSZ -> stringTableSize = value
            DT_RPATH -> rpathOffset = value
            DT_RUNPATH -> runpathOffset = value
        }
    }
    val address = stringTableAddress ?: return null
    val size = stringTableSize?.toIntSize() ?: return null
    val load = loads.firstOrNull { (_, start, length) -> address >= start && address - start < length } ?: return null
    val strings = channel.readAt(load.first + (address - load.second), size, order) ?: return null
    fun string(offset: Long): String? {
        if (offset < 0 || offset >= size) return null
        val start = offset.toInt()
        val end = (start until minOf(size, start + MAX_ELF_STRING_SIZE + 1)).firstOrNull { strings.get(it).toInt() == 0 }
            ?: return null
        return String(ByteArray(end - start) { strings.get(start + it) }, Charsets.ISO_8859_1)
    }
    val needed = neededOffsets.map { string(it) ?: return null }
    val searchDirectories = listOfNotNull(rpathOffset, runpathOffset).flatMap { (string(it) ?: return null).split(':') }
    return ElfDynamic(needed, searchPath = rpathOffset != null || runpathOffset != null, searchDirectories)
}

/** The [size] bytes at the [offset] of the [FileChannel], or `null` if the file ends before. */
private fun FileChannel.readAt(offset: Long, size: Int, order: ByteOrder): ByteBuffer? {
    if (offset < 0 || size < 0) return null
    val buffer = ByteBuffer.allocate(size)
    while (buffer.hasRemaining()) {
        if (read(buffer, offset + buffer.position()) < 0) return null
    }
    return buffer.flip().order(order)
}

/** The size as an [Int], or `null` if it is beyond the [MAX_ELF_PART_SIZE], of a malformed library. */
private fun Long.toIntSize(): Int? = takeIf { it in 0..MAX_ELF_PART_SIZE }?.toInt()

/** The largest part of an ELF library read by [readElfDynamic]. */
private const val MAX_ELF_PART_SIZE = 16L * 1024 * 1024

/** The most libraries needed, and the longest string, which [readElfDynamic] reads, beyond those of any sane library. */
private const val MAX_ELF_NEEDED = 1024
private const val MAX_ELF_STRING_SIZE = 4096

private const val PT_LOAD = 1
private const val PT_DYNAMIC = 2
private const val DT_NULL = 0L
private const val DT_NEEDED = 1L
private const val DT_STRTAB = 5L
private const val DT_STRSZ = 10L
private const val DT_RPATH = 15L
private const val DT_RUNPATH = 29L

/** [loaderFinds] of the [library] by a name, reading the cache of the loader at most once for all the names. */
private fun loaderFinder(library: Path): (name: String) -> Boolean {
    val cache by lazy(::readLoaderCache)
    val jvmDirectories by lazy { jvmSearchDirectories() }
    return { name -> loaderFinds(library, name, cache = { cache }, jvmDirectories = { jvmDirectories }) }
}

/**
 * How to have the symbols of the [library] made global by Jep, before the startup of Python,
 * when [loadPythonLibrary] cannot, or only after the startup, on Java 17 to 21:
 * the dynamic loader has to find the [library] by its soname, and by its [developmentName], see [jepLoadsGlobally].
 * If Jep cannot, with the [developmentName] of a static library, or might not, with an unknown, empty one,
 * the [library] has to be loaded with global symbols before Python starts:
 * by [loadPythonLibrary] with the [foreignLinker] on Java 22 or newer, the [javaVersion],
 * or by the loader itself, with `LD_PRELOAD`, which the processes started by the Python code inherit as well,
 * e.g. another Python, which would then have the symbols of this `libpython` instead of its own,
 * unlike the ones of [runProcess].
 */
internal fun pythonLibraryGlobalAdvice(
    library: Path,
    developmentName: String,
    javaVersion: Int = Runtime.version().feature(),
    foreignLinker: Boolean = foreignLinkerEnabled()
): String =
    if (!jepOpensShared(developmentName)) {
        val reason = if (developmentName.isEmpty()) {
            "if Jep does not make its symbols global with this Python, which its native library does not tell for certain"
        } else {
            "as Jep does not make its symbols global with this Python"
        }
        val beforeStartup = "$library with global symbols before Python starts"
        val withoutPreload = when {
            javaVersion < 22 -> "run the JVM on Java 22 or newer with --enable-native-access=ALL-UNNAMED, " +
                "which loads $beforeStartup"
            !foreignLinker -> "run the JVM without -D$NO_FOREIGN_LINKER_PROPERTY=true, " +
                "with which the foreign linker loads $beforeStartup"
            else -> "run the JVM with --enable-native-access=ALL-UNNAMED, as the foreign linker failed to load $beforeStartup"
        }
        "$withoutPreload, or put it on LD_PRELOAD, $reason, " +
            "set only for the launch of the JVM, e.g. LD_PRELOAD=$library java ..., " +
            "as the processes started by the Python code inherit it, e.g. with subprocess, " +
            "unlike the ones started by this library"
    } else {
        "put ${library.parent} on LD_LIBRARY_PATH before the JVM starts, " +
            "unless it is a directory the dynamic loader searches anyway, and if $developmentName is missing there, " +
            "install the development package of this Python which provides it, e.g. libpython3.X-dev on Debian or Ubuntu"
    }

/**
 * Whether the dynamic loader finds the [library] by the [name], its soname by default, as the same file,
 * see [jepLoadsGlobally].
 *
 * Like the loader of glibc, it takes the first file of the name in the [jvmDirectories], see [jvmSearchDirectories],
 * in the directories of the [libraryPath], `LD_LIBRARY_PATH`,
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
 * - [jvmDirectories] which cannot be told;
 * - a file of the [name] in a subdirectory of `glibc-hwcaps` of a searched directory, also in the cache,
 *   which glibc takes first, if the CPU supports its level, e.g. `x86-64-v3`;
 * - different files of the [name] in the cache, whose order is not the one of their priority to the loader;
 * - a file of the [name] only in a directory which the loader searches last by default,
 *   as they differ between distributions, e.g. `/usr/lib/x86_64-linux-gnu` of Debian, `/usr/lib64` of Fedora.
 *
 * It does not know the other rules of the loaders, with which it might tell that it finds a library it would not:
 * the `RPATH` or `RUNPATH` of the library which needs the [name], which [jepLoadsGlobally] checks itself,
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
    workingDirectory: Path = Path(""),
    jvmDirectories: () -> List<Path>? = ::jvmSearchDirectories
): Boolean = try {
    loaderFindsValidPaths(library, name, libraryPath, cache, workingDirectory, jvmDirectories)
} catch (_: InvalidPathException) {
    false
}

private fun loaderFindsValidPaths(
    library: Path,
    name: String,
    libraryPath: String?,
    cache: () -> ByteArray?,
    workingDirectory: Path,
    jvmDirectories: () -> List<Path>?
): Boolean {
    for (directory in jvmDirectories() ?: return false) {
        val candidate = directory.resolve(name)
        if (candidate.exists()) return isSameFile(candidate, library)
    }
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

/**
 * The directories which glibc searches for a library needed, or opened, by the native library of Jep,
 * before `LD_LIBRARY_PATH`, or `null` if they cannot be told: the `RPATH` of the libraries which loaded it,
 * those of the JVM, e.g. `$ORIGIN` of `libjli.so`, and of the JVM [executable], e.g. `$ORIGIN:$ORIGIN/../lib` of `java`.
 * They are taken as the ones of the [executable], with its `RUNPATH` as well, which glibc would ignore,
 * and the `lib` directory of the [javaHome], as the libraries of the JVM in between are not known.
 * A `RUNPATH`, or `RPATH`, which is not absolute, or has a `$` other than of `$ORIGIN`, cannot be told.
 * Off Linux, there are none.
 */
internal fun jvmSearchDirectories(
    executable: Path = Path("/proc/self/exe"),
    javaHome: String = System.getProperty("java.home")
): List<Path>? {
    if (!System.getProperty("os.name").startsWith("Linux")) return emptyList()
    val file = try {
        executable.toRealPath()
    } catch (_: IOException) {
        return null
    }
    val dynamic = readElfDynamic(file) ?: return null
    val origin = file.parent.toString()
    return try {
        dynamic.searchDirectories.map { entry ->
            val expanded = entry.replace("\${ORIGIN}", origin).replace("\$ORIGIN", origin)
            if ('$' in expanded || !expanded.startsWith("/")) return null
            Path(expanded)
        } + listOf(Path(javaHome, "lib"))
    } catch (_: InvalidPathException) {
        null
    }
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
