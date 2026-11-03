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
 * are kept in their versions, as a module cannot be replaced once imported.
 */
internal object PythonDependencies {

    private val scriptMetadata = Regex("(?m)^# /// script$")

    /** Installs the missing dependencies declared by the [code], if any, on the thread of the [interpreter]. */
    @Synchronized
    fun install(code: String, interpreter: Interpreter) {
        if (!scriptMetadata.containsMatchIn(code)) return
        val directory = PythonEnvironment.dependencyDirectory!!.createDirectories().toString()
        val imported = interpreter.getValue(
            "sorted({name.partition('.')[0] for name in __import__('sys').modules})",
            List::class.java
        ).map { it.toString() }
        val (exitCode, output) = runProcess(
            listOf(PythonEnvironment.executable, "-c", PLAN, directory) + imported,
            input = code
        )
        if (exitCode != 0) throw PythonDependencyException(output)
        val plan = output.lines().filter { it.isNotBlank() }.map { it.substringBefore(' ') to it.substringAfter(' ') }
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
                    "Cannot install Python dependencies ${missing.joinToString(", ")}:\n$output"
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

    /**
     * Prints the dependencies to install, as `install <requirement>`,
     * and the distributions to keep in their versions, as `constraint <name>==<version>`,
     * or exits with the error.
     * Its arguments are the dependency directory and the imported top-level modules,
     * and the code declaring the dependencies is read from the standard input.
     */
    @Language("python")
    private val PLAN = """
        import importlib.metadata as metadata, os, re, sys, tomllib

        directory, imported = sys.argv[1], set(sys.argv[2:])
        sys.path.append(directory)
        # the reference implementation of https://packaging.python.org/en/latest/specifications/inline-script-metadata/
        blocks = [
            match for match in re.finditer(
                r"(?m)^# /// (?P<type>[a-zA-Z0-9-]+)$\s(?P<content>(^#(| .*)$\s)+)^# ///$", sys.stdin.read()
            ) if match.group("type") == "script"
        ]
        if len(blocks) > 1:
            sys.exit("Multiple '# /// script' metadata blocks, only one is allowed")
        if not blocks:
            sys.exit("Unclosed '# /// script' metadata block, it has to end with a '# ///' line")
        content = "".join(
            line[2:] if line.startswith("# ") else line[1:]
            for line in blocks[0].group("content").splitlines(keepends=True)
        )
        try:
            dependencies = tomllib.loads(content).get("dependencies", [])
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
            in_use = {name for module in imported for name in metadata.packages_distributions().get(module, [])}
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
