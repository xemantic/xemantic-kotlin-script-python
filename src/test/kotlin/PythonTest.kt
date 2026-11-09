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
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import jep.JepException
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PythonTest {

    private val python = Python()

    @AfterTest
    fun closePython() {
        python.close()
    }

    @Test
    fun `should evaluate expression`() {
        // when
        val long = python.eval<Long>("2 + 3")
        val int = python.eval<Int>("2 + 3")
        val string = python.eval<String>("'foo' + 'bar'")

        // then
        assert(long == 5L)
        assert(int == 5)
        assert(string == "foobar")
    }

    @Test
    fun `should return value of code ending with expression`() {
        // when
        val result = python("[n * n for n in range(4)]")

        // then
        assert(result == listOf(0L, 1L, 4L, 9L))
    }

    @Test
    fun `should return null for code ending with statement`() {
        // when
        val result = python("x = 42")

        // then
        assert(result == null)
        assert(python["x"] == 42L)
    }

    @Test
    fun `should return null for expression evaluating to None`() {
        // when
        val result = python("[].append(1)")

        // then
        assert(result == null)
    }

    @Test
    fun `should execute statements before last expression`() {
        // when
        val result = python(
            """
            x = 2
            def triple(n):
                return n * 3
            triple(x)
            """.trimIndent()
        )

        // then
        assert(result == 6L)
    }

    @Test
    fun `should execute statements before last expression on the same line`() {
        // when
        val result = python("import math; r = 2; 'é' + str(math.floor(math.pi * r))")

        // then
        assert(result == "é6")
    }

    @Test
    fun `should return multi-line last expression`() {
        // when
        val result = python(
            """
            base = 10
            (base +
                5)
            """.trimIndent()
        )

        // then
        assert(result == 15L)
    }

    @Test
    fun `should return repr of value not convertible to Java`() {
        // when
        val result = python(
            """
            class Point:
                def __repr__(self):
                    return 'Point(1, 2)'
            Point()
            """.trimIndent()
        )

        // then
        assert(result == "Point(1, 2)")
    }

    @Test
    fun `should report line of failing last expression`() {
        // when
        val exception = assertFailsWith<JepException> {
            python(
                """
                x = 1
                undefined_name
                """.trimIndent()
            )
        }

        // then
        exception should {
            have(message!!.contains("NameError"))
            assert(stackTrace.first().toString() == "<string>.<module>(<string>:2)")
        }
    }

    @Test
    fun `should report syntax error`() {
        // when
        val exception = assertFailsWith<JepException> {
            python("x = (1")
        }

        // then
        assert(exception.message!!.contains("SyntaxError"))
    }

    @Test
    fun `should keep state between calls`() {
        // given
        python.exec(
            """
            x = 21
            def double(n):
                return n * 2
            """.trimIndent()
        )

        // when
        val result = python.eval<Int>("double(x)")

        // then
        assert(result == 42)
    }

    @Test
    fun `should set and get variables`() {
        // given
        python["greeting"] = "Hello"

        // when
        python.exec("message = greeting + ' World'")

        // then
        assert(python["message"] == "Hello World")
    }

    @Test
    fun `should convert list to Kotlin list`() {
        // when
        val list = python.eval<List<Long>>("[n * n for n in range(4)]")

        // then
        assert(list == listOf(0L, 1L, 4L, 9L))
    }

    @Test
    fun `should isolate state of separate instances`() {
        Python().use { other ->
            // given
            python.exec("x = 1")

            // when
            val defined = other.eval<Boolean>("'x' in globals()")

            // then
            assert(!defined)
        }
    }

    @Test
    fun `should be callable from any thread`() {
        // given
        python.exec("x = 1")

        // when
        var result: Long? = null
        thread { result = python.eval<Long>("x + 1") }.join()

        // then
        assert(result == 2L)
    }

    @Test
    fun `should call Kotlin function from Python`() {
        // given
        python["double"] = { n: Long -> n * 2 }

        // when
        val result = python.eval<Long>("double(21)")

        // then
        assert(result == 42L)
    }

    @Test
    fun `should call back Python from Kotlin function called from Python`() {
        // given
        python.exec("factor = 3")
        python["scale"] = { n: Long -> n * python.eval<Long>("factor") }

        // when
        val result = python.eval<Long>("scale(7)")

        // then
        assert(result == 21L)
    }

    @Test
    fun `should report Python exception`() {
        // when
        val exception = assertFailsWith<JepException> {
            python.eval<Any>("undefined_name")
        }

        // then
        assert(exception.message!!.contains("NameError"))
    }

    @Test
    fun `should keep working after Python exception`() {
        // given
        python.exec("x = 1")
        runCatching { python.exec("raise ValueError('boom')") }

        // when
        val result = python.eval<Long>("x")

        // then
        assert(result == 1L)
    }

    @Test
    fun `should import native extension module`() {
        // given
        python.exec(
            """
            # /// script
            # dependencies = ["numpy"]
            # ///
            import numpy as np
            """.trimIndent()
        )

        // when
        val sum = python.eval<Long>("int(np.arange(10).sum())")

        // then
        assert(sum == 45L)
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should fail dependency conflicting with requirement of distribution of dependency directory`() {
        // given
        val directory = PythonEnvironment.dependencyDirectory!!.createDirectories()
        val wheels = createTempDirectory("python-wheels")
        try {
            // installed by an earlier snippet, not imported
            directory.writeDistribution("issue28-conflict-dependent", "1.0", "issue28-conflict-dependency<2")
            directory.writeDistribution("issue28-conflict-dependency", "1.0")
            // a local wheel, so that the installer needs no index to find the conflict
            val wheel = wheels.writeWheel("issue28-conflict-dependency", "2.0")

            // when
            val exception = assertFailsWith<PythonDependencyException> {
                python.exec(
                    """
                    # /// script
                    # dependencies = ["issue28-conflict-dependency @ ${wheel.toUri()}"]
                    # ///
                    """.trimIndent()
                )
            }

            // then
            val message = exception.message!!
            assert("issue28-conflict-dependency" in message)
            assert(
                "The dependency directory $directory keeps the distributions it holds compatible" in message
            )
        } finally {
            directory.listDirectoryEntries("issue28_conflict_*").forEach { it.deleteRecursively() }
            wheels.deleteRecursively()
        }
    }

    /** Writes a wheel of a distribution without modules into this directory. */
    private fun Path.writeWheel(name: String, version: String): Path {
        val distribution = name.replace('-', '_')
        val distInfo = "$distribution-$version.dist-info"
        val wheel = resolve("$distribution-$version-py3-none-any.whl")
        ZipOutputStream(wheel.outputStream()).use { zip ->
            mapOf(
                "$distInfo/METADATA" to "Metadata-Version: 2.1\nName: $name\nVersion: $version\n",
                "$distInfo/WHEEL" to "Wheel-Version: 1.0\nGenerator: test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
                "$distInfo/RECORD" to "$distInfo/METADATA,,\n$distInfo/WHEEL,,\n$distInfo/RECORD,,\n"
            ).forEach { (entry, content) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return wheel
    }

    @Test
    fun `should run code with lone script metadata marker line`() {
        // given
        python.exec(
            """
            template = '''
            # /// script
            '''
            """.trimIndent()
        )

        // when
        val template = python.eval<String>("template")

        // then
        assert(template == "\n# /// script\n")
    }

    @Test
    fun `should fail when used after close`() {
        // given
        python.exec("x = 1")
        python.close()

        // when
        val exception = assertFailsWith<IllegalStateException> {
            python.exec("x = 2")
        }

        // then
        assert(exception.message == "Python is closed")
    }

    @Test
    fun `should allow closing twice`() {
        // given
        python.close()

        // when
        python.close()

        // then
        // no exception
    }

    @Test
    fun `should install dependencies into directory of system property`() {
        // when
        python.exec("x = 1")

        // then
        val property = System.getProperty("xemantic.kotlin.script.python.dependencyDirectory")
        assert(property != null)
        assert(PythonEnvironment.dependencyDirectory == Path(property))
    }

    @Test
    fun `should search modules in the same paths as the Python executable`() {
        // given
        val executable = Path(System.getenv("VIRTUAL_ENV"), "bin", "python")
        val process = ProcessBuilder(executable.toString(), "-c", "import sys; print(*sys.path[1:], sep='\\n')").start()
        val executablePath = process.inputStream.bufferedReader().readLines()
        process.waitFor()

        // when
        val path = python.eval<List<String>>("__import__('sys').path")

        // then
        // the dependency directory is added once any snippet of this JVM declares dependencies
        val dependencyDirectory = PythonEnvironment.dependencyDirectory.toString()
        assert(path.filter { it != dependencyDirectory } == executablePath)
    }

}
