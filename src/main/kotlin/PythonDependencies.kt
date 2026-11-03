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
        if (exitCode != 0) throw PythonDependencyException(redactCredentials(output))
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
                ) + missing
            } else {
                pipInstallCommand(PythonEnvironment.executable, directory, constraintsFile.toString(), missing)
            }
            val (exitCode, output) = runProcess(command)
            if (exitCode != 0) {
                throw PythonDependencyException(
                    redactCredentials("Cannot install Python dependencies ${missing.joinToString(", ")}:\n$output")
                )
            }
        } finally {
            constraintsFile.deleteIfExists()
        }
    }

    /**
     * The command installing the [requirements] with pip of the [python] executable into the [directory],
     * keeping the distributions of the [constraints] file in their versions,
     * and the ones already in the [directory], if they satisfy the [requirements].
     */
    internal fun pipInstallCommand(
        python: String,
        directory: String,
        constraints: String,
        requirements: List<String>
    ): List<String> = listOf(python, "-c", PIP_INSTALL, directory, constraints) + requirements

    /**
     * The `pinned_requirement` Python function,
     * returning the requirement of exactly the distribution resolved in an installation report of pip,
     * like `direct_url_as_pep440_direct_reference` of pip,
     * and the `requested_urls` function, returning the URLs of the requirements by their distribution names.
     *
     * The report has the URLs without their credentials,
     * which are taken back from the URL of the requirement, if the distribution is a requested one.
     */
    @Language("python")
    internal val PIP_PINNED_REQUIREMENT = """
        def requested_urls(requirements):
            from pip._vendor.packaging.requirements import InvalidRequirement, Requirement
            from pip._vendor.packaging.utils import canonicalize_name
            urls = {}
            for requirement in requirements:
                try:
                    parsed = Requirement(requirement)
                except InvalidRequirement:
                    continue
                if parsed.url:
                    urls[canonicalize_name(parsed.name)] = parsed.url
            return urls

        def with_credentials(url, requested_url):
            from urllib.parse import urlsplit, urlunsplit
            credentials, at, host = urlsplit(requested_url).netloc.rpartition("@")
            parts = urlsplit(url)
            if not at or parts.netloc.rpartition("@")[2] != host:
                return url
            return urlunsplit(parts._replace(netloc=f"{credentials}@{host}"))

        def pinned_requirement(distribution, urls):
            from pip._vendor.packaging.utils import canonicalize_name
            name, download = distribution["metadata"]["name"], distribution["download_info"]
            if not distribution["is_direct"]:
                return f"{name}=={distribution['metadata']['version']}"
            url = download["url"]
            if distribution.get("requested") and canonicalize_name(name) in urls:
                url = with_credentials(url, urls[canonicalize_name(name)])
            fragments = []
            if "vcs_info" in download:
                vcs = download["vcs_info"]
                requirement = f"{name} @ {vcs['vcs']}+{url}@{vcs['commit_id']}"
            else:
                requirement = f"{name} @ {url}"
                archive = download.get("archive_info") or {}
                if archive.get("hashes"):
                    fragments.append("=".join(next(iter(archive["hashes"].items()))))
                elif archive.get("hash"):
                    fragments.append(archive["hash"])
            if download.get("subdirectory"):
                fragments.append("subdirectory=" + download["subdirectory"])
            return requirement + ("#" + "&".join(fragments) if fragments else "")
    """.trimIndent()

    /**
     * Installs the requirements with pip into the dependency directory, its first argument,
     * keeping the versions of the constraints file, its second argument.
     *
     * pip ignores the distributions already installed in a `--target` directory,
     * so it would install them again, also with their own dependencies, replacing the files of the loaded modules.
     * Therefore, the distributions to install are resolved first, with the dependency directory on `sys.path`,
     * and then only these are installed, without their dependencies, into an empty staging directory.
     * Its files are merged into the dependency directory, after removing the files of the replaced distributions,
     * so that a directory shared with other distributions, like a namespace package, is kept.
     */
    @Language("python")
    private val PIP_INSTALL = PIP_PINNED_REQUIREMENT + "\n\n" + """
        import json, os, re, shutil, subprocess, sys, tempfile
        from importlib import metadata
        from pathlib import Path

        directory, constraints, requirements = sys.argv[1], sys.argv[2], sys.argv[3:]

        try:
            from pip import __version__ as pip_version
        except ImportError:
            sys.exit(f"Installing Python dependencies needs uv, or pip 22.2 or newer, but {sys.executable} has no pip")
        if tuple(int(part) for part in re.findall(r"\d+", pip_version)[:2]) < (22, 2):
            sys.exit(
                f"Installing Python dependencies needs uv, or pip 22.2 or newer, but {sys.executable} has pip {pip_version}, "
                f"upgrade it with: {sys.executable} -m pip install --upgrade pip"
            )

        from pip._vendor.packaging.utils import canonicalize_name

        # the staging directories left by a killed installation
        for stale in Path(directory).glob(".staging-*"):
            shutil.rmtree(stale, ignore_errors=True)

        options = ["--quiet", "--disable-pip-version-check", "--no-input"]
        pip_seeing_directory = [
            sys.executable, "-c",
            "import runpy, sys; sys.path.append(sys.argv.pop(1)); runpy.run_module('pip', run_name='__main__', alter_sys=True)",
            directory
        ] + options
        with tempfile.TemporaryDirectory() as temp:
            report = os.path.join(temp, "report.json")
            resolution = subprocess.run(
                pip_seeing_directory + ["install", "--dry-run", "--report", report, "--constraint", constraints] + requirements
            )
            if resolution.returncode != 0:
                sys.exit(resolution.returncode)
            with open(report, encoding="utf-8") as file:
                resolved = json.load(file)["install"]
        if not resolved:
            sys.exit()
        urls = requested_urls(requirements)

        def distributions(path):
            found = {}
            for entry in Path(path).glob("*.dist-info"):
                name = metadata.PathDistribution(entry).metadata["Name"]
                if entry.is_dir() and name:
                    found.setdefault(canonicalize_name(name), []).append(entry)
            return found

        def remove(distribution, kept):
            emptied = set()
            for file in metadata.PathDistribution(distribution).files or []:
                relative = os.path.normpath(str(file))
                path = os.path.join(directory, relative)
                if relative.startswith(os.pardir) or os.path.isabs(relative) or relative in kept:
                    continue
                if os.path.isfile(path) or os.path.islink(path):
                    os.remove(path)
                    emptied.add(os.path.dirname(path))
            shutil.rmtree(distribution, ignore_errors=True)
            for path in sorted(emptied, key=len, reverse=True):
                while os.path.normpath(path) != os.path.normpath(directory) and os.path.isdir(path) and not os.listdir(path):
                    os.rmdir(path)
                    path = os.path.dirname(path)

        os.makedirs(directory, exist_ok=True)
        staging = tempfile.mkdtemp(prefix=".staging-", dir=directory)
        try:
            installation = subprocess.run(
                [sys.executable, "-m", "pip"] + options
                + ["install", "--no-deps", "--target", staging]
                + [pinned_requirement(distribution, urls) for distribution in resolved]
            )
            if installation.returncode != 0:
                sys.exit(installation.returncode)
            staged = {
                os.path.relpath(os.path.join(root, file), staging)
                for root, _, files in os.walk(staging) for file in files
            }
            staged_names = distributions(staging).keys()
            for name, replaced in distributions(directory).items():
                if name in staged_names:
                    for distribution in replaced:
                        remove(distribution, staged)
            for root, _, files in os.walk(staging):
                target = os.path.join(directory, os.path.relpath(root, staging))
                os.makedirs(target, exist_ok=True)
                for file in files:
                    os.replace(os.path.join(root, file), os.path.join(target, file))
        finally:
            shutil.rmtree(staging, ignore_errors=True)
    """.trimIndent()

    private val urlCredentials = Regex("(?<=://)[^/@\\s]+@")

    /** Replaces the credentials of the URLs in the [text] with `****`. */
    internal fun redactCredentials(text: String): String = text.replace(urlCredentials, "****@")

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
