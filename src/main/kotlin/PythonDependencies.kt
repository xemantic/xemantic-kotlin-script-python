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
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isExecutable
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
 * are kept in their versions, as a module cannot be replaced once imported,
 * and the other distributions of the dependency directory in the versions required by the installed ones,
 * so that a new dependency does not break the installed ones.
 */
internal object PythonDependencies {

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

    /** Installs the missing dependencies declared by the [code], if any, on the thread of the [interpreter]. */
    fun install(code: String, interpreter: Interpreter) {
        // outside the lock, so that code without metadata does not wait for an installation of another thread
        val metadata = scriptMetadata(code) ?: return
        installMetadata(metadata, interpreter)
    }

    @Synchronized
    private fun installMetadata(metadata: String, interpreter: Interpreter) {
        val directory = PythonEnvironment.dependencyDirectory!!.createDirectories().toString()
        val imported = interpreter.getValue(
            "sorted({name.partition('.')[0] for name in __import__('sys').modules})",
            List::class.java
        ).map { it.toString() }
        val plan = plan(metadata, directory, imported)
        val missing = plan.filter { it.first == "install" }.map { it.second }
        if (missing.isNotEmpty()) {
            installMissing(missing, plan.filter { it.first == "constraint" }.map { it.second }, directory)
        }
        @Suppress("UNCHECKED_CAST")
        val path = interpreter.getValue("__import__('sys').path", List::class.java) as List<String>
        if (directory !in path) {
            interpreter.getValue("__import__('sys').path.append", PyCallable::class.java).use { it.call(directory) }
        }
        interpreter.getValue("__import__('importlib').invalidate_caches", PyCallable::class.java).use { it.call() }
    }

    /**
     * Runs the [PLAN] for the [metadata], found by [scriptMetadata], the dependency [directory],
     * and the [imported] top-level modules.
     *
     * @return the `install` and `constraint` entries of the plan, as pairs of the kind and the value.
     * @throws PythonDependencyException if the plan cannot be made.
     */
    fun plan(metadata: String, directory: String, imported: List<String>): List<Pair<String, String>> {
        // on the standard input, as the command line would exceed the OS limit after importing many modules
        val input = """{"metadata": ${metadata.toJson()}, "modules": [${imported.joinToString(", ") { it.toJson() }}]}"""
        val (exitCode, output) = runProcess(
            listOf(PythonEnvironment.executable, "-c", PLAN, directory),
            input = input
        )
        if (exitCode != 0) throw PythonDependencyException(output)
        return output.lines().filter { it.isNotBlank() }.map { it.substringBefore(' ') to it.substringAfter(' ') }
    }

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

    private fun installMissing(missing: List<String>, constraints: List<String>, directory: String) {
        val constraintsFile = createTempFile("python-constraints", ".txt")
        try {
            constraintsFile.writeLines(constraints)
            val uv = findOnPath("uv")
            val command = if (uv != null) {
                listOf(
                    uv, "pip", "install", "--quiet",
                    "--python", PythonEnvironment.executable,
                    "--target", directory,
                    "--constraints", constraintsFile.toString()
                )
            } else {
                listOf(
                    PythonEnvironment.executable, "-m", "pip", "install", "--quiet",
                    "--disable-pip-version-check", "--no-input", "--upgrade",
                    "--target", directory,
                    "--constraint", constraintsFile.toString()
                )
            } + missing
            val (exitCode, output) = runProcess(command)
            if (exitCode != 0) {
                throw PythonDependencyException(
                    "Cannot install Python dependencies ${missing.joinToString(", ")}:\n$output\n" +
                        "The dependency directory $directory keeps the distributions it holds compatible " +
                        "with the ones requiring them, and the imported ones in their versions, " +
                        "as well as the distributions of the Python environment, " +
                        "so a dependency conflicting with them cannot be installed: " +
                        "set another PythonEnvironment.dependencyDirectory for the code declaring it, " +
                        "or delete the dependency directory, when no Python uses it, to start from scratch."
                )
            }
        } finally {
            constraintsFile.deleteIfExists()
        }
    }

    private fun findOnPath(name: String): String? = System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.flatMap { listOf(Path(it, name), Path(it, "$name.exe")) }
        ?.firstOrNull { it.isExecutable() }
        ?.toString()

    /** The error of the [PLAN], when there is no TOML parser to read the metadata. */
    internal const val NO_TOML_PARSER_MESSAGE =
        "Cannot read '# /// script' metadata: Python 3.11+ is required, or the tomli or pip package before 3.11"

    /**
     * Prints the dependencies to install, as `install <requirement>`,
     * the distributions to keep in their versions, as `constraint <name>==<version>`,
     * and the specifiers required of the other distributions by the installed ones, as `constraint <name><specifier>`,
     * but not the ones of a distribution declared as a dependency to install, as it is replaced,
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

        def canonical(name):
            return re.sub(r"[-_.]+", "-", name).lower()

        def name_of(dependency):
            return re.match(r"\s*([A-Za-z0-9._-]*)", dependency).group(1)

        def is_missing(dependency):
            if Requirement is None:
                name = name_of(dependency)
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

        def required_specifiers(distribution):
            # the requirements of the distribution, without the ones of its extras, and of other environments
            for dependency in distribution.requires or []:
                try:
                    requirement = Requirement(dependency)
                    if requirement.marker is None or requirement.marker.evaluate({"extra": ""}):
                        yield requirement
                except Exception:
                    # an invalid requirement, or marker, of an installed distribution cannot be honored anyway
                    pass

        missing = [dependency for dependency in dependencies if is_missing(dependency)]
        if missing:
            upgraded = {canonical(name_of(dependency)) for dependency in missing}
            distributions_of = metadata.packages_distributions()
            in_use = {name for module in imported for name in distributions_of.get(module, [])}
            dependency_location = os.path.realpath(directory)
            # the distributions kept in their versions: the ones of the environment, and the imported ones
            pinned = {}
            # the specifiers which the installed distributions require of the other ones
            required = {}
            seen = set()
            for distribution in metadata.distributions():
                name = distribution.metadata["Name"]
                if not name or canonical(name) in seen:
                    # shadowed by a distribution found before, like by the import system
                    continue
                seen.add(canonical(name))
                in_dependencies = os.path.realpath(distribution.locate_file("")) == dependency_location
                if not in_dependencies or name in in_use or Requirement is None:
                    # without packaging, the requirements cannot be read, so all the distributions are kept
                    pinned[canonical(name)] = (name, distribution.version)
                if Requirement is not None and canonical(name) not in upgraded:
                    for requirement in required_specifiers(distribution):
                        key = canonical(requirement.name)
                        required[key] = required.get(key, requirement.specifier) & requirement.specifier
            for dependency in missing:
                print("install", dependency)
            for name, version in pinned.values():
                print("constraint", f"{name}=={version}")
            for name, specifier in required.items():
                if specifier and name not in pinned:
                    print("constraint", f"{name}{specifier}")
    """.trimIndent()

}
