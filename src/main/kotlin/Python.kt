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

import jep.SharedInterpreter
import jep.python.PyCallable
import jep.python.PyObject
import org.intellij.lang.annotations.Language
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stateful CPython interpreter, embedded with [Jep](https://github.com/ninia/jep).
 *
 * The global variables, functions, classes and imports defined by the executed code
 * stay visible to the code executed after it,
 * so a `Python` provided as a dependency of a Kotlin script session,
 * like `"python" provides Python()`,
 * keeps its state between the snippets.
 *
 * Each `Python` has its own global variables, while the imported modules are shared by all of them,
 * so that native extension modules, like `numpy`, which do not support sub-interpreters, can be used.
 *
 * A Jep interpreter can be used only by the thread which created it,
 * therefore each `Python` runs its code on a thread of its own,
 * and can be called from any thread, one call at a time.
 * Concurrent calls wait for their turn.
 *
 * Once [close] is called, every call which is not running yet fails with an [IllegalStateException]
 * with the message `Python is closed` —
 * also a call racing `close()` from another thread, or waiting for its turn behind a running call.
 * Only the running call completes, also when a Kotlin function it calls calls back this `Python`,
 * and the interpreter is closed after it,
 * exactly once, on the thread of this `Python`.
 * Also such a Kotlin function can call `close()`, which then closes this `Python` after the running call.
 *
 * A Kotlin function can be set as a global variable and called from Python, like `python["f"] = { n: Long -> n * 2 }`.
 * It runs on the thread of this `Python`, and can call it back.
 *
 * The values are converted between Java and Python by Jep, e.g. a Python `int` becomes a [Long],
 * a `str` a [String], and a `list` a [List].
 * A returned value which Jep cannot convert, also when nested in a [List], a [Map] or an array,
 * becomes its Python `repr`, as a Python object cannot be used outside the thread of this `Python`.
 * If different keys of a `dict` have the same `repr`, e.g. `{'Point': 1, Point(): 2}`,
 * an [IllegalStateException] is thrown, not to drop an entry silently.
 *
 * The Python environment is resolved on the first use, see [PythonEnvironment].
 */
public class Python private constructor(
    decorateExecutor: (ExecutorService) -> ExecutorService
) : AutoCloseable {

    public constructor() : this({ it })

    @Volatile
    private var thread: Thread? = null

    // decorated only by tests, see withDecoratedExecutor
    private val executor: ExecutorService = decorateExecutor(Executors.newSingleThreadExecutor { task ->
        Thread(task, "python-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
            thread = this
        }
    })

    private val closed = AtomicBoolean(false)

    private var interpreter: SharedInterpreter? = null

    /**
     * Executes Python [code], like a module, defining or redefining the global variables.
     *
     * The code can declare its dependencies with
     * [inline script metadata](https://packaging.python.org/en/latest/specifications/inline-script-metadata/):
     * ```python
     * # /// script
     * # dependencies = ["rdkit"]
     * # ///
     * from rdkit import Chem
     * ```
     * The missing ones are installed before executing the code, see [PythonEnvironment.dependencyDirectory].
     *
     * @throws jep.JepException if the code fails, with the type and the message of the Python exception.
     * @throws PythonDependencyException if the declared dependencies cannot be installed.
     */
    public fun exec(code: String) {
        call {
            PythonDependencies.install(code, this)
            exec(code)
        }
    }

    /**
     * Executes Python [code], like a cell of a Jupyter notebook:
     * if its last statement is an expression, its value is returned, otherwise `null`.
     *
     * It is meant for the code, which is not known in advance, e.g. written by an LLM,
     * so the value can be either used, like `python("a.tolist()") as List<Long>`, or ignored.
     * The value is converted by Jep to its default Java type, e.g. a Python `int` becomes a [Long],
     * and a value which Jep cannot convert is returned as its Python `repr`.
     * A `None` value, e.g. of a function returning nothing, is returned as `null`.
     *
     * Like with [exec], the code can declare its dependencies.
     *
     * @throws jep.JepException if the code fails, with the type and the message of the Python exception.
     * @throws PythonDependencyException if the declared dependencies cannot be installed.
     * @throws IllegalStateException if different keys of a returned `dict` have the same `repr`.
     */
    public operator fun invoke(@Language("python") code: String): Any? = call {
        PythonDependencies.install(code, this)
        @Suppress("UNCHECKED_CAST")
        val (statements, expression) = getValue(SPLIT_LAST_EXPRESSION, PyCallable::class.java).use {
            it.callAs(List::class.java, code)
        } as List<String>
        if (statements.isNotBlank()) exec(statements)
        if (expression.isEmpty()) return@call null
        toJava(getValue(expression))
    }

    /**
     * Evaluates a Python [expression], converting its value to the reified type [T].
     *
     * A value which Jep cannot convert, e.g. of `eval<Any>("len")`, or nested in a [List] or a [Map],
     * becomes its Python `repr`.
     *
     * @throws jep.JepException if the evaluation fails, or the value cannot be converted.
     * @throws IllegalArgumentException if [T] is a [PyObject], which cannot leave the thread of this `Python`.
     * @throws IllegalStateException if different keys of a returned `dict` have the same `repr`.
     */
    public inline fun <reified T : Any> eval(@Language("python") expression: String): T =
        eval(expression, T::class.javaObjectType)

    /**
     * Evaluates a Python [expression], converting its value to the given [type].
     *
     * A value which Jep cannot convert, e.g. of `eval("len", Any::class.java)`, or nested in a [List] or a [Map],
     * becomes its Python `repr`.
     *
     * @throws jep.JepException if the evaluation fails, or the value cannot be converted.
     * @throws IllegalArgumentException if the [type] is a [PyObject], which cannot leave the thread of this `Python`.
     * @throws IllegalStateException if different keys of a returned `dict` have the same `repr`.
     */
    public fun <T : Any> eval(@Language("python") expression: String, type: Class<T>): T {
        require(!PyObject::class.java.isAssignableFrom(type)) { "A PyObject cannot leave the thread of Python" }
        return call { type.cast(toJava(getValue(expression, type))) }
    }

    /**
     * Returns the value of the global variable of the given [name], converted to a Java object.
     *
     * A value which Jep cannot convert, e.g. of an imported module, or nested in a [List] or a [Map],
     * becomes its Python `repr`.
     *
     * @throws jep.JepException if there is no such variable.
     * @throws IllegalStateException if different keys of a `dict` have the same `repr`.
     */
    public operator fun get(name: String): Any? = call { toJava(getValue(name)) }

    /**
     * Sets the global variable of the given [name] to the [value], converted to a Python object.
     */
    public operator fun set(name: String, value: Any?) {
        call { set(name, value) }
    }

    /**
     * Closes the interpreter and releases its thread.
     *
     * It waits for the running call, if any, to complete, also calling back this `Python` from a Kotlin function,
     * and then closes the interpreter on its thread.
     * Each call which is not running yet, or which is made afterward, fails with an [IllegalStateException].
     * Calling it again has no effect, and returns immediately.
     *
     * Called by a Kotlin function, which is called from Python, it does not wait,
     * as the running call is the one which called it:
     * it returns immediately, the running call continues and completes normally,
     * still able to call back this `Python`, and the interpreter is closed after it returns.
     * Then nobody waits for the closing, so an exception thrown by closing the interpreter is lost.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // the calls submitted before, and still waiting for their turn, see it closed when it comes,
        // and the ones submitted afterward are rejected, so that this task is the last one to touch the interpreter
        // explicitly a Runnable, unlike the calls, which tests rely on to intercept only this task
        val closing = try {
            executor.submit(Runnable {
                interpreter?.close()
                interpreter = null
            })
        } finally {
            executor.shutdown()
        }
        // called by a Kotlin function, called from Python, the running call is still on the stack of this thread,
        // and the task of close() can run only after it, so waiting for it here would deadlock
        if (Thread.currentThread() === thread) return
        try {
            closing.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun <T> call(block: SharedInterpreter.() -> T): T {
        // a Kotlin function called from Python, calling back this Python, runs already on its thread,
        // where waiting for the executor would deadlock; it belongs to the running call, which completes
        // even if close() is already waiting for it, as the task of close() cannot run before
        if (Thread.currentThread() === thread) return interpreter!!.block()
        checkNotClosed()
        val future = try {
            executor.submit<T> {
                // close() might have been called after this call was submitted, and if its task has run already,
                // creating a new interpreter here would leak it, never to be closed
                checkNotClosed()
                val interpreter = interpreter ?: run {
                    PythonEnvironment.initialize()
                    SharedInterpreter().also { interpreter = it }
                }
                interpreter.block()
            }
        } catch (_: RejectedExecutionException) {
            // close() has shut down the executor after the check above
            throw closedException()
        }
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun checkNotClosed() {
        if (closed.get()) throw closedException()
    }

    /**
     * Replaces each [PyObject] of the [value], also nested in a [List], a [Map] or an array, with its `repr`,
     * as it is bound to the thread of this interpreter.
     *
     * Only a top-level [PyObject] is closed, as it is created by Jep for this value.
     * A nested one can be held by a Java collection, which Python can still use, e.g. set with `python["k"] = list`,
     * so it is only left out of the returned copy, and Jep releases it once it is garbage collected.
     */
    private fun SharedInterpreter.toJava(value: Any?): Any? = when (value) {
        is PyObject -> value.use { PyObjectConverter(this).use { converter -> converter.convert(it) } }
        is List<*>, is Map<*, *>, is Array<*> -> PyObjectConverter(this).use { it.convert(value) }
        else -> value
    }

    /**
     * Converts a value returned by Jep, see [toJava].
     *
     * A value without any [PyObject] is returned as it is.
     * Otherwise each [List], [Map] and array reachable from it is copied, never changed,
     * also when it contains itself, so that the copy contains itself as well.
     * The same [PyObject] becomes the same `repr`.
     */
    private class PyObjectConverter(private val interpreter: SharedInterpreter) : AutoCloseable {

        private var repr: PyCallable? = null

        /** The copy of each converted value, by its identity, so that a cycle is copied once. */
        private val copies = IdentityHashMap<Any, Any>()

        fun convert(value: Any?): Any? =
            if (containsPyObject(value, Collections.newSetFromMap(IdentityHashMap()))) copy(value) else value

        private fun containsPyObject(value: Any?, visited: MutableSet<Any>): Boolean = when {
            value is PyObject -> true
            value == null || !visited.add(value) -> false
            value is List<*> -> value.any { containsPyObject(it, visited) }
            value is Map<*, *> -> value.any { (key, element) ->
                containsPyObject(key, visited) || containsPyObject(element, visited)
            }
            value is Array<*> -> value.any { containsPyObject(it, visited) }
            else -> false
        }

        private fun copy(value: Any?): Any? {
            if (value == null) return null
            copies[value]?.let { return it }
            return when (value) {
                is PyObject -> repr(value).also { copies[value] = it }
                is List<*> -> ArrayList<Any?>(value.size).also { copy ->
                    copies[value] = copy
                    value.forEach { copy.add(copy(it)) }
                }
                is Map<*, *> -> LinkedHashMap<Any?, Any?>().also { copy ->
                    copies[value] = copy
                    value.forEach { (key, element) ->
                        val copiedKey = copy(key)
                        check(copiedKey !in copy) { "Different keys of a dict have the same repr: $copiedKey" }
                        copy[copiedKey] = copy(element)
                    }
                }
                is Array<*> -> arrayOfNulls<Any?>(value.size).also { copy ->
                    copies[value] = copy
                    value.forEachIndexed { index, element -> copy[index] = copy(element) }
                }
                else -> value
            }
        }

        private fun repr(value: PyObject): String {
            val repr = repr ?: interpreter.getValue("__import__('builtins').repr", PyCallable::class.java)
                .also { repr = it }
            return repr.callAs(String::class.java, value)
        }

        override fun close() {
            repr?.close()
        }

    }

    private fun closedException() = IllegalStateException("Python is closed")

    internal companion object {

        /**
         * Creates a `Python`, whose executor is decorated by [decorateExecutor],
         * so that a test can interleave deterministically with the submission of a call.
         */
        internal fun withDecoratedExecutor(
            decorateExecutor: (ExecutorService) -> ExecutorService
        ): Python = Python(decorateExecutor)

        private val threadCounter = AtomicInteger()

        /**
         * Splits the code into the statements before its last expression, and this expression,
         * preceded by empty lines to keep its line number in a traceback,
         * or returns the whole code and an empty expression, if it does not end with an expression,
         * or cannot be parsed, so that executing it reports the `SyntaxError`.
         */
        @Language("python")
        private val SPLITTER = """
            import ast

            def split(code):
                try:
                    tree = ast.parse(code)
                except SyntaxError:
                    return [code, '']
                if not tree.body or not isinstance(tree.body[-1], ast.Expr):
                    return [code, '']
                last = tree.body[-1]
                # the positions of ast are in UTF-8 bytes, get_source_segment converts them
                start = ast.Pass(lineno=1, col_offset=0, end_lineno=last.lineno, end_col_offset=last.col_offset)
                statements = ast.get_source_segment(code, start)
                expression = chr(10) * (last.lineno - 1) + ast.get_source_segment(code, last)
                return [statements, expression]
        """.trimIndent()

        /**
         * Evaluates to the `split` function of the [SPLITTER],
         * defined in a namespace of its own, not to add it to the global variables.
         */
        private val SPLIT_LAST_EXPRESSION =
            "(lambda namespace: exec(${pythonStringLiteral(SPLITTER)}, namespace) or namespace['split'])({})"

        private fun pythonStringLiteral(text: String): String = "'" + text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n") + "'"

    }

}
