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

import jep.MainInterpreter
import jep.PyConfig
import java.io.IOException
import java.io.Reader
import java.nio.file.Path
import java.security.MessageDigest
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
 * 3. `python3` on the `PATH`.
 *
 * The `jep` package must be installed in it, with `pip install jep`,
 * in the version of the `org.ninia:jep` library,
 * and the Python code imports the modules from the same paths as this executable,
 * e.g. the `site-packages` of a virtual environment, but not the ones of its base Python.
 */
public object PythonEnvironment {

    private var initialized = false

    /**
     * The Python executable to use, instead of the one resolved by default.
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

    @Synchronized
    internal fun initialize() {
        if (initialized) return
        val python = (pythonExecutable ?: defaultPythonExecutable()).toString()
        val (executable, cacheTag, prefix, jepLocation) = probePython(python)
        val jepDir = Path(jepLocation)
        val library = jepDir.listDirectoryEntries().firstOrNull {
            it.name.startsWith("libjep.") || it.name == "jep.dll"
        } ?: throw IllegalStateException(
            "No native Jep library in $jepDir, reinstall jep with: $python -m pip install jep"
        )
        MainInterpreter.setJepLibraryPath(library.toString())
        // the embedded CPython finds the pyvenv.cfg of a virtual environment next to its program,
        // otherwise it would search the site-packages of the base Python as well
        MainInterpreter.setInitParams(PyConfig.python().setProgramName(executable))
        this.executable = executable
        if (dependencyDirectory == null) {
            dependencyDirectory = System.getProperty(DEPENDENCY_DIRECTORY_PROPERTY)?.let { Path(it) }
        }
        if (dependencyDirectory == null) {
            val prefixHash = MessageDigest.getInstance("SHA-256")
                .digest(prefix.toByteArray())
                .take(6)
                .joinToString("") { "%02x".format(it) }
            dependencyDirectory = Path(
                System.getProperty("user.home"), ".cache", "xemantic-kotlin-script-python", "$cacheTag-$prefixHash"
            )
        }
        initialized = true
    }

    /**
     * Asks the [python] executable about itself.
     *
     * @throws IllegalStateException if it cannot be run, has no `jep` package,
     * does not write the values of the probe, or cannot tell the path of its executable.
     */
    internal fun probePython(python: String): PythonProbe {
        val result = try {
            runProcess(probeCommand(python), mergeErrorStream = false)
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Cannot run Python executable: $python", e)
        }
        check(result.exitCode == 0) {
            "Cannot find the jep package of $python, install it with: $python -m pip install jep\n" +
                result.failureOutput
        }
        val probe = checkNotNull(parseProbeOutput(result.output)) {
            "Cannot read the values of the Python environment probe of $python from its output:\n" +
                result.failureOutput
        }
        // the embedded CPython needs it to find the pyvenv.cfg of a virtual environment, and pip is run with it
        check(probe.executable.isNotEmpty()) {
            "Python cannot tell the path of its executable (sys.executable is empty): $python"
        }
        return probe
    }

    /**
     * The [PythonProbe] of the standard [output] of [probeCommand].
     *
     * The values are taken only from between the last [PROBE_BEGIN] and the following [PROBE_END] line,
     * as anything else run by Python, e.g. a `sitecustomize` module or a `.pth` file,
     * can write to the standard output as well, before or after them.
     *
     * @return the probe, or `null` if the [output] has no such values.
     */
    internal fun parseProbeOutput(output: String): PythonProbe? {
        // the output is trimmed, also of the newline before the first line
        val lines = "\n$output"
        val begin = lines.lastIndexOf("\n$PROBE_BEGIN\n").takeIf { it != -1 }?.let { it + PROBE_BEGIN.length + 2 }
        val end = begin?.let { lines.indexOf("\n$PROBE_END", it) }?.takeIf { it != -1 }
        val values = if (begin == null || end == null) null else lines.substring(begin, end).split('\u0000')
        if (values == null || values.size != 4) return null
        val (executable, cacheTag, prefix, jepLocation) = values
        return PythonProbe(executable, cacheTag, prefix, jepLocation)
    }

    /**
     * The command of [probePython].
     *
     * It writes its values, separated by NUL, which no path can contain, between the [PROBE_BEGIN] and [PROBE_END] lines,
     * with an empty line before them, in case the standard output written before does not end with a newline,
     * and as bytes, without the newline translation of the text mode, e.g. on Windows,
     * to the original standard output, as `sys.stdout` might be replaced, e.g. by a `sitecustomize` module,
     * with a stream which has no `buffer`, or which does not write to the standard output at all.
     */
    internal fun probeCommand(python: String): List<String> = pythonCommand(
        python,
        """
        |import importlib.util, sys
        |values = [
        |    sys.executable,
        |    sys.implementation.cache_tag,
        |    sys.prefix,
        |    importlib.util.find_spec('jep').submodule_search_locations[0],
        |]
        |payload = '\0'.join('' if value is None else str(value) for value in values)
        |sys.stdout.flush()
        |sys.__stdout__.flush()
        |sys.__stdout__.buffer.write(('\n$PROBE_BEGIN\n' + payload + '\n$PROBE_END\n').encode('utf-8'))
        |sys.__stdout__.flush()
        |""".trimMargin()
    )

    internal const val PROBE_BEGIN = "--- xemantic-kotlin-script-python probe begin ---"

    internal const val PROBE_END = "--- xemantic-kotlin-script-python probe end ---"

    private const val DEPENDENCY_DIRECTORY_PROPERTY = "xemantic.kotlin.script.python.dependencyDirectory"

    private fun checkNotInitialized() {
        check(!initialized) {
            "The Python environment is already initialized"
        }
    }

    private fun defaultPythonExecutable(): Path {
        val virtualEnv = System.getenv("VIRTUAL_ENV")
        if (virtualEnv != null) {
            val unix = Path(virtualEnv, "bin", "python")
            return if (unix.exists()) unix else Path(virtualEnv, "Scripts", "python.exe")
        }
        return Path("python3")
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
 * What a Python executable tells about itself.
 *
 * @property executable the absolute path of the executable, `sys.executable`.
 * @property cacheTag the `sys.implementation.cache_tag`, e.g. `cpython-313`.
 * @property prefix the `sys.prefix`, the virtual environment, if any.
 * @property jepLocation the directory of the `jep` package.
 */
internal data class PythonProbe(
    val executable: String,
    val cacheTag: String,
    val prefix: String,
    val jepLocation: String
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
