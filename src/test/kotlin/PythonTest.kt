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
import jep.python.PyObject
import kotlin.concurrent.thread
import kotlin.io.path.Path
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
    fun `should return repr of values not convertible to Java nested in collections`() {
        // given
        python.exec(
            """
            class Point:
                def __repr__(self):
                    return 'Point'
            """.trimIndent()
        )

        // when
        val list = python("[Point(), [Point()]]")
        val tuple = python("(Point(), 1)")
        val dict = python("{'p': Point(), 'nested': {'q': Point()}}")

        // then
        assert(list == listOf("Point", listOf("Point")))
        assert(tuple == listOf("Point", 1L))
        assert(dict == mapOf("p" to "Point", "nested" to mapOf("q" to "Point")))
    }

    @Test
    fun `should get repr of global variable not convertible to Java`() {
        // given
        python.exec("import math")

        // when
        val math = python["math"]

        // then
        // math is either built in, or loaded from a file, depending on how Python was built
        assert(math is String && math.startsWith("<module 'math'"))
    }

    @Test
    fun `should get repr of values not convertible to Java nested in global variable`() {
        // given
        python.exec("functions = {'all': [len, abs]}")

        // when
        val functions = python["functions"]

        // then
        assert(functions == mapOf("all" to listOf("<built-in function len>", "<built-in function abs>")))
    }

    @Test
    fun `should evaluate to repr of value not convertible to Java`() {
        // when
        val len = python.eval<Any>("len")
        val functions = python.eval<List<Any>>("[len, abs]")
        val byName = python.eval<Map<String, Any>>("{'len': len}")

        // then
        assert(len == "<built-in function len>")
        assert(functions == listOf("<built-in function len>", "<built-in function abs>"))
        assert(byName == mapOf("len" to "<built-in function len>"))
    }

    @Test
    fun `should reject evaluating to PyObject`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            python.eval<PyObject>("len")
        }

        // then
        assert(exception.message == "A PyObject cannot leave the thread of Python")
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
