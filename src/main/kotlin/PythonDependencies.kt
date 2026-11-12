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
import jep.python.PyCallable
import org.intellij.lang.annotations.Language
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeLines

/**
 * The Python dependencies could not be installed.
 *
 * @param message the description of the failure, with the output of the installer.
 */
public class PythonDependencyException(message: String) : RuntimeException(message)

/**
 * Installs the dependencies declared by the Python code
 * with [inline script metadata](https://packaging.python.org/en/latest/specifications/inline-script-metadata/),
 * into the dependency directory of the [PythonEnvironment], shared by all the [Python] interpreters.
 *
 * The distributions of the environment itself, and the ones already imported from the dependency directory,
 * are kept in their versions, as a module cannot be replaced once imported.
 */
internal object PythonDependencies {

    /**
     * The file in the dependency directory, locked by every JVM while it installs into the directory.
     *
     * Not `.lock`, which `uv pip install --target` locks itself while this JVM waits for it,
     * where the locks of both interact, e.g. on Windows, macOS, or NFS,
     * so uv would wait for its timeout, and then install without locking.
     */
    internal const val LOCK_FILE_NAME = ".xemantic-kotlin-script-python.lock"

    private val blockStart = Regex("# /// [a-zA-Z0-9-]+")

    /**
     * The TOML content of the `script` metadata block of the [code], or `null` if there is none.
     *
     * The blocks are found like with the regex of the
     * [reference implementation](https://packaging.python.org/en/latest/specifications/inline-script-metadata/#reference-implementation),
     * so an unclosed block is not a block, but line by line, in linear time:
     * a regex retries all the following comment lines for every unclosed block start,
     * and Java one also recurses for each line of the block.
     *
     * Unlike in the reference implementation, every line, also of the content, ends only with `\n` or `\r\n`:
     * a `\r\n` line ending, of code written on Windows, does not hide the block,
     * and the content is not broken within a TOML string at the characters like `\u000c` or `\u0085`,
     * as `str.splitlines` would do.
     *
     * @throws PythonDependencyException if there is more than one `script` block.
     */
    fun scriptMetadata(code: String): String? {
        if ("# ///" !in code) return null
        val lines = code.split('\n').map { it.removeSuffix("\r") }
        val blocks = mutableListOf<List<String>>()
        var runStart = 0
        while (runStart < lines.size) {
            if (!lines[runStart].isComment()) {
                runStart++
                continue
            }
            var runEnd = runStart
            while (runEnd < lines.size && lines[runEnd].isComment()) runEnd++
            // a block is a run of consecutive comment lines, ending at its last closing line,
            // started by its first block start followed by at least one content line before that
            val end = (runStart until runEnd).lastOrNull { lines[it] == "# ///" }
            if (end != null) {
                val start = (runStart until end - 1).firstOrNull { blockStart.matches(lines[it]) }
                if (start != null && lines[start] == "# /// script") {
                    blocks += lines.subList(start + 1, end)
                }
            }
            runStart = runEnd
        }
        if (blocks.size > 1) {
            throw PythonDependencyException("Multiple '# /// script' metadata blocks, only one is allowed")
        }
        return blocks.singleOrNull()?.joinToString("") {
            (if (it.startsWith("# ")) it.drop(2) else it.drop(1)) + "\n"
        }
    }

    private fun String.isComment() = this == "#" || startsWith("# ")

    /**
     * Serializes the installations of this JVM,
     * before the [LOCK_FILE_NAME] serializes them with the ones of other processes,
     * as a JVM cannot lock a file it already holds a lock on.
     */
    internal val installLock = ReentrantLock()

    /** Installs the missing dependencies declared by the [code], if any, on the thread of the [interpreter]. */
    fun install(code: String, interpreter: Interpreter) {
        // before locking, so that code without metadata does not wait for an installation of another thread
        val metadata = scriptMetadata(code) ?: return
        val directory = PythonEnvironment.dependencyDirectory!!
        val imported = interpreter.getValue(
            "sorted({name.partition('.')[0] for name in __import__('sys').modules})",
            List::class.java
        ).map { it.toString() }
        // without locking, as the plan only reads the directory, which can be read-only when nothing is missing,
        // and so that the installed dependencies do not wait for an installation of another thread or process
        if (plan(metadata, directory.toString(), imported).missing().isNotEmpty()) {
            installLock.withLock {
                withDirectoryLock(directory) {
                    // again, as another thread or process could have installed them meanwhile
                    val plan = plan(metadata, directory.toString(), imported)
                    val missing = plan.missing()
                    if (missing.isNotEmpty()) {
                        val constraints = plan.filter { it.first == "constraint" }.map { it.second }
                        installMissing(missing, constraints, directory.toString())
                    }
                }
            }
        }
        // the sys module is shared by all the interpreters, so that the directory is appended only once;
        // as the str of the path in Python, which differs from the one of the JVM under a non-UTF-8 locale
        synchronized(this) {
            interpreter.exec(
                "(lambda path, sys: path in sys.path or sys.path.append(path))" +
                    "(${pythonPath(directory.toString())}, __import__('sys'))"
            )
        }
        interpreter.getValue("__import__('importlib').invalidate_caches", PyCallable::class.java).use { it.call() }
    }

    private fun List<Pair<String, String>>.missing() = filter { it.first == "install" }.map { it.second }

    /**
     * Runs the [block] with the [LOCK_FILE_NAME] of the dependency [directory] locked,
     * against another JVM installing into the same directory, e.g. a shared one of the user cache,
     * which would otherwise delete or overwrite the files being installed.
     *
     * The lock file is never deleted, as another process could have opened it already, and would lock a deleted file.
     * It is created writable for everyone, like the lock file of uv,
     * so that the users sharing the directory, e.g. of a group, can lock it.
     *
     * @throws PythonDependencyException if the directory cannot be locked, e.g. when it is not writable.
     */
    private fun withDirectoryLock(directory: Path, block: () -> Unit) {
        val lockFile = directory.resolve(LOCK_FILE_NAME)
        val channel = try {
            directory.createDirectories()
            if (!lockFile.exists()) createLockFile(lockFile)
            FileChannel.open(lockFile, WRITE)
        } catch (e: IOException) {
            throw PythonDependencyException("Cannot lock the dependency directory with $lockFile: $e")
        }
        channel.use {
            val lock = try {
                channel.lock()
            } catch (e: IOException) {
                // e.g. ENOLCK of a network file system without locking
                throw PythonDependencyException("Cannot lock the dependency directory with $lockFile: $e")
            }
            lock.use { block() }
        }
    }

    private fun createLockFile(lockFile: Path) {
        try {
            lockFile.createFile()
        } catch (_: FileAlreadyExistsException) {
            // created by another process meanwhile
            return
        }
        try {
            // after creating, as the permissions of creating are restricted by the umask
            lockFile.setPosixFilePermissions(PosixFilePermissions.fromString("rw-rw-rw-"))
        } catch (_: UnsupportedOperationException) {
            // e.g. on Windows
        }
    }

    /**
     * Runs the [PLAN] for the [metadata], found by [scriptMetadata], the dependency [directory],
     * and the [imported] top-level modules, with the [command], by default the [planCommand].
     *
     * @return the `install` and `constraint` entries of the plan, as pairs of the kind and the value.
     * @throws PythonDependencyException if the plan cannot be made.
     */
    fun plan(
        metadata: String,
        directory: String,
        imported: List<String>,
        command: List<String> = planCommand(directory)
    ): List<Pair<String, String>> {
        // on the standard input, as the command line would exceed the OS limit after importing many modules
        val input = """{"metadata": ${metadata.toJson()}, "modules": [${imported.joinToString(", ") { it.toJson() }}]}"""
        // the standard error separately, as a write to it, e.g. a warning, could split a line of the plan
        val result = runProcess(command, input = input, mergeErrorStream = false)
        if (result.exitCode != 0) throw PythonDependencyException(result.failureOutput)
        return result.output.lines().filter { it.isNotBlank() }.map { it.substringBefore(' ') to it.substringAfter(' ') }
    }

    /** The command running the [PLAN] for the dependency [directory]. */
    fun planCommand(directory: String): List<String> = pythonCommand(PythonEnvironment.executable, PLAN, directory)

    /**
     * The command running `pip` of the environment with the [arguments],
     * like `python -m pip`, which would import from the working directory, also `pip` itself.
     */
    fun pipCommand(vararg arguments: String): List<String> = pythonCommand(
        PythonEnvironment.executable,
        "import runpy\nrunpy.run_module('pip', run_name='__main__', alter_sys=True)",
        *arguments
    )

    /** This string as a JSON string, with every character outside printable ASCII escaped, so it is ASCII only. */
    private fun String.toJson(): String = buildString(length + 2) {
        append('"')
        for (char in this@toJson) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                in ' '..'~' -> append(char)
                else -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
            }
        }
        append('"')
    }

    /**
     * Installs the [missing] dependencies into the dependency [directory], keeping the [constraints],
     * with the [uv] executable, if any, otherwise with `pip` of the environment.
     */
    fun installMissing(
        missing: List<String>,
        constraints: List<String>,
        directory: String,
        uv: String? = findOnPath("uv")
    ) {
        val constraintsFile = createTempFile("python-constraints", ".txt")
        try {
            constraintsFile.writeLines(constraints)
            // understood alike by uv and pip; not --constraints, an alias in uv, which uv older than 0.5.4 rejects
            val arguments = listOf(
                "--target", directory,
                "--constraint", constraintsFile.toString()
            ) + missing
            val command = if (uv != null) {
                listOf(
                    uv, "pip", "install", "--quiet",
                    "--python", PythonEnvironment.executable
                ) + arguments
            } else {
                pipCommand(
                    "install", "--quiet",
                    "--disable-pip-version-check", "--no-input", "--upgrade",
                    *arguments.toTypedArray()
                )
            }
            val (exitCode, output) = runProcess(command)
            if (exitCode != 0) {
                throw PythonDependencyException(
                    "Cannot install Python dependencies ${missing.joinToString(", ")}:\n$output"
                )
            }
        } finally {
            constraintsFile.deleteIfExists()
        }
    }

    /** The executable of the [name] on the `PATH`, or `null` if there is none. */
    fun findOnPath(name: String): String? = System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.flatMap { listOf(Path(it, name), Path(it, "$name.exe")) }
        ?.firstOrNull { it.isExecutable() }
        ?.toString()

    /** The error of the [PLAN], when there is no TOML parser to read the metadata. */
    internal const val NO_TOML_PARSER_MESSAGE =
        "Cannot read '# /// script' metadata: Python 3.11+ is required, or the tomli or pip package before 3.11"

    /**
     * Prints the dependencies to install, as `install <requirement>`,
     * and the distributions to keep in their versions, as `constraint <name>==<version>`,
     * or exits with the error.
     * Its argument is the dependency directory,
     * and its standard input is a JSON object of the TOML content of the `script` metadata block,
     * found by [scriptMetadata], as `metadata`, and of the imported top-level modules, as `modules`.
     */
    @Language("python")
    internal val PLAN = """
        import json, sys

        # before any import which can fail, so that the input is always read
        request = json.loads(sys.stdin.read())

        import importlib, importlib.metadata as metadata, os, re

        directory, imported = sys.argv[1], set(request["modules"])
        sys.path.append(directory)
        # tomllib is new in Python 3.11, tomli is its original, also vendored by pip
        for toml_module in ("tomllib", "tomli", "pip._vendor.tomli"):
            try:
                tomllib = importlib.import_module(toml_module)
                break
            except ImportError:
                pass
        else:
            sys.exit(${NO_TOML_PARSER_MESSAGE.toJson()})
        try:
            dependencies = tomllib.loads(request["metadata"]).get("dependencies", [])
        except tomllib.TOMLDecodeError as e:
            sys.exit(f"Invalid '# /// script' metadata: {e}")
        if not isinstance(dependencies, list) or not all(isinstance(it, str) for it in dependencies):
            sys.exit("Invalid '# /// script' metadata: 'dependencies' must be a list of strings")

        try:
            from packaging.requirements import Requirement, InvalidRequirement
        except ImportError:
            try:
                from pip._vendor.packaging.requirements import Requirement, InvalidRequirement
            except ImportError:
                Requirement = None

        def is_missing(dependency):
            if Requirement is None:
                name = re.match(r"\s*([A-Za-z0-9._-]*)", dependency).group(1)
                specified = dependency.strip() != name
            else:
                try:
                    requirement = Requirement(dependency)
                except InvalidRequirement as e:
                    sys.exit(f"Invalid dependency '{dependency}': {e}")
                if requirement.marker is not None and not requirement.marker.evaluate():
                    return False
                name = requirement.name
            try:
                version = metadata.version(name)
            except metadata.PackageNotFoundError:
                return True
            if Requirement is None:
                return specified
            return requirement.url is not None or not requirement.specifier.contains(version, prereleases=True)

        missing = [dependency for dependency in dependencies if is_missing(dependency)]
        if missing:
            distributions_of = metadata.packages_distributions()
            in_use = {name for module in imported for name in distributions_of.get(module, [])}
            dependency_location = os.path.realpath(directory)
            constraints = {}
            for distribution in metadata.distributions():
                name = distribution.metadata["Name"]
                in_dependencies = os.path.realpath(distribution.locate_file("")) == dependency_location
                if name and (not in_dependencies or name in in_use):
                    constraints.setdefault(name, distribution.version)
            for dependency in missing:
                print("install", dependency)
            for name, version in constraints.items():
                print("constraint", f"{name}=={version}")
    """.trimIndent()

}
