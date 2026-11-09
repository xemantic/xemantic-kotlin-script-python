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

import com.xemantic.kotlin.test.assert
import jep.Interpreter
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readBytes
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PythonDependenciesTest {

    @Test
    fun `should find no metadata in code without metadata block`() {
        // when
        val metadata = PythonDependencies.scriptMetadata("x = 1")

        // then
        assert(metadata == null)
    }

    @Test
    fun `should find metadata of script block`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            import sys
            # /// script
            # requires-python = ">=3.11"
            #
            # dependencies = ["numpy"]
            # ///
            print(sys.argv)
            """.trimIndent()
        )

        // then
        assert(metadata == "requires-python = \">=3.11\"\n\ndependencies = [\"numpy\"]\n")
    }

    @Test
    fun `should find metadata of script block ending the code`() {
        // when
        val metadata = PythonDependencies.scriptMetadata("# /// script\n# dependencies = []\n# ///")

        // then
        assert(metadata == "dependencies = []\n")
    }

    @Test
    fun `should find metadata of script block with CRLF line endings`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            "import sys\r\n# /// script\r\n#\r\n# dependencies = [\"numpy\"]\r\n# ///\r\nprint(sys.argv)\r\n"
        )

        // then
        assert(metadata == "\ndependencies = [\"numpy\"]\n")
    }

    @Test
    fun `should split metadata content only at line feeds`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            "# /// script\n# title = \"a\u000cb\u0085c d\"\n# ///\n"
        )

        // then
        // str.splitlines of the reference implementation would also break the TOML string at these characters
        assert(metadata == "title = \"a\u000cb\u0085c d\"\n")
    }

    @Test
    fun `should not wait for other installation when code has no metadata`() {
        // given
        val interpreter = Proxy.newProxyInstance(
            Interpreter::class.java.classLoader, arrayOf(Interpreter::class.java)
        ) { _, method, _ -> error("Unexpected call of Interpreter.${method.name}") } as Interpreter

        // when
        val state = synchronized(PythonDependencies) {
            // as if another thread was installing dependencies
            val installer = thread { PythonDependencies.install("x = 1", interpreter) }
            while (installer.state != Thread.State.BLOCKED && installer.state != Thread.State.TERMINATED) {
                Thread.onSpinWait()
            }
            installer.state
        }

        // then
        assert(state == Thread.State.TERMINATED)
    }

    @Test
    fun `should find no metadata for lone script marker line`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            open('t.py', 'w').write('''
            # /// script
            ''')
            """.trimIndent()
        )

        // then
        assert(metadata == null)
    }

    @Test
    fun `should find no metadata for unclosed script block`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            # /// script
            # dependencies = ["numpy"]
            x = 1
            # ///
            """.trimIndent()
        )

        // then
        assert(metadata == null)
    }

    @Test
    fun `should find no metadata for script block without content`() {
        // when
        val metadata = PythonDependencies.scriptMetadata("# /// script\n# ///\n")

        // then
        assert(metadata == null)
    }

    @Test
    fun `should end script block at last closing line of consecutive comments`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            # /// script
            # dependencies = ["numpy"]
            # ///
            # embedded = '''
            # ///
            # '''
            # ///
            # a comment
            """.trimIndent()
        )

        // then
        assert(metadata == "dependencies = [\"numpy\"]\n///\nembedded = '''\n///\n'''\n")
    }

    @Test
    fun `should find no metadata for script block within block of other type`() {
        // when
        val metadata = PythonDependencies.scriptMetadata(
            """
            # /// other
            # /// script
            # dependencies = ["numpy"]
            # ///
            """.trimIndent()
        )

        // then
        assert(metadata == null)
    }

    @Test
    fun `should fail on multiple script blocks`() {
        // when
        val exception = assertFailsWith<PythonDependencyException> {
            PythonDependencies.scriptMetadata(
                """
                # /// script
                # dependencies = ["numpy"]
                # ///
                x = 1
                # /// script
                # dependencies = ["pandas"]
                # ///
                """.trimIndent()
            )
        }

        // then
        assert(exception.message == "Multiple '# /// script' metadata blocks, only one is allowed")
    }

    @Test
    fun `should find no metadata in many script marker lines without backtracking`() {
        // given
        // a backtracking regex retries all the following lines for each marker, hanging for hours,
        // and a recursive one overflows the stack
        val code = "# /// script\n".repeat(200_000)

        // when
        val metadata = PythonDependencies.scriptMetadata(code)

        // then
        assert(metadata == null)
    }

    @Test
    fun `should plan with more imported modules than fit on command line`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        // about 6 MB of names, more than ARG_MAX of macOS and Linux, and the 32767 characters of Windows,
        // and enough to hang if the distributions of the environment are scanned again for each module
        val imported = List(300_000) { "imported_module_$it" }

        // when
        val plan = PythonDependencies.plan("dependencies = [\"nonexistent-dependency\"]\n", directory, imported)

        // then
        assert(("install" to "nonexistent-dependency") in plan)
    }

    @Test
    fun `should plan for metadata with quotes, backslashes, line breaks and non-ASCII characters`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        val metadata = "title = \"\\\"q\\\" \\\\ \u00fc\u2028 \uD83D\uDE00 \\u0001\"\r\n" +
            "dependencies = [\n  \"nonexistent-dependency\",\n]\n"

        // when
        val plan = PythonDependencies.plan(metadata, directory, listOf("sys", "\u00fcber"))

        // then
        assert(plan.filter { it.first == "install" } == listOf("install" to "nonexistent-dependency"))
    }

    @Test
    fun `should plan without tomllib of Python before 3_11`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        // None in sys.modules makes the import fail with ModuleNotFoundError, like on Python 3.10,
        // and without tomli, the copy vendored by pip is used
        val code = "import sys; sys.modules.update(dict.fromkeys(['tomllib', 'tomli'], None))\n" +
            PythonDependencies.PLAN

        // when
        val (exitCode, output) = runProcess(
            listOf(PythonEnvironment.executable, "-c", code, directory),
            input = """{"metadata": "dependencies = [\"nonexistent-dependency\"]\n", "modules": []}"""
        )

        // then
        assert(exitCode == 0 && "install nonexistent-dependency" in output.lines())
    }

    @Test
    fun `should fail plan on invalid metadata without tomllib of Python before 3_11`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        val code = "import sys; sys.modules.update(dict.fromkeys(['tomllib', 'tomli'], None))\n" +
            PythonDependencies.PLAN

        // when
        val (exitCode, output) = runProcess(
            listOf(PythonEnvironment.executable, "-c", code, directory),
            input = """{"metadata": "dependencies = \n", "modules": []}"""
        )

        // then
        assert(exitCode != 0 && output.startsWith("Invalid '# /// script' metadata: "))
    }

    @Test
    fun `should fail plan with clear message without any TOML parser`() {
        // given
        PythonEnvironment.initialize()
        val directory = PythonEnvironment.dependencyDirectory.toString()
        val code = "import sys; sys.modules.update(dict.fromkeys(['tomllib', 'tomli', 'pip'], None))\n" +
            PythonDependencies.PLAN

        // when
        val (exitCode, output) = runProcess(
            listOf(PythonEnvironment.executable, "-c", code, directory),
            input = """{"metadata": "dependencies = [\"nonexistent-dependency\"]\n", "modules": []}"""
        )

        // then
        assert(exitCode != 0 && output == PythonDependencies.NO_TOML_PARSER_MESSAGE)
    }

    @Test
    fun `should plan git dependency as installed for the same URL and revision`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""

        // when
        val installs = planInstalls("direct-url-probe @ git+https://example.com/probe.git@v1", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan git dependency as installed for the same URL without revision`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT"}}"""

        // when
        val installs = planInstalls("direct-url-probe @ git+https://example.com/probe.git", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan git dependency as installed for the installed commit`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "main"}}"""

        // when
        val installs = planInstalls("direct-url-probe @ git+https://example.com/probe.git@$COMMIT", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan git dependency as installed with credentials and subdirectory`() {
        // given
        // direct_url.json holds the URL without credentials, and the subdirectory separately
        val directUrl = """{"url": "https://example.com/probe.git", "subdirectory": "python", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""

        // when
        val installs = planInstalls(
            "direct-url-probe @ git+https://user:secret@example.com/probe.git@v1#subdirectory=python", directUrl
        )

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan git dependency as missing for another revision of the same version`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/probe.git@feature"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan git dependency as missing for another commit`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "$COMMIT"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/probe.git@${COMMIT.reversed()}"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan git dependency as missing for another repository`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/fork/probe.git@v1"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan git dependency as missing for another subdirectory`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/probe.git@v1#subdirectory=python"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan archive dependency as installed for the same URL`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {}}"""

        // when
        val installs = planInstalls("direct-url-probe @ https://user:secret@example.com/probe-1.0.tar.gz", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan archive dependency as installed for the same URL and hash`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {"hash": "sha256=$HASH", "hashes": {"sha256": "$HASH"}}}"""

        // when
        val installs = planInstalls("direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha256=$HASH", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan archive dependency as installed for the same legacy hash`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {"hash": "sha256=$HASH"}}"""

        // when
        val installs = planInstalls("direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha256=$HASH", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @Test
    fun `should plan archive dependency as missing for another hash`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {"hashes": {"sha256": "$HASH"}}}"""
        val dependency = "direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha256=${HASH.reversed()}"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan local archive dependency as missing for hash not recorded on installation`() {
        // given
        val directUrl = """{"url": "file:///home/user/probe-1.0.tar.gz", "archive_info": {}}"""
        val dependency = "direct-url-probe @ file:///home/user/probe-1.0.tar.gz#sha256=$HASH"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan archive dependency as missing for another URL`() {
        // given
        val directUrl = """{"url": "https://example.com/probe-1.0.tar.gz", "archive_info": {}}"""
        val dependency = "direct-url-probe @ https://example.com/probe-1.1.tar.gz"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan archive dependency as missing when installed from git`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT"}}"""
        val dependency = "direct-url-probe @ https://example.com/probe.git"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency as installed for unchanged content`() {
        // given
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("archive content")
            val url = archive.toUri().toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            val directUrl = """{"url": "$url", "archive_info": {"hashes": {"sha256": "$hash"}}}"""

            // when
            val installs = planInstalls("direct-url-probe @ $url", directUrl)

            // then
            assert(installs.isEmpty())
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency as missing for changed content`() {
        // given
        // a local archive can be rebuilt in the same version, without changing its URL
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("rebuilt archive content")
            val dependency = "direct-url-probe @ ${archive.toUri()}"
            val directUrl = """{"url": "${archive.toUri()}", "archive_info": {"hashes": {"sha256": "$HASH"}}}"""

            // when
            val installs = planInstalls(dependency, directUrl)

            // then
            assert(installs == listOf(dependency))
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency installed by uv as installed for unchanged change time`() {
        // given
        // uv records no hash of a local archive, but its change time in uv_cache.json, the last write time on Windows
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("archive content")
            // a modification time other than the change time
            archive.setLastModifiedTime(FileTime.from(Instant.ofEpochSecond(1_700_000_000, 123_456_700)))
            val url = archive.toUri().toString()
            val directUrl = """{"url":"$url","archive_info":{}}"""
            val uvCache = uvCache(uvTimestamp(archive))

            // when
            val installs = planInstalls("direct-url-probe @ $url", directUrl, uvCache)

            // then
            assert(installs.isEmpty())
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency installed by uv as missing for changed change time`() {
        // given
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("rebuilt archive content")
            val dependency = "direct-url-probe @ ${archive.toUri()}"
            val directUrl = """{"url":"${archive.toUri()}","archive_info":{}}"""
            // as if installed before the archive was rebuilt
            val uvCache = uvCache(uvTimestamp(archive).minusSeconds(1))

            // when
            val installs = planInstalls(dependency, directUrl, uvCache)

            // then
            assert(installs == listOf(dependency))
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @Test
    fun `should plan local archive dependency as missing without recorded hash or modification time`() {
        // given
        val directUrl = """{"url": "file:///home/user/probe-1.0.tar.gz", "archive_info": {}}"""
        val dependency = "direct-url-probe @ file:///home/user/probe-1.0.tar.gz"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan hashed local archive dependency installed by uv as installed`() {
        // given
        // uv keeps the hash fragment in the URL of a local archive, and records no hashes
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("archive content")
            val hash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            val url = "${archive.toUri()}#sha256=$hash"
            val directUrl = """{"url":"$url","archive_info":{}}"""

            // when
            val installs = planInstalls("direct-url-probe @ $url", directUrl)

            // then
            assert(installs.isEmpty())
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @Test
    fun `should plan hashed remote archive dependency installed by uv as installed`() {
        // given
        // uv drops the hash fragment from the URL of a remote archive, and records no hashes
        val directUrl = """{"url":"https://example.com/probe-1.0.tar.gz","archive_info":{}}"""

        // when
        val installs = planInstalls("direct-url-probe @ https://example.com/probe-1.0.tar.gz#sha256=$HASH", directUrl)

        // then
        assert(installs.isEmpty())
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should plan local archive dependency with unverifiable hash as missing`() {
        // given
        // a shake digest has no fixed length to compare
        val archiveDirectory = createTempDirectory("python-archive")
        try {
            val archive = archiveDirectory.resolve("probe-1.0.tar.gz")
            archive.writeText("archive content")
            val url = "${archive.toUri()}#shake_256=$HASH"
            val dependency = "direct-url-probe @ $url"
            val directUrl = """{"url":"$url","archive_info":{}}"""

            // when
            val installs = planInstalls(dependency, directUrl)

            // then
            assert(installs == listOf(dependency))
        } finally {
            archiveDirectory.deleteRecursively()
        }
    }

    @Test
    fun `should plan installed URL dependency for installation with missing dependency`() {
        // given
        // pip resolves the missing dependency without the dependency directory,
        // so a distribution required by it would be installed from the index over the one installed from the URL
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe @ git+https://example.com/probe.git@v1"

        // when
        val installs = planInstalls(dependency, directUrl, otherDependencies = listOf("nonexistent-dependency"))

        // then
        assert(installs == listOf(dependency, "nonexistent-dependency"))
    }

    @Test
    fun `should plan URL dependency with extras as missing`() {
        // given
        // the dependencies of the extras are not checked, see #47
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT", "requested_revision": "v1"}}"""
        val dependency = "direct-url-probe[extra] @ git+https://example.com/probe.git@v1"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan local directory dependency as missing even for the same URL`() {
        // given
        // the content of a local directory can change without changing its URL
        val directUrl = """{"url": "file:///home/user/probe", "dir_info": {}}"""
        val dependency = "direct-url-probe @ file:///home/user/probe"

        // when
        val installs = planInstalls(dependency, directUrl)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan URL dependency as missing when installed from index`() {
        // given
        val dependency = "direct-url-probe @ https://example.com/probe-1.0.tar.gz"

        // when
        val installs = planInstalls(dependency, directUrl = null)

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan URL dependency as missing for invalid direct_url_json`() {
        // given
        val dependency = "direct-url-probe @ https://example.com/probe-1.0.tar.gz"

        // when
        val installs = planInstalls(dependency, directUrl = "not JSON")

        // then
        assert(installs == listOf(dependency))
    }

    @Test
    fun `should plan version dependency as installed when installed from URL`() {
        // given
        val directUrl = """{"url": "https://example.com/probe.git", "vcs_info": {"vcs": "git", "commit_id": "$COMMIT"}}"""

        // when
        val installs = planInstalls("direct-url-probe>=1.0", directUrl)

        // then
        assert(installs.isEmpty())
    }

    /**
     * The requirements to install, planned for the [dependency]
     * with the dependency directory holding `direct-url-probe 1.0` installed from the [directUrl] (PEP 610),
     * or from an index when `null`.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun planInstalls(
        dependency: String,
        directUrl: String?,
        uvCache: String? = null,
        otherDependencies: List<String> = emptyList()
    ): List<String> {
        PythonEnvironment.initialize()
        val directory = createTempDirectory("python-dependencies")
        try {
            val distInfo = directory.resolve("direct_url_probe-1.0.dist-info").createDirectories()
            distInfo.resolve("METADATA").writeText("Metadata-Version: 2.1\nName: direct-url-probe\nVersion: 1.0\n")
            if (directUrl != null) distInfo.resolve("direct_url.json").writeText(directUrl)
            if (uvCache != null) distInfo.resolve("uv_cache.json").writeText(uvCache)
            val dependencies = (listOf(dependency) + otherDependencies).joinToString(", ") { "'$it'" }
            return PythonDependencies.plan("dependencies = [$dependencies]\n", directory.toString(), emptyList())
                .filter { it.first == "install" }
                .map { it.second }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** The time of the [archive] which uv records in `uv_cache.json`: its change time, or last write time on Windows. */
    private fun uvTimestamp(archive: Path): Instant =
        if (System.getProperty("os.name").startsWith("Windows")) archive.getLastModifiedTime().toInstant()
        else (Files.getAttribute(archive, "unix:ctime") as FileTime).toInstant()

    private fun uvCache(timestamp: Instant): String =
        """{"timestamp":{"secs_since_epoch":${timestamp.epochSecond},"nanos_since_epoch":${timestamp.nano}},""" +
            """"commit":null,"tags":null,"env":{},"directories":{}}"""

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        const val HASH = "427f82ccf8f86e95585ffde082ac93c543fa3582c45b40b303148abd537d002e"
    }

}
