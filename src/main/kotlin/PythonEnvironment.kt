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

import jep.Interpreter
import jep.MainInterpreter
import jep.PyConfig
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The Python environment of all the [Python] interpreters of the JVM,
 * which Jep allows to initialize only once.
 *
 * Its Python executable is the one of:
 * 1. the [pythonExecutable] set before the first use of [Python],
 * 2. the virtual environment of the `VIRTUAL_ENV` environment variable,
 * 3. `python3` on the `PATH`, `python3.exe` on Windows, found only in its absolute directories, not in the working directory.
 *
 * The `jep` package must be installed in it, in the version of the `org.ninia:jep` library,
 * into a virtual environment, if the Python is externally managed,
 * and the Python code imports the modules from the same paths as this executable,
 * e.g. the `site-packages` of a virtual environment, but not the ones of its base Python.
 */
public object PythonEnvironment {

    private var initialized = false

    /**
     * The Python executable to use, instead of the one resolved by default.
     *
     * A bare name, e.g. `python3.12`, is looked up in the absolute directories of the `PATH`, like the default `python3`.
     *
     * @throws IllegalStateException if set after the first use of [Python].
     */
    public var pythonExecutable: Path? = null
        @Synchronized set(value) {
            checkNotInitialized()
            field = value
        }

    /**
     * The directory, where the dependencies declared by the Python code are installed,
     * importable once the code of any [Python] declares its dependencies.
     *
     * By default, it is the directory of the `xemantic.kotlin.script.python.dependencyDirectory` system property,
     * if set, or otherwise a directory of the Python environment in `~/.cache/xemantic-kotlin-script-python`,
     * resolved on the first use of [Python].
     *
     * @throws IllegalStateException if set after the first use of [Python].
     */
    public var dependencyDirectory: Path? = null
        @Synchronized set(value) {
            checkNotInitialized()
            field = value
        }

    /** The absolute path of the Python executable, resolved on the first use of [Python]. */
    internal lateinit var executable: String
        private set

    /** The starter of the interpreters of the embedded CPython, resolved on the first use of [Python]. */
    internal lateinit var starter: EmbeddedPythonStarter
        private set

    @Synchronized
    internal fun initialize() {
        if (initialized) return
        val python = resolvePythonExecutable()
        val probe = probePython(python)
        val (executable, cacheTag, prefix, jepLocation) = probe
        val library = jepLibrary(Path(jepLocation), python, probe.externallyManaged)
        MainInterpreter.setJepLibraryPath(library.toString())
        MainInterpreter.setInitParams(embeddedPythonConfig(executable))
        this.executable = executable
        starter = EmbeddedPythonStarter(executable, probe.error)
        if (dependencyDirectory == null) {
            dependencyDirectory = System.getProperty(DEPENDENCY_DIRECTORY_PROPERTY)?.let { Path(it) }
        }
        if (dependencyDirectory == null) {
            val prefixHash = MessageDigest.getInstance("SHA-256")
                .digest(prefix.toByteArray())
                .copyOf(6)
                .toHexString()
            dependencyDirectory = Path(
                System.getProperty("user.home"), ".cache", "xemantic-kotlin-script-python", "$cacheTag-$prefixHash"
            )
        }
        initialized = true
    }

    /**
     * The version of the `org.ninia:jep` library, which the `jep` package must match,
     * read from the `pom.properties` of its jar, as its manifest has no version,
     * or `null` if the jar does not have it, e.g. repackaged.
     */
    internal val jepVersion: String? by lazy {
        MainInterpreter::class.java.getResourceAsStream("/META-INF/maven/org.ninia/jep/pom.properties")
            ?.let { readJepVersion(it) }
    }

    /**
     * The native library of Jep in the [jepDirectory], the directory of the `jep` package of the [python],
     * which is [externallyManaged] or not.
     *
     * @throws IllegalStateException if there is none, or the [jepDirectory] cannot be listed.
     */
    internal fun jepLibrary(jepDirectory: Path, python: String, externallyManaged: Boolean): Path {
        val entries = try {
            jepDirectory.listDirectoryEntries()
        } catch (e: IOException) {
            throw IllegalStateException(
                "Cannot list the directory of the jep package of $python: $jepDirectory.\n" +
                    jepInstallAdvice(python, externallyManaged, reinstall = true),
                e
            )
        }
        return entries.firstOrNull {
            it.name.startsWith("libjep.") || it.name == "jep.dll"
        } ?: throw IllegalStateException(
            "No native Jep library in $jepDirectory, the jep package of $python is broken.\n" +
                jepInstallAdvice(python, externallyManaged, reinstall = true)
        )
    }

    /**
     * The configuration of the embedded CPython of the Python [executable].
     *
     * Its program name is the [executable], as the embedded CPython finds the `pyvenv.cfg` of a virtual environment
     * next to its program, otherwise it would search the `site-packages` of the base Python as well.
     *
     * It runs in the UTF-8 mode, like the Python subprocesses, see [runProcess],
     * as otherwise it would use the encoding of the locale of the JVM, e.g. cp1252 on Windows, or ASCII in the C locale,
     * for its standard streams and as the default encoding of `open`,
     * and fail with a `UnicodeEncodeError` printing, or a `UnicodeDecodeError` reading a UTF-8 file, non-ASCII text.
     * Jep has no setter of the UTF-8 mode, but its command line option `-X utf8` enables it,
     * also over a `PYTHONUTF8=0` environment variable,
     * as Jep 4.3 sets the argv before the other values, and CPython reads its options then,
     * before decoding any other value; [checkUtf8Mode] tells if a version of Jep does not.
     */
    internal fun embeddedPythonConfig(executable: String): PyConfig = PyConfig.python()
        .setParseArgv(true)
        .setArgv(executable, "-X", "utf8")
        .setProgramName(executable)

    /**
     * Checks that the embedded CPython of the [interpreter] runs in UTF-8 mode, see [embeddedPythonConfig].
     *
     * @throws IllegalStateException if it does not.
     */
    internal fun checkUtf8Mode(interpreter: Interpreter) {
        val utf8Mode = interpreter.getValue("__import__('sys').flags.utf8_mode")
        check(utf8Mode == 1L) {
            "The embedded Python does not run in UTF-8 mode (sys.flags.utf8_mode is $utf8Mode), " +
                "the version of Jep might not support enabling it with -X utf8 in PyConfig.setArgv"
        }
    }

    /**
     * Asks the [python] executable about itself.
     *
     * The standard error of a successful probe, e.g. a warning, is kept as [PythonProbe.error].
     *
     * @throws IllegalStateException if it cannot be run, or fails, has no `jep` package,
     * does not write the values of the probe, or cannot tell the path of its executable,
     * or if its paths are not valid in the [filesystemCharset].
     */
    internal fun probePython(python: String): PythonProbe {
        val result = try {
            runProcess(probeCommand(python), mergeErrorStream = false)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Cannot run Python executable: $python", e)
        }
        // only the probe's own check blames jep, any other failure is the one of Python itself,
        // e.g. of a pyenv shim of a version which is not installed
        val jepMissing = result.exitCode == PROBE_JEP_MISSING_EXIT_CODE
        val jepMissingExternallyManaged = result.exitCode == PROBE_JEP_MISSING_EXTERNALLY_MANAGED_EXIT_CODE
        check(!jepMissing && !jepMissingExternallyManaged) {
            withOutput("Cannot find the jep package of $python.", result.failureOutput) +
                "\n" + jepInstallAdvice(python, externallyManaged = jepMissingExternallyManaged)
        }
        check(result.exitCode == 0) {
            withOutput("Cannot run Python $python, it failed with exit code ${result.exitCode}", result.failureOutput)
        }
        val parsed = try {
            parseProbeOutput(result.output)
        } catch (e: CharacterCodingException) {
            throw IllegalStateException(
                "The paths of Python $python are not valid in the file system encoding of the JVM, " +
                    "$filesystemCharset, run the JVM in a locale of the encoding of the file system, e.g. UTF-8",
                e
            )
        }
        val probe = checkNotNull(parsed) {
            withOutput(
                "Cannot read the values of the Python environment probe of $python from its output",
                result.failureOutput
            )
        }
        // the embedded CPython needs it to find the pyvenv.cfg of a virtual environment, and pip is run with it
        check(probe.executable.isNotEmpty()) {
            "Python cannot tell the path of its executable (sys.executable is empty): $python"
        }
        // limited, as it is kept for the lifetime of the JVM; a warning of Python at startup comes first
        return probe.copy(error = limitProbeError(result.error))
    }

    /** The [error] of the probe, limited to [PROBE_ERROR_LIMIT] characters, including the note of truncation. */
    private fun limitProbeError(error: String): String {
        if (error.length <= PROBE_ERROR_LIMIT) return error
        val end = (PROBE_ERROR_LIMIT - PROBE_ERROR_TRUNCATED.length).let {
            // not between the two chars of a surrogate pair
            if (error[it - 1].isHighSurrogate()) it - 1 else it
        }
        return error.substring(0, end) + PROBE_ERROR_TRUNCATED
    }

    private const val PROBE_ERROR_TRUNCATED = "\n[truncated]"

    private fun withOutput(headline: String, output: String) =
        if (output.isEmpty()) headline else "$headline\n$output"

    /**
     * The [PythonProbe] of the standard [output] of [probeCommand].
     *
     * The values are taken only from between the last [PROBE_BEGIN] and the following [PROBE_END] line,
     * as anything else run by Python, e.g. a `sitecustomize` module or a `.pth` file,
     * can write to the standard output as well, before or after them.
     *
     * @return the probe, or `null` if the [output] has no such values.
     * @throws CharacterCodingException if a value is not valid in the [filesystemCharset].
     */
    internal fun parseProbeOutput(output: String): PythonProbe? {
        // the output is trimmed, also of the newline before the first line
        val lines = "\n$output"
        val begin = lines.lastIndexOf("\n$PROBE_BEGIN\n").takeIf { it != -1 }?.let { it + PROBE_BEGIN.length + 2 }
        val end = begin?.let { lines.indexOf("\n$PROBE_END", it) }?.takeIf { it != -1 }
        val values = if (begin == null || end == null) null else lines.substring(begin, end).split(' ')
        if (values == null || values.size != 5) return null
        val (executable, cacheTag, prefix, jepLocation, externallyManaged) =
            values.map { it.hexToPath() ?: return null }
        return PythonProbe(executable, cacheTag, prefix, jepLocation, externallyManaged.isNotEmpty())
    }

    /**
     * The command of [probePython].
     *
     * It writes its values, separated by a space, between the [PROBE_BEGIN] and [PROBE_END] lines,
     * each as the hex digits of its bytes, of `os.fsencode`, the bytes of a path in the file system,
     * to be decoded with the [filesystemCharset] to the same path in the JVM:
     * as text, a path which is not valid UTF-8, e.g. `/home/josé` in ISO-8859-1,
     * would be surrogate-escaped by Python in UTF-8 mode, and could not be encoded to UTF-8,
     * while the JVM would decode a path of UTF-8 bytes to another one, in another encoding of its locale.
     * The last value tells whether the Python is externally managed (PEP 668), `1` if it is, empty if not:
     * a base Python, not a virtual environment, with an `EXTERNALLY-MANAGED` file in its standard library,
     * into which pip refuses to install.
     * Without the `jep` package, it exits with the [PROBE_JEP_MISSING_EXIT_CODE],
     * or the [PROBE_JEP_MISSING_EXTERNALLY_MANAGED_EXIT_CODE] if the Python is externally managed,
     * writing to the standard error the file of a `jep` module, if any, e.g. a `jep.py` shadowing the package.
     * The lines are written with an empty line before them, in case the standard output written before does not end with a newline,
     * and as bytes, without the newline translation of the text mode, e.g. on Windows,
     * to the original standard output, as `sys.stdout` might be replaced, e.g. by a `sitecustomize` module,
     * with a stream which has no `buffer`, or which does not write to the standard output at all.
     */
    internal fun probeCommand(python: String): List<String> = pythonCommand(
        python,
        """
        |import importlib.util, os, sys, sysconfig
        |externally_managed = sys.prefix == sys.base_prefix and os.path.isfile(
        |    os.path.join(sysconfig.get_path('stdlib'), 'EXTERNALLY-MANAGED')
        |)
        |jep_spec = importlib.util.find_spec('jep')
        |if jep_spec is None or not jep_spec.submodule_search_locations:
        |    if jep_spec is not None:
        |        print('jep is not the package, but the module:', jep_spec.origin, file=sys.stderr)
        |    sys.exit(
        |        $PROBE_JEP_MISSING_EXTERNALLY_MANAGED_EXIT_CODE if externally_managed else $PROBE_JEP_MISSING_EXIT_CODE
        |    )
        |values = [
        |    sys.executable,
        |    sys.implementation.cache_tag,
        |    sys.prefix,
        |    jep_spec.submodule_search_locations[0],
        |    '1' if externally_managed else '',
        |]
        |payload = ' '.join('' if value is None else os.fsencode(str(value)).hex() for value in values)
        |sys.stdout.flush()
        |sys.__stdout__.flush()
        |sys.__stdout__.buffer.write(('\n$PROBE_BEGIN\n' + payload + '\n$PROBE_END\n').encode('utf-8'))
        |sys.__stdout__.flush()
        |""".trimMargin()
    )

    /**
     * The exit code of [probeCommand] without the `jep` package,
     * unlike the ones of a failure of Python itself: 1 of an exception or of `sys.exit` with a message,
     * 2 of a wrong command line, 120 of a failure to flush the standard streams at exit,
     * 126 and 127 of a shell which cannot run the command, and above 128 of a signal.
     */
    internal const val PROBE_JEP_MISSING_EXIT_CODE = 83

    /** The exit code of [probeCommand] without the `jep` package in an externally managed Python. */
    internal const val PROBE_JEP_MISSING_EXTERNALLY_MANAGED_EXIT_CODE = 84

    /** The maximum length of the standard error of the probe kept as [PythonProbe.error]. */
    internal const val PROBE_ERROR_LIMIT = 8192

    internal const val PROBE_BEGIN = "--- xemantic-kotlin-script-python probe begin ---"

    internal const val PROBE_END = "--- xemantic-kotlin-script-python probe end ---"

    private const val DEPENDENCY_DIRECTORY_PROPERTY = "xemantic.kotlin.script.python.dependencyDirectory"

    private fun checkNotInitialized() {
        check(!initialized) {
            "The Python environment is already initialized"
        }
    }

    /**
     * The Python executable to probe: the [configured] one, or the one of the [virtualEnv], or `python3` on the [path].
     *
     * A bare name, `python3`, or a [configured] one, e.g. `python3.12`, is looked up by [PythonDependencies.findOnPath],
     * only in the absolute directories of the [path], and the absolute path of the executable is returned,
     * as `ProcessBuilder` would look up the name itself, also in the working directory,
     * for an empty entry of the `PATH`, or always on [windows],
     * where an executable there, e.g. among the files generated by an agent, would be embedded instead.
     * On [windows], it is looked up with the `.exe` extension, e.g. `python3.exe`, also for a [configured] `python3.exe`.
     *
     * @throws IllegalStateException if the name is not found on the [path].
     */
    internal fun resolvePythonExecutable(
        configured: Path? = pythonExecutable,
        virtualEnv: String? = System.getenv("VIRTUAL_ENV"),
        path: String? = System.getenv("PATH"),
        windows: Boolean = System.getProperty("os.name").startsWith("Windows")
    ): String {
        if (configured != null && (configured.root != null || configured.nameCount > 1)) return configured.toString()
        if (configured == null && virtualEnv != null) {
            val unix = Path(virtualEnv, "bin", "python")
            return (if (unix.exists()) unix else Path(virtualEnv, "Scripts", "python.exe")).toString()
        }
        val name = configured?.toString()?.let { if (windows) it.removeSuffix(".exe") else it } ?: "python3"
        return checkNotNull(PythonDependencies.findOnPath(name, path, windows)) {
            "Cannot find Python executable $name in the absolute directories of the PATH, " +
                "set VIRTUAL_ENV to a virtual environment with the jep package, " +
                "or PythonEnvironment.pythonExecutable to its Python"
        }
    }

}

/**
 * The command running the Python [code] with the [python] executable, and the [arguments] as `sys.argv[1:]`,
 * without the `sys.path` entry of the working directory, which Python adds implicitly for `-c`.
 *
 * With `-c`, as with `-m`, Python puts the working directory first on `sys.path`,
 * so that a `json.py`, or a `pip` package, there, e.g. among the files generated by an agent,
 * would replace the module the code imports, and run instead of it.
 * The code removes only this implicit entry, before any import,
 * and keeps the ones the user configured, e.g. with `PYTHONPATH` or a `.pth` file,
 * also when they point to the working directory, as the embedded Python sees them too:
 * `-P` would do the same, but it is unknown before Python 3.11,
 * and `-I` would also ignore all the `PYTHON*` environment variables, e.g. `PYTHONUTF8` and `PYTHONPATH`,
 * and the user `site-packages`, which the embedded Python sees.
 */
internal fun pythonCommand(python: String, code: String, vararg arguments: String): List<String> = listOf(
    python,
    "-c",
    "import sys\nif sys.path[:1] == ['']: del sys.path[0]\n$code",
    *arguments
)

/**
 * The version of the `org.ninia:jep` library in its `pom.properties`, read and closed,
 * or `null` if it cannot be read, e.g. is malformed.
 */
internal fun readJepVersion(properties: InputStream): String? = try {
    properties.use { Properties().apply { load(it) } }.getProperty("version")
} catch (_: Exception) {
    null
}

/**
 * How to install the `jep` package of the [jepVersion] for the [python] executable,
 * or to [reinstall] it, if it is broken.
 *
 * If the [python] is [externallyManaged] (PEP 668), e.g. the one of Homebrew or of most Linux distributions,
 * pip refuses to install into it, so jep is installed into a new virtual environment in the [userHome],
 * otherwise, e.g. in a virtual environment holding the other packages of the user, into the [python] itself,
 * also with uv, as a virtual environment of uv has no pip.
 * The paths are absolute, as `~` or `%USERPROFILE%` would not be expanded outside a shell, e.g. in an IDE,
 * and quoted for cmd, which PowerShell runs only with the `&` operator before them.
 * It also tells what building jep from its source needs, as PyPI has no wheels of it,
 * and on Debian/Ubuntu also the package of the venv module, without which a virtual environment cannot be created.
 */
internal fun jepInstallAdvice(
    python: String,
    externallyManaged: Boolean,
    reinstall: Boolean = false,
    jepVersion: String? = PythonEnvironment.jepVersion,
    javaHome: String? = System.getenv("JAVA_HOME"),
    userHome: String = System.getProperty("user.home"),
    windows: Boolean = System.getProperty("os.name").startsWith("Windows")
): String {
    fun quoted(path: String) = if (' ' in path) "\"$path\"" else path
    val separator = if (windows) "\\" else "/"
    val jep = if (jepVersion == null) "jep" else "jep==$jepVersion"
    val version = if (jepVersion == null) ", in the version of the org.ninia:jep library,"
    else " $jepVersion, the version of the org.ninia:jep library,"
    val install = if (externallyManaged) {
        val venv = listOf(userHome, ".venvs", "jep").joinToString(separator)
        val venvPython = if (windows) "$venv\\Scripts\\python.exe" else "$venv/bin/python"
        listOf(
            "This Python is externally managed, install jep$version into a virtual environment:",
            "  ${quoted(python)} -m venv ${quoted(venv)}",
            "  ${quoted(venvPython)} -m pip install $jep",
            "then set VIRTUAL_ENV to $venv, or PythonEnvironment.pythonExecutable to $venvPython."
        )
    } else listOf(
        (if (reinstall) "Reinstall" else "Install") + " jep$version with:",
        "  ${quoted(python)} -m pip install " + (if (reinstall) "--force-reinstall --no-cache-dir " else "") + jep,
        "or, without pip, e.g. in a virtual environment of uv:",
        "  uv pip install --python ${quoted(python)} " + (if (reinstall) "--reinstall --no-cache " else "") + jep
    )
    val build = if (windows) listOf(
        "jep is built from its source, needing Microsoft C++ Build Tools and a JDK, found through JAVA_HOME" +
            (if (javaHome == null) " (not set)." else ".")
    ) else listOf(
        "jep is built from its source, needing a C compiler, the Python headers, " +
            "and a JDK, found through JAVA_HOME, or javac on the PATH;",
        "on Debian/Ubuntu: apt install python3-dev" + (if (externallyManaged) " python3-venv" else "")
    )
    val powerShell = if (windows && install.any { "\"" in it }) {
        listOf("In PowerShell, prefix a quoted command with &.")
    } else emptyList()
    return (install + build + powerShell).joinToString("\n")
}

/**
 * The encoding of the file paths of the JVM, the one of the locale on POSIX, `sun.jnu.encoding`,
 * with which the JVM passes a path, also in the command line of a process, to the OS,
 * or UTF-8 on Windows, where the paths are UTF-16, and Python encodes them in UTF-8 with `os.fsencode`.
 */
internal val filesystemCharset: Charset =
    if (System.getProperty("os.name").startsWith("Windows")) Charsets.UTF_8
    else System.getProperty("sun.jnu.encoding")?.let { Charset.forName(it) } ?: Charset.defaultCharset()

/**
 * The Python expression of the [path] of the JVM, as the `str` of the same path in Python,
 * decoded by `os.fsdecode` from its bytes in the [filesystemCharset].
 *
 * Python in UTF-8 mode decodes a path of the file system from UTF-8, surrogate-escaping the bytes which are not,
 * so under a non-UTF-8 locale, e.g. `/home/josé` in ISO-8859-1,
 * the `str` of the path of the JVM, as passed by Jep, would be another path, encoded in (modified) UTF-8.
 * A path in the command line of a Python process is decoded by Python the same way, and needs no such conversion.
 */
internal fun pythonPath(path: String): String =
    "__import__('os').fsdecode(bytes.fromhex('${path.toByteArray(filesystemCharset).toHexString()}'))"

/**
 * The path of the hex digits of its bytes in the [filesystemCharset], or `null` if they are not hex digits.
 *
 * @throws CharacterCodingException if the bytes are not valid in the [filesystemCharset],
 * which would otherwise be replaced, and name another path.
 */
private fun String.hexToPath(): String? {
    val bytes = try {
        hexToByteArray()
    } catch (_: IllegalArgumentException) {
        return null
    }
    return filesystemCharset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

/**
 * Creates the interpreters of the embedded CPython of the Python [executable],
 * telling a failure of its startup with what the probe of the [executable] wrote to the standard error, if anything,
 * as a warning of a successful probe, e.g. `Could not find platform libraries` of a wrong `PYTHONHOME`,
 * might tell why the embedded CPython, configured the same way, fails.
 */
internal class EmbeddedPythonStarter(private val executable: String, private val probeError: String) {

    /** Whether an interpreter has been created, so that the embedded CPython has started. */
    @Volatile
    private var started = false

    /**
     * Creates an interpreter with [create], and checks it with [check], closing it if the check fails.
     *
     * @throws IllegalStateException if the embedded CPython fails to start,
     * with the original failure as its cause:
     * any failure before the first interpreter is created,
     * e.g. the [Error] with which Jep rethrows the failure of initializing CPython,
     * also when it is retried, or an [UnsatisfiedLinkError] of its native libraries.
     * Any failure after the first interpreter is created is thrown as is,
     * as the embedded CPython starts once per JVM.
     */
    fun <T : AutoCloseable> createInterpreter(create: () -> T, check: (T) -> Unit): T {
        val interpreter = try {
            create()
        } catch (e: VirtualMachineError) {
            // e.g. an OutOfMemoryError, which is no failure of the embedded CPython
            throw e
        } catch (e: Throwable) {
            if (started) throw e
            throw startupFailure(e)
        }
        started = true
        try {
            check(interpreter)
        } catch (e: Throwable) {
            try {
                interpreter.close()
            } catch (closing: Throwable) {
                e.addSuppressed(closing)
            }
            throw e
        }
        return interpreter
    }

    private fun startupFailure(cause: Throwable): IllegalStateException {
        // Jep wraps the failure of initializing CPython in an Error, whose message is its toString()
        val reason = cause.cause?.takeIf { cause.message == it.toString() } ?: cause
        val message = "Cannot initialize the embedded Python $executable: ${reason.message ?: reason}"
        return IllegalStateException(
            if (probeError.isEmpty()) message
            else "$message\nThe probe of this Python wrote to the standard error, which might tell why:\n$probeError",
            cause
        )
    }

}

/**
 * What a Python executable tells about itself.
 *
 * @property executable the absolute path of the executable, `sys.executable`.
 * @property cacheTag the `sys.implementation.cache_tag`, e.g. `cpython-313`.
 * @property prefix the `sys.prefix`, the virtual environment, if any.
 * @property jepLocation the directory of the `jep` package.
 * @property externallyManaged whether pip refuses to install into it (PEP 668), see [PythonEnvironment.probeCommand].
 * @property error the standard error of the probe, e.g. a warning of Python, empty if none.
 */
internal data class PythonProbe(
    val executable: String,
    val cacheTag: String,
    val prefix: String,
    val jepLocation: String,
    val externallyManaged: Boolean = false,
    val error: String = ""
)

/**
 * The result of [runProcess].
 *
 * @property exitCode the exit code of the process.
 * @property output the standard output, merged with the standard error, unless the error stream is separate.
 * @property error the standard error, if the error stream is separate, otherwise empty.
 */
internal data class ProcessResult(
    val exitCode: Int,
    val output: String,
    val error: String
) {

    /**
     * The standard error, followed by the standard output, without the empty ones,
     * to tell why the process failed: the error, e.g. a traceback or the message of `sys.exit`,
     * is what matters, and the output is likely partial then.
     */
    val failureOutput: String get() = listOf(error, output).filter { it.isNotEmpty() }.joinToString("\n")

}

/**
 * Runs the [command], writing the [input] to its standard input, and waits for it to end.
 *
 * Its standard error is merged into its standard output when [mergeErrorStream],
 * which keeps the order of all the messages, e.g. of a failing `pip`,
 * otherwise it is collected separately, so that the standard output can be parsed,
 * whatever Python writes to the standard error, e.g. a warning at startup.
 *
 * The process runs in the working [directory], by default the one of the JVM.
 * The [environment] variables are added to the inherited ones,
 * and every Python process exchanges its standard streams in UTF-8, whatever its locale.
 *
 * The [readOutput] reads the whole standard output, a test seam for a failure of reading it.
 */
internal fun runProcess(
    command: List<String>,
    input: String? = null,
    mergeErrorStream: Boolean = true,
    environment: Map<String, String> = emptyMap(),
    directory: Path? = null,
    readOutput: (Reader) -> String = { it.readText() }
): ProcessResult {
    // a file, unlike a pipe, does not block the process when nobody reads it
    val errorFile = if (mergeErrorStream) null else createTempFile("process-stderr", ".txt")
    try {
        val builder = ProcessBuilder(command).directory(directory?.toFile())
        builder.environment().apply {
            putAll(environment)
            // the input is written, and the output read, as UTF-8, while Python would use the locale encoding,
            // e.g. cp1252 on Windows, or ASCII in the C locale;
            // the UTF-8 mode makes the whole process tree agree on UTF-8, also pip and its build subprocesses,
            // and PYTHONIOENCODING, which would take precedence over the UTF-8 mode for the standard streams,
            // must not be inherited with another encoding
            put("PYTHONUTF8", "1")
            put("PYTHONIOENCODING", "utf-8")
        }
        if (errorFile == null) builder.redirectErrorStream(true) else builder.redirectError(errorFile.toFile())
        val process = builder.start()
        try {
            // the input is written, and the output read, on their own threads, as either of them can block,
            // when the process writes more than the pipe buffer to its output before consuming all its input;
            // the calling thread waits for the process, so that it can be interrupted
            val writer = if (input == null) {
                process.outputStream.close()
                null
            } else thread(name = "process-stdin", isDaemon = true) {
                try {
                    process.outputStream.bufferedWriter().use { it.write(input) }
                } catch (_: IOException) {
                    // a broken pipe, when the process ends, or closes its input, without reading all of it,
                    // e.g. on a failure, which its exit code and output tell, not the broken pipe
                }
            }
            var output: String? = null
            var outputFailure: Throwable? = null
            val reader = thread(name = "process-stdout", isDaemon = true) {
                try {
                    output = process.inputStream.bufferedReader().use(readOutput).trim()
                } catch (e: Throwable) {
                    // e.g. an OutOfMemoryError, rethrown on the calling thread;
                    // the process is destroyed, as it might never end without its output read,
                    // and the calling thread would wait for it forever
                    outputFailure = e
                    process.destroyForcibly()
                }
            }
            val exitCode = process.waitFor()
            reader.join()
            writer?.join()
            outputFailure?.let { throw it }
            return ProcessResult(exitCode, output!!, errorFile?.readText()?.trim() ?: "")
        } finally {
            // e.g. when interrupted while waiting for the process
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    } finally {
        errorFile?.deleteIfExists()
    }
}
