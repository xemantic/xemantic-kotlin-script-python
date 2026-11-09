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
 * unless the dependencies replace these as well,
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
        install(plan(metadata, directory, imported), directory)
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
     * @return the `install`, `constraint` and `requirement` entries of the plan, as pairs of the kind and the value.
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

    /** A requirement of a distribution installed in the dependency directory, of the [PLAN]. */
    private class InstalledRequirement(
        val dependent: String,
        val version: String,
        val name: String,
        val specifier: String
    ) {
        override fun toString() = "$name $specifier, required by $dependent $version"
    }

    /**
     * Installs the `install` entries of the [plan], of the [PLAN], into the dependency [directory],
     * with the [installerOptions] given to the installer, e.g. its index.
     *
     * The `requirement` entries, of the distributions installed in the directory, become constraints,
     * except the ones of the distributions which the dependencies resolve to,
     * as the resolution of the installer replaces them, if needed, honoring the requirements of their new versions,
     * so they are resolved first, but only when there are such requirements.
     *
     * @throws PythonDependencyException if the dependencies cannot be installed.
     */
    fun install(plan: List<Pair<String, String>>, directory: String, installerOptions: List<String> = emptyList()) {
        val missing = plan.filter { it.first == "install" }.map { it.second }
        if (missing.isEmpty()) return
        val pinned = plan.filter { it.first == "constraint" }.map { it.second }
        val requirements = plan.filter { it.first == "requirement" }.map {
            val (dependent, version, name, specifier) = it.second.split(' ')
            InstalledRequirement(dependent, version, name, specifier)
        }
        val uv = findOnPath("uv")
        val protecting = if (requirements.isEmpty()) emptyList() else {
            val resolved = resolve(missing, pinned, directory, uv, installerOptions)
            requirements.filter { it.dependent !in resolved && it.name in resolved }
        }
        val constraints = pinned + protecting.groupBy { it.name }.map { (name, requirements) ->
            name + requirements.joinToString(",") { it.specifier }
        }
        val (exitCode, output) = withConstraints(constraints) { constraintsFile ->
            runProcess(
                if (uv != null) {
                    listOf(
                        uv, "pip", "install", "--quiet",
                        "--python", PythonEnvironment.executable,
                        "--target", directory,
                        "--constraints", constraintsFile
                    )
                } else {
                    listOf(
                        PythonEnvironment.executable, "-m", "pip", "install", "--quiet",
                        "--disable-pip-version-check", "--no-input", "--upgrade",
                        "--target", directory,
                        "--constraint", constraintsFile
                    )
                } + installerOptions + missing
            )
        }
        if (exitCode != 0) {
            val message = "Cannot install Python dependencies ${missing.joinToString(", ")}:\n$output"
            // resolved without the requirements of the installed distributions, so these are the conflict
            throw PythonDependencyException(
                if (protecting.isEmpty()) message else message + "\n" +
                    "The dependencies conflict with the requirements of the distributions " +
                    "installed in the dependency directory $directory:\n" +
                    protecting.joinToString("") { "  $it\n" } +
                    "Installing them would break these distributions, so they need another dependency directory, " +
                    "see PythonEnvironment.dependencyDirectory, " +
                    "or the dependency directory can be deleted, when no Python uses it, to start from scratch."
            )
        }
    }

    /**
     * The canonical names of the distributions which the [missing] dependencies resolve to,
     * with the [pinned] constraints,
     * without installing them, or the dependency directory, which pip ignores with `--target` anyway.
     */
    private fun resolve(
        missing: List<String>,
        pinned: List<String>,
        directory: String,
        uv: String?,
        installerOptions: List<String>
    ): Set<String> {
        val (exitCode, output, error) = withConstraints(pinned) { constraintsFile ->
            if (uv != null) {
                runProcess(
                    listOf(
                        uv, "pip", "compile", "-", "--quiet", "--no-header", "--no-annotate",
                        "--python", PythonEnvironment.executable,
                        "--constraints", constraintsFile
                    ) + installerOptions,
                    input = missing.joinToString("\n"),
                    mergeErrorStream = false
                )
            } else {
                val (exitCode, report, error) = runProcess(
                    listOf(
                        PythonEnvironment.executable, "-m", "pip", "install", "--quiet",
                        "--disable-pip-version-check", "--no-input", "--dry-run", "--report", "-",
                        "--target", directory,
                        "--constraint", constraintsFile
                    ) + installerOptions + missing,
                    mergeErrorStream = false
                )
                if (exitCode != 0) ProcessResult(exitCode, report, error) else runProcess(
                    listOf(PythonEnvironment.executable, "-c", REPORTED_NAMES),
                    input = report,
                    mergeErrorStream = false
                )
            }
        }
        if (exitCode != 0) {
            throw PythonDependencyException(
                "Cannot install Python dependencies ${missing.joinToString(", ")}:\n" +
                    listOf(output, error).filter { it.isNotEmpty() }.joinToString("\n")
            )
        }
        // uv lists name==version, or name @ url, pip only the name
        return output.lines()
            .mapNotNull { Regex("^[A-Za-z0-9][A-Za-z0-9._-]*").find(it)?.value }
            .map { it.lowercase().replace(Regex("[-_.]+"), "-") }
            .toSet()
    }

    /** The names of the distributions to install, in the pip installation report on the standard input. */
    @Language("python")
    private const val REPORTED_NAMES =
        "import json, sys; print(*(it['metadata']['name'] for it in json.load(sys.stdin)['install']), sep='\\n')"

    private fun <T> withConstraints(constraints: List<String>, block: (constraintsFile: String) -> T): T {
        val constraintsFile = createTempFile("python-constraints", ".txt")
        try {
            constraintsFile.writeLines(constraints)
            return block(constraintsFile.toString())
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
     * and the requirements of the distributions of the dependency directory,
     * as `requirement <dependent> <version> <name> <specifier>`, with canonical names,
     * which [install] turns into constraints, or exits with the error.
     * Without packaging, to read the requirements, there are none,
     * and with several `dist-info` directories of a distribution, left behind by pip, there are none of it,
     * as which of them describes the installed files is unknown.
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
            from packaging.utils import canonicalize_name
        except ImportError:
            try:
                from pip._vendor.packaging.requirements import Requirement, InvalidRequirement
                from pip._vendor.packaging.utils import canonicalize_name
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

        def requirements_of(distribution):
            # without the ones of its extras, and of other environments
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
            if Requirement is not None:
                kept = {canonicalize_name(name) for name in constraints}
                installed = {}
                for distribution in metadata.distributions(path=[directory]):
                    name = distribution.metadata["Name"]
                    if name:
                        installed.setdefault(canonicalize_name(name), []).append(distribution)
                for name, distributions in installed.items():
                    # pip --target leaves the dist-info of a replaced version behind,
                    # and which of them describes the installed files is unknown
                    if len(distributions) == 1:
                        distribution = distributions[0]
                        for requirement in requirements_of(distribution):
                            required = canonicalize_name(requirement.name)
                            if requirement.specifier and required not in kept:
                                print("requirement", name, distribution.version, required, requirement.specifier)
    """.trimIndent()

}
