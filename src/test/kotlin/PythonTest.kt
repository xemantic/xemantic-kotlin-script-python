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
import jep.python.PyCallable
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
    fun `should return repr of dict keys not convertible to Java`() {
        // given
        python.exec(
            """
            class Point:
                def __repr__(self):
                    return 'Point'
            d = {len: 'x'}
            """.trimIndent()
        )

        // when
        val byPoint = python("{Point(): 1, 'other': 2}")
        val byFunction = python("{len: 1}")
        val global = python["d"]

        // then
        assert(byPoint == mapOf("Point" to 1L, "other" to 2L))
        assert(byFunction == mapOf("<built-in function len>" to 1L))
        assert(global == mapOf("<built-in function len>" to "x"))
    }

    @Test
    fun `should reject dict keys of the same repr`() {
        // given
        python.exec(
            """
            class Point:
                def __repr__(self):
                    return 'Point'
            """.trimIndent()
        )

        // when
        val exception = assertFailsWith<IllegalStateException> {
            python("{'Point': 1, Point(): 2}")
        }

        // then
        assert(exception.message == "Different keys of a dict have the same repr: Point")
    }

    @Test
    fun `should report failing repr of value not convertible to Java`() {
        // given
        python.exec(
            """
            class Point:
                def __repr__(self):
                    return 'Point'
            class Broken:
                def __repr__(self):
                    raise ValueError('no repr')
            """.trimIndent()
        )

        // when
        val exception = assertFailsWith<JepException> {
            python("[Point(), Broken(), Point()]")
        }

        // then
        assert(exception.message!!.contains("no repr"))
        assert(python("[Point()]") == listOf("Point"))
    }

    @Test
    fun `should return repr of values not convertible to Java nested in array`() {
        // when
        val array = python.eval("[len, 1]", Array<Any>::class.java)

        // then
        assert(array.toList() == listOf("<built-in function len>", 1L))
    }

    @Test
    fun `should return self-referencing collection set from Kotlin`() {
        // given
        val list = mutableListOf<Any>()
        list.add(list)
        python["l"] = list

        // when
        val result = python["l"]

        // then
        assert(result === list)
    }

    @Test
    fun `should not change Kotlin collection holding Python objects when reading it`() {
        // given
        val list = mutableListOf<Any?>()
        python["k"] = list
        python.exec(
            """
            class Point:
                def __repr__(self):
                    return 'Point'
            k.add(Point())
            k.add(len)
            """.trimIndent()
        )

        // when
        val result = python["k"]

        // then
        // the list holding Python objects is not rendered by power-assert, which cannot do it on this thread
        val copied = result !== list
        val size = list.size
        assert(result == listOf("Point", "<built-in function len>"))
        assert(copied)
        assert(size == 2)
        assert(python("k.get(1)('abc')") == 3L)
        assert(python("k.get(0)") == "Point")
    }

    @Test
    fun `should return repr of the same Python object held twice by Kotlin collection`() {
        // given
        val list = mutableListOf<Any?>()
        python["k"] = list
        python["f"] = { value: Any? ->
            list.add(value)
            list.add(value)
        }
        python.exec(
            """
            class Point:
                def __repr__(self):
                    return 'Point'
            f(Point())
            """.trimIndent()
        )

        // when
        val result = python["k"]

        // then
        assert(result == listOf("Point", "Point"))
    }

    @Test
    fun `should copy self-referencing Kotlin collection holding Python object`() {
        // given
        val list = mutableListOf<Any?>()
        list.add(list)
        python["s"] = list
        python.exec("s.add(len)")

        // when
        val result = python["s"]

        // then
        // the list holding a Python object is not rendered by power-assert, which cannot do it on this thread
        val copied = result !== list
        assert(copied)
        assert(result is List<*> && result[0] === result && result[1] == "<built-in function len>")
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
    fun `should reject evaluating to array of PyObject`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            python.eval("[[len, abs]]", Array<Array<PyCallable>>::class.java)
        }

        // then
        assert(exception.message == "A PyObject cannot leave the thread of Python")
    }

    @Test
    fun `should reject evaluating to PyObject after close as closed`() {
        // given
        python.close()

        // when
        val exception = assertFailsWith<IllegalStateException> {
            python.eval<PyObject>("len")
        }

        // then
        assert(exception.message == "Python is closed")
    }

    @Test
    fun `should reject evaluating Python callable to functional interface`() {
        // when
        val runnable = assertFailsWith<IllegalArgumentException> {
            python.eval("lambda: 1", Runnable::class.java)
        }
        val function = assertFailsWith<IllegalArgumentException> {
            python.eval("str.upper", java.util.function.Function::class.java)
        }

        // then
        assert(
            runnable.message == "A Python object is converted to its repr, which is not a java.lang.Runnable"
        )
        assert(
            function.message == "A Python object is converted to its repr, which is not a java.util.function.Function"
        )
    }

    @Test
    fun `should reject evaluating Python object to AutoCloseable`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            python.eval("len", AutoCloseable::class.java)
        }

        // then
        assert(exception.message == "A Python object is converted to its repr, which is not a java.lang.AutoCloseable")
    }

    @Test
    fun `should evaluate Python object to repr for interface implemented by String`() {
        // when
        val comparable = python.eval("len", Comparable::class.java)
        val serializable = python.eval("len", java.io.Serializable::class.java)

        // then
        assert(comparable == "<built-in function len>")
        assert(serializable == "<built-in function len>")
    }

    @Test
    fun `should keep working after rejecting Python callable as functional interface`() {
        // given
        assertFailsWith<IllegalArgumentException> {
            python.eval("lambda: 1", Runnable::class.java)
        }

        // when
        val result = python.eval<Long>("1 + 1")

        // then
        assert(result == 2L)
    }

    @Test
    fun `should evaluate to repr of Python callables in array of functional interface`() {
        // given
        val arrayOfComparables = arrayOf<Comparable<*>>().javaClass
        val arrayOfArraysOfComparables = arrayOf<Array<Comparable<*>>>().javaClass

        // when
        val comparables = python.eval("[len]", arrayOfComparables)
        val nested = python.eval("[[len], [abs]]", arrayOfArraysOfComparables)

        // then
        assert(comparables.javaClass == arrayOfComparables)
        assert(comparables.toList() == listOf("<built-in function len>"))
        assert(nested.javaClass == arrayOfArraysOfComparables)
        assert(nested.all { it.javaClass == arrayOfComparables })
        assert(nested.map { it.toList() } == listOf(listOf("<built-in function len>"), listOf("<built-in function abs>")))
    }

    @Test
    fun `should not close Java proxy held by Python variable when reading it`() {
        // given
        python.exec(
            """
            from jep import jproxy
            class R:
                def __init__(self):
                    self.runs = 0
                def run(self):
                    self.runs += 1
                def __repr__(self):
                    return 'R'
            r = R()
            x = jproxy(r, ['java.lang.Runnable'])
            xs = [x]
            """.trimIndent()
        )

        // when
        val first = python["x"]
        python.exec("x.run()")
        val second = python["x"]
        val list = python["xs"]
        python.exec("xs[0].run()")

        // then
        assert(first == "R")
        assert(second == "R")
        assert(list == listOf("R"))
        assert(python.eval<Long>("r.runs") == 2L)
    }

    @Test
    fun `should widen arrays in cycle holding repr of proxy`() {
        // given
        val b = arrayOfNulls<Cloneable>(2)
        val a = arrayOfNulls<Array<Cloneable?>>(1)
        b[0] = a
        a[0] = b
        python["b"] = b
        python.exec(
            """
            from jep import jproxy
            class C:
                def __repr__(self):
                    return 'C'
            b[1] = jproxy(C(), ['java.lang.Cloneable'])
            """.trimIndent()
        )

        // when
        val result = python["b"]

        // then
        assert(result is Array<*> && result[1] == "C")
        val nested = (result as Array<*>)[0]
        assert(nested is Array<*> && nested.single() === result)
    }

    @Test
    fun `should widen array holding arrays of collections copied to another collection type`() {
        // given
        python["a"] = arrayOf<Any?>(arrayOf(java.util.TreeSet(listOf("t"))), arrayOf(java.util.ArrayDeque(listOf("d"))))
        python.exec("a[0][0].add('u')")
        python.exec("a[1][0].add(len)")

        // when
        val result = python["a"]

        // then
        assert(result is Array<*> && result.javaClass == Array<Any?>::class.java)
        val (sets, deques) = (result as Array<*>).toList()
        assert(sets is Array<*> && sets.javaClass == Array<Any?>::class.java && sets.single() == setOf("t", "u"))
        assert(deques is Array<*> && deques.javaClass == Array<Any?>::class.java)
        assert((deques as Array<*>).single() == listOf("d", "<built-in function len>"))
    }

    @Test
    fun `should reject evaluating Python callables to array of functional interface not holding repr`() {
        // given
        val arrayOfRunnables = arrayOf<Runnable>().javaClass

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            python.eval("[len]", arrayOfRunnables)
        }

        // then
        assert(
            exception.message == "A value holding Python objects is copied to a List, a Set, a Map or an array, " +
                    "which is not a java.lang.Runnable[]"
        )
    }

    @Test
    fun `should return repr of Python callable passed to Kotlin as functional interface`() {
        // given
        val holder = RunnableHolder()
        python["holder"] = holder
        python.exec("holder.add(lambda: None)")

        // when
        val runnables = python("holder.getRunnables()")
        val map = python("{'runnables': holder.getRunnables()}")
        val got = python["holder"]

        // then
        assert(runnables is List<*> && runnables.single().toString().startsWith("<function <lambda> at "))
        assert(map is Map<*, *> && (map["runnables"] as List<*>).single().toString().startsWith("<function <lambda> at "))
        // the holder is returned as it is, as Jep passes a Java object through,
        // still holding the proxies bound to the thread of Python, which is not intended, see #38
        assert(got === holder)
    }

    @Test
    fun `should evaluate to interface type implemented by converted value`() {
        // when
        val list = python.eval("[1, 2]", List::class.java)
        val collection = python.eval("[1, 2]", Collection::class.java)
        val map = python.eval("{'a': 1}", Map::class.java)
        val charSequence = python.eval("'foo'", CharSequence::class.java)
        val comparableString = python.eval("'foo'", Comparable::class.java)
        val comparableNumber = python.eval("42", Comparable::class.java)

        // then
        assert(list == listOf(1L, 2L))
        assert(collection.toList() == listOf(1L, 2L))
        assert(map == mapOf("a" to 1L))
        assert(charSequence == "foo")
        assert(comparableString == "foo")
        assert(comparableNumber == 42L)
    }

    @Test
    fun `should evaluate to typed nested array holding repr of value not convertible to Java`() {
        // given
        val arrayOfLists = arrayOf<List<*>>().javaClass

        // when
        val arrays = python.eval("[[len]]", Array<Array<Any>>::class.java)
        val lists = python.eval("[[len]]", arrayOfLists)

        // then
        assert(arrays.single().toList() == listOf("<built-in function len>"))
        assert(lists.toList() == listOf(listOf("<built-in function len>")))
    }

    @Test
    fun `should widen Kotlin array of Python objects holding repr`() {
        // given
        python["a"] = arrayOfNulls<PyObject>(1)
        python.exec("a[0] = len")

        // when
        val result = python["a"]

        // then
        assert(result is Array<*> && result.toList() == listOf("<built-in function len>"))
    }

    @Test
    fun `should widen Kotlin array of Python object subtype holding repr`() {
        // given
        python["a"] = arrayOfNulls<PyCallable>(1)
        python.exec("a[0] = len")

        // when
        val result = python["a"]

        // then
        assert(result is Array<*> && result.toList() == listOf("<built-in function len>"))
    }

    @Test
    fun `should widen nested Kotlin array of Python objects holding repr by each dimension`() {
        // given
        python["a"] = arrayOf(arrayOfNulls<PyObject>(1))
        python.exec("a[0][0] = len")

        // when
        val result = python["a"]
        val evaluated = python.eval("a", Array<Array<Any>>::class.java)

        // then
        assert(result is Array<*> && result.javaClass == Array<Array<Any>>::class.java)
        assert(evaluated.single().toList() == listOf("<built-in function len>"))
    }

    @Test
    fun `should widen empty Kotlin array of Python objects next to one holding repr`() {
        // given
        python["a"] = arrayOf(arrayOfNulls<PyObject>(0), arrayOfNulls<PyObject>(1))
        python.exec("a[1][0] = len")

        // when
        val result = python["a"]

        // then
        assert(result is Array<*> && result.javaClass == Array<Array<Any>>::class.java)
        val empty = (result as Array<*>)[0]
        assert(empty is Array<*> && empty.javaClass == Array<Any>::class.java && empty.isEmpty())
    }

    @Test
    fun `should widen nested Kotlin array of proxies of Python objects holding repr by each dimension`() {
        // given
        python["a"] = arrayOf(arrayOfNulls<Runnable>(1))
        python.exec("a[0][0] = len")

        // when
        val result = python["a"]

        // then
        assert(result is Array<*> && result.javaClass == Array<Array<Any>>::class.java)
        assert((result as Array<*>).single().let { it is Array<*> && it.toList() == listOf("<built-in function len>") })
    }

    @Test
    fun `should reject evaluating to concrete collection type holding Python object`() {
        // given
        python["q"] = java.util.ArrayDeque<Any?>()
        python.exec("q.add(len)")

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            python.eval("q", java.util.ArrayDeque::class.java)
        }

        // then
        assert(
            exception.message == "A value holding Python objects is copied to a List, a Set, a Map or an array, " +
                    "which is not a java.util.ArrayDeque"
        )
    }

    @Test
    fun `should copy Kotlin set holding Python object`() {
        // given
        val set = LinkedHashSet<Any?>()
        set.add("a")
        python["s"] = set
        python.exec("s.add(len)")

        // when
        val result = python["s"]
        val nested = python("[s]")

        // then
        // the set holding a Python object is not rendered by power-assert, which cannot do it on this thread
        val copied = result !== set
        assert(copied)
        assert(result is Set<*> && result == setOf("a", "<built-in function len>"))
        assert(nested is List<*> && nested.single() is Set<*> && nested == listOf(setOf("a", "<built-in function len>")))
        assert(python("s.size()") == 2L)
    }

    @Test
    fun `should copy Kotlin collection holding Python object`() {
        // given
        val queue = java.util.ArrayDeque<Any?>()
        python["q"] = queue
        python.exec("q.add(len)")

        // when
        val result = python["q"]

        // then
        assert(result is Collection<*> && result.toList() == listOf("<built-in function len>"))
    }

    @Test
    fun `should reject set elements of the same repr`() {
        // given
        val set = LinkedHashSet<Any?>()
        set.add("Point")
        python["s"] = set
        python.exec(
            """
            class Point:
                def __repr__(self):
                    return 'Point'
            s.add(Point())
            """.trimIndent()
        )

        // when
        val exception = assertFailsWith<IllegalStateException> {
            python["s"]
        }

        // then
        assert(exception.message == "Different elements of a set have the same repr: Point")
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

/** Holds the [Runnable]s added from Python, which Jep converts to proxies of Python callables. */
class RunnableHolder {

    private val runnables = ArrayList<Runnable>()

    fun add(runnable: Runnable) {
        runnables.add(runnable)
    }

    fun getRunnables(): List<Runnable> = runnables

}
