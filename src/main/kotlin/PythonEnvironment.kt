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
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

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
        val (exitCode, output) = try {
            runProcess(
                listOf(
                    python,
                    "-c",
                    "import importlib.util, sys; print(sys.executable, sys.implementation.cache_tag, sys.prefix, " +
                        "importlib.util.find_spec('jep').submodule_search_locations[0], sep='\\n')"
                )
            )
        } catch (e: java.io.IOException) {
            throw IllegalStateException("Cannot run Python executable: $python", e)
        }
        check(exitCode == 0) {
            "Cannot find the jep package of $python, install it with: $python -m pip install jep\n$output"
        }
        val (executable, cacheTag, prefix, jepLocation) = output.lines()
        val jepDir = Path(jepLocation)
        val library = jepDir.listDirectoryEntries().firstOrNull {
            it.name.startsWith("libjep.") || it.name == "jep.dll"
        } ?: throw IllegalStateException(
            "No native Jep library in $jepDir, reinstall jep with: $python -m pip install jep"
        )
        MainInterpreter.setJepLibraryPath(library.toString())
        // the embedded CPython finds the pyvenv.cfg of a virtual environment next to its program,
        // otherwise it would search the site-packages of the base Python as well
        MainInterpreter.setInitParams(PyConfig().setProgramName(executable))
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
 * Runs the [command], writing the [input] to its standard input, and waits for it to end.
 *
 * @return the exit code, and the standard output merged with the standard error.
 */
internal fun runProcess(command: List<String>, input: String? = null): Pair<Int, String> {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    process.outputStream.bufferedWriter().use { writer ->
        if (input != null) writer.write(input)
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    return process.waitFor() to output
}
