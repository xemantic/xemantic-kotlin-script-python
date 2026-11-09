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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 * A call whose calling thread is interrupted, while it waits for its turn, throws an [InterruptedException],
 * and never runs, while a running call, whose caller is interrupted, still completes.
 *
 * Once [close] is called, every call which is not running yet fails with an [IllegalStateException]
 * with the message `Python is closed` —
 * also a call racing `close()` from another thread, or waiting for its turn behind a running call.
 * Only the running call completes, also when a Kotlin function it calls calls back this `Python`,
 * and the interpreter is closed after it,
 * exactly once, on the thread of this `Python`.
 * Also such a Kotlin function can call `close()`, directly or from another thread it waits for,
 * which then closes this `Python` after the running call.
 *
 * A Kotlin function can be set as a global variable and called from Python, like `python["f"] = { n: Long -> n * 2 }`.
 * It runs on the thread which calls it from Python: the thread of this `Python`,
 * unless the Python code calls it from a thread of its own, like a `threading.Thread`.
 * On the thread of this `Python` it can call this `Python` back directly, as a part of the running call,
 * while on another thread, its call waits for its turn, like any call from another thread.
 * The running call must not wait for another thread calling this `Python`, e.g. a Kotlin function
 * with `thread { python.eval<Long>("x") }.join()`, or `runBlocking(Dispatchers.IO) { python["x"] }`,
 * or Python code joining a `threading.Thread` which calls a Kotlin function calling this `Python`:
 * such a call waits for its turn behind the running call, which waits for it, so both wait forever.
 * It cannot be detected, as a call from another thread, made during the running call, can as well just wait for its turn.
 * Call this `Python` directly from a Kotlin function running on the thread of this `Python` instead.
 *
 * The values are converted between Java and Python by Jep, e.g. a Python `int` becomes a [Long],
 * a `str` a [String], and a `list` a [List].
 * A returned value which Jep cannot convert, also when nested in a [Collection], a [Map] or an array,
 * becomes its Python `repr`, as a Python object cannot be used outside the thread of this `Python`.
 * If different keys of a `dict`, or elements of a [Set], have the same `repr`, e.g. `{'Point': 1, Point(): 2}`,
 * an [IllegalStateException] is thrown, not to drop an entry silently.
 *
 * The Python environment is resolved on the first use, see [PythonEnvironment].
 */
public class Python private constructor(
    decorateExecutor: (ExecutorService) -> ExecutorService,
    // replaced only by tests, see withInterpreterFactory
    private val createInterpreter: () -> SharedInterpreter
) : AutoCloseable {

    public constructor() : this({ it }, { SharedInterpreter() })

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

    /**
     * The task of [close] closing the interpreter, set by the first `close()`,
     * so that each `close()`, which waits, waits for the same one, see [close].
     */
    private val closing = CompletableFuture<Future<*>>()

    /** Whether a call is running on the thread of this `Python`, written only by this thread, see [close]. */
    @Volatile
    private var running = false

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
     * @throws IllegalStateException if different keys of a returned `dict`, or elements of a [Set], have the same `repr`.
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
     * A value which Jep cannot convert, e.g. of `eval<Any>("len")`, or nested in a [Collection] or a [Map],
     * becomes its Python `repr`.
     *
     * @throws jep.JepException if the evaluation fails, or the value cannot be converted.
     * A value holding Python objects is copied to a [List], a [Set], a [Map] or an array, see [Python],
     * so it cannot be of another collection type, like `ArrayDeque`.
     *
     * @throws IllegalArgumentException if [T] is a [PyObject], or an array of them, which cannot leave the thread of this `Python`,
     * or if [T] is another collection type, and the value holds Python objects.
     * @throws IllegalStateException if different keys of a returned `dict`, or elements of a [Set], have the same `repr`.
     */
    public inline fun <reified T : Any> eval(@Language("python") expression: String): T =
        eval(expression, T::class.javaObjectType)

    /**
     * Evaluates a Python [expression], converting its value to the given [type].
     *
     * A value which Jep cannot convert, e.g. of `eval("len", Any::class.java)`, or nested in a [Collection] or a [Map],
     * becomes its Python `repr`.
     *
     * @throws jep.JepException if the evaluation fails, or the value cannot be converted.
     * A value holding Python objects is copied to a [List], a [Set], a [Map] or an array, see [Python],
     * so it cannot be of another collection type, like `ArrayDeque`.
     *
     * @throws IllegalArgumentException if the [type] is a [PyObject], or an array of them, which cannot leave the thread of this `Python`,
     * or if the [type] is another collection type, and the value holds Python objects.
     * @throws IllegalStateException if different keys of a returned `dict`, or elements of a [Set], have the same `repr`.
     */
    public fun <T : Any> eval(@Language("python") expression: String, type: Class<T>): T = call {
        // checked in the call, so that a closed Python fails as closed
        require(!type.holdsPyObject()) { "A PyObject cannot leave the thread of Python" }
        val raw = getValue(expression, type)
        val value = toJava(raw)
        // a copy is a List, a Set, a Map, or an array, which might not be the type requested
        require(value === raw || type.isInstance(value)) {
            "A value holding Python objects is copied to a List, a Set, a Map or an array, which is not a ${type.name}"
        }
        type.cast(value)
    }

    /**
     * Returns the value of the global variable of the given [name], converted to a Java object.
     *
     * A value which Jep cannot convert, e.g. of an imported module, or nested in a [Collection] or a [Map],
     * becomes its Python `repr`.
     *
     * @throws jep.JepException if there is no such variable.
     * @throws IllegalStateException if different keys of a `dict`, or elements of a [Set], have the same `repr`.
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
     * Unless a call is running, or starting or ending on the thread of this `Python`,
     * it closes the interpreter on its thread, and waits for it,
     * so that the interpreter is closed when it returns.
     * Each call which is not running yet, or which is made afterward, fails with an [IllegalStateException].
     * Calling it again, also from another thread, or after the waiting is interrupted, waits for the same closing,
     * and throws the same exception, if closing the interpreter fails.
     * If the waiting is interrupted, it throws an [InterruptedException], while the interpreter is still closed.
     *
     * With a call running, or starting or ending, it does not wait for it:
     * it returns immediately, the running call continues and completes normally,
     * still able to call back this `Python`, and the interpreter is closed after it returns.
     * Then only a `close()` called again, with no call running, waits for the closing,
     * and reports an exception thrown by closing the interpreter,
     * and if the JVM exits before the running call returns, e.g. at the end of `main`,
     * the interpreter is never closed, as its thread is a daemon one.
     * Waiting would deadlock, when the running call waits for `close()`:
     * when it is called by a Kotlin function called from Python, or by another thread, which this function,
     * or the Python code, waits for, and it cannot be told apart from a `close()` which the running call does not wait for.
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // the calls submitted before, and still waiting for their turn, see it closed when it comes,
            // and the ones submitted afterward are rejected, so that this task is the last one to touch the interpreter
            // explicitly a Runnable, unlike the calls, which tests rely on to intercept only this task
            try {
                closing.complete(executor.submit(Runnable {
                    try {
                        interpreter?.close()
                    } finally {
                        // also when closing fails, not to hold a half-closed interpreter, which cannot be closed again
                        interpreter = null
                    }
                }))
            } catch (e: Throwable) {
                // not to leave another close() waiting forever
                closing.completeExceptionally(e)
                throw e
            } finally {
                executor.shutdown()
            }
        }
        // the task of close() can run only after the running call, which might wait for this close(),
        // called by a Kotlin function, called from Python, or by a thread it waits for, so waiting here could deadlock;
        // read after setting closed, so that a call, which is not running yet, sees it closed before running any code,
        // and then the task of close() runs right after it
        if (running) return
        // also a close() called again, e.g. after the first one is interrupted, waits until the interpreter is closed
        try {
            closing.get().get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun <T> call(block: SharedInterpreter.() -> T): T {
        // a Kotlin function called from Python, calling back this Python, runs already on its thread,
        // where waiting for the executor would deadlock; it belongs to the running call, which completes
        // even if close() is already called, as the task of close() cannot run before
        if (Thread.currentThread() === thread) return interpreter!!.block()
        checkNotClosed()
        val future = try {
            executor.submit<T> {
                // set before checking if closed, see close()
                running = true
                try {
                    // close() might have been called after this call was submitted, and if its task has run already,
                    // creating a new interpreter here would leak it, never to be closed
                    checkNotClosed()
                    val interpreter = interpreter ?: run {
                        PythonEnvironment.initialize()
                        createInterpreter().also { interpreter = it }
                    }
                    interpreter.block()
                } finally {
                    running = false
                }
            }
        } catch (_: RejectedExecutionException) {
            // close() has shut down the executor after the check above
            throw closedException()
        }
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            // a call still waiting for its turn never runs, only a running one completes
            future.cancel(false)
            throw e
        }
    }

    /** Whether this type is a [PyObject], or an array of them, also of arrays. */
    private fun Class<*>.holdsPyObject(): Boolean =
        PyObject::class.java.isAssignableFrom(this) || componentType?.holdsPyObject() == true

    private fun checkNotClosed() {
        if (closed.get()) throw closedException()
    }

    /**
     * Replaces each [PyObject] of the [value], also nested in a [Collection], a [Map] or an array, with its `repr`,
     * as it is bound to the thread of this interpreter.
     *
     * Only a top-level [PyObject] is closed, as it is created by Jep for this value.
     * A nested one can be held by a Java collection, which Python can still use, e.g. set with `python["k"] = list`,
     * so it is only left out of the returned copy, and Jep releases it once it is garbage collected.
     */
    private fun SharedInterpreter.toJava(value: Any?): Any? = when (value) {
        is PyObject -> value.use { PyObjectConverter(this).use { converter -> converter.convert(it) } }
        is Collection<*>, is Map<*, *>, is Array<*> -> PyObjectConverter(this).use { it.convert(value) }
        else -> value
    }

    /**
     * Converts a value returned by Jep, see [toJava].
     *
     * A value without any [PyObject] is returned as it is.
     * Otherwise each [Collection], [Map] and array reachable from it is copied, never changed,
     * a [Set] to a [LinkedHashSet], another [Collection] than a [List] to an [ArrayList],
     * and an array to an array of the same component type, unless it cannot hold a `repr`, like `PyObject[]`,
     * which is widened to `Object[]`, so that the copy can be cast to the type requested from [eval],
     * also when it contains itself, so that the copy contains itself as well.
     * The same [PyObject] becomes the same `repr`.
     */
    private class PyObjectConverter(private val interpreter: SharedInterpreter) : AutoCloseable {

        private var repr: PyCallable? = null

        /** The copy of each converted value, by its identity, so that a cycle is copied once. */
        private val copies = IdentityHashMap<Any, Any>()

        fun convert(value: Any?): Any? =
            if (containsPyObject(value, Collections.newSetFromMap(IdentityHashMap()))) copy(value) else value

        // only the containers are tracked, which can form a cycle, not to track each element of a large list
        private fun containsPyObject(value: Any?, visited: MutableSet<Any>): Boolean = when (value) {
            is PyObject -> true
            is Collection<*> -> visited.add(value) && value.any { containsPyObject(it, visited) }
            is Map<*, *> -> visited.add(value) && value.any { (key, element) ->
                containsPyObject(key, visited) || containsPyObject(element, visited)
            }
            is Array<*> -> visited.add(value) && value.any { containsPyObject(it, visited) }
            else -> false
        }

        private fun copy(value: Any?): Any? {
            if (value == null) return null
            copies[value]?.let { return it }
            return when (value) {
                is PyObject -> repr(value).also { copies[value] = it }
                is Set<*> -> LinkedHashSet<Any?>().also { copy ->
                    copies[value] = copy
                    value.forEach { element ->
                        val copiedElement = copy(element)
                        check(copy.add(copiedElement)) { "Different elements of a set have the same repr: $copiedElement" }
                    }
                }
                is Collection<*> -> ArrayList<Any?>(value.size).also { copy ->
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
                is Array<*> -> newArray(value.javaClass.componentType, value.size).also { copy ->
                    copies[value] = copy
                    value.forEachIndexed { index, element -> copy[index] = copy(element) }
                }
                else -> value
            }
        }

        /**
         * Creates an array, which can hold the copy of each element of an array of the [componentType]:
         * a type which can hold a [PyObject], or its subtype, but not its `repr`, is widened to [Any],
         * also of a nested array.
         */
        private fun newArray(componentType: Class<*>, size: Int): Array<Any?> {
            @Suppress("UNCHECKED_CAST")
            return java.lang.reflect.Array.newInstance(copyType(componentType), size) as Array<Any?>
        }

        private fun copyType(type: Class<*>): Class<*> = when {
            type.isArray -> java.lang.reflect.Array.newInstance(copyType(type.componentType), 0).javaClass
            type.isAssignableFrom(PyObject::class.java) && !type.isAssignableFrom(String::class.java) -> Any::class.java
            PyObject::class.java.isAssignableFrom(type) -> Any::class.java
            else -> type
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
        ): Python = Python(decorateExecutor) { SharedInterpreter() }

        /**
         * Creates a `Python`, whose interpreter is created by [createInterpreter], on the thread of this `Python`,
         * so that a test can make the interpreter fail.
         */
        internal fun withInterpreterFactory(
            createInterpreter: () -> SharedInterpreter
        ): Python = Python({ it }, createInterpreter)

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
