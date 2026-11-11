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
                        // within the lock, as another process could be installing another version meanwhile
                        removeReplacedDistInfo(directory.toString())
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
                pipCommand(
                    "install", "--quiet",
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

    /**
     * Removes from the dependency [directory] the dist-info directories of the distributions
     * replaced by another version, which `pip install --target --upgrade` leaves beside the installed one,
     * so that the [PLAN] does not judge a requirement by them, see [INSTALLED_DIST_INFO].
     *
     * @throws PythonDependencyException if they cannot be removed.
     */
    internal fun removeReplacedDistInfo(directory: String) {
        val result = runProcess(
            pythonCommand(PythonEnvironment.executable, REMOVE_REPLACED_DIST_INFO, directory),
            mergeErrorStream = false
        )
        if (result.exitCode != 0) {
            throw PythonDependencyException(
                "Cannot remove the dist-info of replaced distributions from $directory:\n${result.failureOutput}"
            )
        }
    }

    private fun findOnPath(name: String): String? = System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.flatMap { listOf(Path(it, name), Path(it, "$name.exe")) }
        ?.firstOrNull { it.isExecutable() }
        ?.toString()

    /**
     * The Python functions telling which of several dist-info directories of a distribution in the dependency directory
     * belongs to its installed files, shared by the [PLAN] and [REMOVE_REPLACED_DIST_INFO].
     *
     * `pip install --target --upgrade` replaces the files of a distribution installed in another version,
     * but leaves its dist-info beside the one of the new version,
     * and `importlib.metadata` would find whichever of them the directory lists first.
     * The installed one is the one whose recorded files are all on disk with their recorded hashes,
     * and of several such ones, when the files are the same in both versions, e.g. of a metapackage,
     * the one whose `RECORD` was written last, as it was installed last;
     * otherwise, e.g. without `RECORD`, it cannot be told.
     */
    @Language("python")
    private val INSTALLED_DIST_INFO = """
        import base64, hashlib, os, re
        import importlib.metadata as metadata
        from pathlib import Path

        def file_digests(path, algorithms):
            # in one pass over the file for all the algorithms
            try:
                digests = {algorithm: hashlib.new(algorithm) for algorithm in algorithms}
                with open(path, "rb") as file:
                    for chunk in iter(lambda: file.read(1 << 20), b""):
                        for digest in digests.values():
                            digest.update(chunk)
                return {algorithm: digest.hexdigest() for algorithm, digest in digests.items()}
            except (OSError, ValueError):
                return None

        def normalized_name(name):
            return re.sub(r"[-_.]+", "_", name).lower()

        def dist_infos(directory):
            # the dist-info directories by the normalized name of their distribution,
            # taken from the directory name, like importlib.metadata finds them
            found = {}
            try:
                entries = sorted(os.listdir(directory))
            except OSError:
                return found
            for entry in entries:
                path = os.path.join(directory, entry)
                if entry.lower().endswith(".dist-info") and os.path.isdir(path):
                    found.setdefault(normalized_name(entry[:-len(".dist-info")].partition("-")[0]), []).append(path)
            return found

        def has_recorded_files(path):
            try:
                files = metadata.PathDistribution(Path(path)).files
                if not files:
                    return False
                for file in files:
                    # the bytecode is recorded without a hash, or can be compiled again
                    if file.hash is None or file.suffix == ".pyc":
                        continue
                    algorithm = file.hash.mode
                    if algorithm not in hashlib.algorithms_guaranteed or algorithm.startswith("shake_"):
                        return False
                    digest = file_digests(file.locate(), [algorithm])
                    if digest is None:
                        return False
                    encoded = base64.urlsafe_b64encode(bytes.fromhex(digest[algorithm])).rstrip(b"=").decode()
                    if encoded != file.hash.value:
                        return False
                return True
            except (OSError, ValueError):
                return False

        def record_time(path):
            try:
                return os.stat(os.path.join(path, "RECORD")).st_mtime_ns
            except OSError:
                return None

        def installed_dist_info(paths):
            # the one of the dist-info directories of a distribution which belongs to its installed files,
            # or None, if it cannot be told
            if len(paths) == 1:
                return paths[0]
            times = {path: record_time(path) for path in paths if has_recorded_files(path)}
            times = {path: time for path, time in times.items() if time is not None}
            latest = [path for path, time in times.items() if time == max(times.values())]
            return latest[0] if len(latest) == 1 else None
    """.trimIndent()

    /**
     * Removes the dist-info directories of the dependency directory, its argument,
     * which do not belong to the installed files of their distribution, see [INSTALLED_DIST_INFO],
     * keeping all of them when it cannot be told, and never the files of a distribution.
     */
    @Language("python")
    private val REMOVE_REPLACED_DIST_INFO = INSTALLED_DIST_INFO + "\n\n" + """
        import shutil, sys

        for paths in dist_infos(sys.argv[1]).values():
            installed = installed_dist_info(paths)
            if installed is not None:
                for path in paths:
                    if path != installed:
                        # a link, not the directory it points to
                        os.unlink(path) if os.path.islink(path) else shutil.rmtree(path)
    """.trimIndent()

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
    internal val PLAN = INSTALLED_DIST_INFO + "\n\n" + """
        import json, sys

        # before any import which can fail, so that the input is always read
        request = json.loads(sys.stdin.read())

        import importlib
        from urllib.parse import parse_qsl, urlsplit, urlunsplit

        directory, imported = sys.argv[1], set(request["modules"])
        sys.path.append(directory)
        dependency_location = os.path.realpath(directory)
        dependency_dist_infos = dist_infos(directory)
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
                if requirement.url is not None:
                    url_dependencies.add(dependency)
                name = requirement.name
            distribution = find_distribution(name)
            if distribution is None:
                return True
            if Requirement is None:
                return specified
            if requirement.url is not None:
                # the dependencies of the extras are not checked (#47), so they are installed again with them
                return bool(requirement.extras) or not is_installed_from(requirement.url, distribution)
            return not requirement.specifier.contains(distribution.version, prereleases=True)

        def find_distribution(name):
            # like importlib.metadata, but of several dist-info of the name in the dependency directory,
            # the one of the installed files, or None, when it cannot be told
            try:
                distribution = metadata.distribution(name)
            except metadata.PackageNotFoundError:
                return None
            if os.path.realpath(distribution.locate_file("")) != dependency_location:
                return distribution
            paths = dependency_dist_infos.get(normalized_name(name), [])
            if len(paths) < 2:
                return distribution
            path = installed_dist_info(paths)
            return None if path is None else metadata.PathDistribution(Path(path))

        def without_credentials(url):
            # direct_url.json holds the URL without credentials, and without the fragment
            parts = urlsplit(url)
            return urlunsplit((parts.scheme.lower(), parts.netloc.rpartition("@")[2].lower(), parts.path, parts.query, ""))

        def is_unmodified_since_uv_install(path, distribution):
            # uv records the change time of a local archive in uv_cache.json, the last write time on Windows,
            # not its hash
            try:
                timestamp = json.loads(distribution.read_text("uv_cache.json") or "null")["timestamp"]
                installed_time = timestamp["secs_since_epoch"] * 1_000_000_000 + timestamp["nanos_since_epoch"]
                status = os.stat(path)
                return (status.st_mtime_ns if os.name == "nt" else status.st_ctime_ns) == installed_time
            except (OSError, ValueError, TypeError, KeyError):
                return False

        def is_installed_from(url, distribution):
            # compared with the direct_url.json of the distribution, PEP 610, without hitting the network
            try:
                installed = json.loads(distribution.read_text("direct_url.json") or "null")
            except ValueError:
                return False
            if not isinstance(installed, dict) or not isinstance(installed.get("url"), str):
                return False
            url, _, fragment = url.partition("#")
            options = dict(parse_qsl(fragment))
            if options.pop("subdirectory", None) != installed.get("subdirectory"):
                return False
            vcs, plus, _ = urlsplit(url).scheme.partition("+")
            vcs_info = installed.get("vcs_info")
            if plus and vcs in ("git", "hg", "svn", "bzr"):
                # a branch or a tag is satisfied by the commit installed for it before, as the snippet requests
                # the same thing, and the latest commit cannot be known without the network
                parts = urlsplit(url[len(vcs) + 1:])
                path, at, revision = parts.path.rpartition("@")
                if not at:
                    path, revision = parts.path, None
                if not isinstance(vcs_info, dict) or vcs_info.get("vcs") != vcs:
                    return False
                installed_url = installed["url"]
                if installed_url.startswith(vcs + "+"):
                    installed_url = installed_url[len(vcs) + 1:]
                if without_credentials(parts._replace(path=path).geturl()) != without_credentials(installed_url):
                    return False
                if revision is not None and re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", revision):
                    return vcs_info.get("commit_id") == revision
                return vcs_info.get("requested_revision") == revision
            archive_info = installed.get("archive_info")
            if vcs_info is not None or not isinstance(archive_info, dict):
                # a local directory can change without changing its URL, so it is always installed again
                return False
            if without_credentials(url) != without_credentials(installed["url"]):
                return False
            # pip records the hashes, also when not requested, while uv records none,
            # but keeps the hash fragment in the URL of a local archive
            hashes = archive_info.get("hashes")
            hashes = dict(hashes) if isinstance(hashes, dict) else {}
            legacy_hash = archive_info.get("hash")
            if isinstance(legacy_hash, str) and "=" in legacy_hash:
                hashes.setdefault(*legacy_hash.split("=", 1))
            for algorithm, value in parse_qsl(installed["url"].partition("#")[2]):
                # the recorded hashes are the ones verified on installation
                hashes.setdefault(algorithm, value)
            # a shake digest has no fixed length to compute and compare
            if any(name.startswith("shake_") for name in options):
                return False
            algorithms = {name for name in hashlib.algorithms_guaranteed if not name.startswith("shake_")}
            hashes = {name: str(value).lower() for name, value in hashes.items() if name in algorithms}
            requested = {name: value.lower() for name, value in options.items() if name in algorithms}
            if any(name in hashes and hashes[name] != value for name, value in requested.items()):
                return False
            if urlsplit(url).scheme.lower() != "file":
                # a remote archive is trusted to keep its content under the same URL,
                # as uv records no hash of it, not even the requested one,
                # but a requested hash of another algorithm than the ones pip recorded cannot be verified
                return not hashes or all(name in hashes for name in requested)
            # imported only here, as it imports http.client, email and ssl
            from urllib.request import url2pathname
            # a local archive can be rebuilt without changing its URL, so its content is compared
            path = url2pathname(urlsplit(url).path)
            if hashes:
                expected = {**hashes, **requested}
                return file_digests(path, expected) == expected
            return not requested and is_unmodified_since_uv_install(path, distribution)

        # the URL requirements whose marker applies, collected by is_missing
        url_dependencies = set()
        missing = [dependency for dependency in dependencies if is_missing(dependency)]
        if missing:
            # pip resolves the missing ones without the dependency directory, so it would install a distribution
            # required by them from the index over the one installed from a URL, also in the same version
            missing = [dependency for dependency in dependencies if dependency in missing or dependency in url_dependencies]
            distributions_of = metadata.packages_distributions()
            in_use = {name for module in imported for name in distributions_of.get(module, [])}
            constraints = {}
            for distribution in metadata.distributions():
                name = distribution.metadata["Name"]
                in_dependencies = os.path.realpath(distribution.locate_file("")) == dependency_location
                if name and name not in constraints and (not in_dependencies or name in in_use):
                    if in_dependencies:
                        # not the version of a dist-info left by a replaced version
                        distribution = find_distribution(name)
                    if distribution is not None:
                        constraints[name] = distribution.version
            for dependency in missing:
                print("install", dependency)
            for name, version in constraints.items():
                print("constraint", f"{name}=={version}")
    """.trimIndent()

}
