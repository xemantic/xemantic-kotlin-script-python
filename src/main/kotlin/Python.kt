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
import jep.python.InvocationHandler
import jep.python.PyCallable
import jep.python.PyObject
import org.intellij.lang.annotations.Language
import java.lang.reflect.Proxy
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
 * A call whose calling thread is interrupted while waiting, throws an [InterruptedException],
 * clearing the interrupt flag of the thread, like any blocking method throwing it,
 * and is cancelled, so that it does not run, unless the thread of this `Python` has already started it,
 * possibly just as the calling thread was interrupted — such a call completes, with its side effects, unreported.
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
 * So does a Python object, which Jep converts to a proxy of a Java interface, as calling the proxy calls Python,
 * e.g. a Python function passed to a Kotlin function taking a [Runnable], or an element of `eval("[len]", Comparable[]::class.java)`.
 * If different keys of a `dict`, or elements of a [Set], have the same `repr`, e.g. `{'Point': 1, Point(): 2}`,
 * an [IllegalStateException] is thrown, not to drop an entry silently.
 *
 * The Python environment is resolved on the first use, see [PythonEnvironment].
 * If it cannot be resolved, or the embedded CPython fails to start, the call fails with an [IllegalStateException].
 * When the embedded CPython fails to start, its cause is the original failure, e.g. of Jep,
 * and its message includes what the probe of the Python executable wrote to the standard error, if anything,
 * e.g. the `Could not find platform libraries` warning of a wrong `PYTHONHOME`, which might tell why.
 * Once the embedded CPython fails to start, every `Python` fails, as it can be started only once per JVM.
 */
public class Python private constructor(
    decorateExecutor: (ExecutorService) -> ExecutorService,
    // replaced only by tests, see withInterpreterFactory
    private val newInterpreter: () -> SharedInterpreter = { SharedInterpreter() }
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
     * @throws InterruptedException if the calling thread is interrupted while the call waits for its turn, or runs.
     */
    @Throws(InterruptedException::class)
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
     * @throws InterruptedException if the calling thread is interrupted while the call waits for its turn, or runs.
     */
    @Throws(InterruptedException::class)
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
     * So does a Python object requested as an interface which a [String] implements,
     * e.g. `Comparable` or `java.io.Serializable`, so that the `repr` is returned instead of a proxy.
     *
     * @throws jep.JepException if the evaluation fails, or the value cannot be converted.
     * A value holding Python objects is copied to a [List], a [Set], a [Map] or an array, see [Python],
     * so it cannot be of another collection type, like `ArrayDeque`.
     *
     * @throws IllegalArgumentException if [T] is a [PyObject], or an array of them, which cannot leave the thread of this `Python`,
     * or if [T] is another collection type, and the value holds Python objects,
     * or if the value is a Python object, which Jep converts to a proxy of the interface [T],
     * like a Python callable of `eval<Runnable>("lambda: 1")`, as its `repr` is not a [T].
     * Such a proxy is not returned, as calling it from another thread fails,
     * and dispatching its calls to the thread of this `Python` would fail once it is closed.
     * It is rejected also when called by a Kotlin function, which is called from Python, on the thread of this `Python`.
     * @throws IllegalStateException if different keys of a returned `dict`, or elements of a [Set], have the same `repr`.
     * @throws InterruptedException if the calling thread is interrupted while the call waits for its turn, or runs.
     */
    @Throws(InterruptedException::class)
    public inline fun <reified T : Any> eval(@Language("python") expression: String): T =
        eval(expression, T::class.javaObjectType)

    /**
     * Evaluates a Python [expression], converting its value to the given [type].
     *
     * A value which Jep cannot convert, e.g. of `eval("len", Any::class.java)`, or nested in a [Collection] or a [Map],
     * becomes its Python `repr`.
     * So does a Python object requested as an interface which a [String] implements,
     * e.g. `Comparable` or `java.io.Serializable`, so that the `repr` is returned instead of a proxy.
     *
     * @throws jep.JepException if the evaluation fails, or the value cannot be converted.
     * A value holding Python objects is copied to a [List], a [Set], a [Map] or an array, see [Python],
     * so it cannot be of another collection type, like `ArrayDeque`.
     *
     * @throws IllegalArgumentException if the [type] is a [PyObject], or an array of them, which cannot leave the thread of this `Python`,
     * or if the [type] is another collection type, and the value holds Python objects,
     * or if the value is a Python object, which Jep converts to a proxy of the interface [type],
     * like a Python callable of `eval("lambda: 1", Runnable::class.java)`, as its `repr` is not of the [type].
     * Such a proxy is not returned, as calling it from another thread fails,
     * and dispatching its calls to the thread of this `Python` would fail once it is closed.
     * It is rejected also when called by a Kotlin function, which is called from Python, on the thread of this `Python`.
     * @throws IllegalStateException if different keys of a returned `dict`, or elements of a [Set], have the same `repr`.
     * @throws InterruptedException if the calling thread is interrupted while the call waits for its turn, or runs.
     */
    @Throws(InterruptedException::class)
    public fun <T : Any> eval(@Language("python") expression: String, type: Class<T>): T = call {
        // checked in the call, so that a closed Python fails as closed
        require(!type.holdsPyObject()) { "A PyObject cannot leave the thread of Python" }
        val raw = getValue(expression, type)
        val value = toJava(raw)
        // a repr is a String, and a copy a List, a Set, a Map, or an array, which might not be the type requested
        require(value === raw || type.isInstance(value)) {
            if (raw.toPyObject() != null) "A Python object is converted to its repr, which is not a ${type.typeName}"
            else "A value holding Python objects is copied to a List, a Set, a Map or an array, which is not a ${type.typeName}"
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
     * @throws InterruptedException if the calling thread is interrupted while the call waits for its turn, or runs.
     */
    @Throws(InterruptedException::class)
    public operator fun get(name: String): Any? = call { toJava(getValue(name)) }

    /**
     * Sets the global variable of the given [name] to the [value], converted to a Python object.
     *
     * @throws InterruptedException if the calling thread is interrupted while the call waits for its turn, or runs.
     */
    @Throws(InterruptedException::class)
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
     * If closing the interpreter fails, its exception is thrown by this `close()`, which waits for it.
     * If the waiting is interrupted, it throws an [InterruptedException] at once, clearing the interrupt flag of the thread,
     * and the interpreter is still closed afterward, in the background, but a failure of closing it is not reported.
     *
     * @throws InterruptedException if the waiting is interrupted.
     *
     * Only the first `close()` can wait.
     * Calling it again has no effect, and returns immediately,
     * also while the interpreter is not closed yet: while the first `close()` waits for it,
     * or after it is interrupted, or when it has returned without waiting, see below.
     * So it throws no failure of closing the interpreter again,
     * e.g. when `use` or try-with-resources closes a `Python` closed explicitly in its block.
     * Neither can it deadlock when the closing of the interpreter, which can run Python code, waits for it:
     * when it is called by a Kotlin function, which `__del__` of a global variable calls on the thread of this `Python`,
     * or by another thread this function waits for.
     *
     * With a call running, or starting or ending, it does not wait for it:
     * it returns immediately, the running call continues and completes normally,
     * still able to call back this `Python`, and the interpreter is closed after it returns.
     * Then nobody waits for the closing, so an exception thrown by closing the interpreter is not reported,
     * and if the JVM exits before the running call returns, e.g. at the end of `main`,
     * the interpreter is never closed, as its thread is a daemon one.
     * Waiting would deadlock, when the running call waits for `close()`:
     * when it is called by a Kotlin function called from Python, or by another thread, which this function,
     * or the Python code, waits for, and it cannot be told apart from a `close()` which the running call does not wait for.
     */
    @Throws(InterruptedException::class)
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // the calls submitted before, and still waiting for their turn, see it closed when it comes,
        // and the ones submitted afterward are rejected, so that this task is the last one to touch the interpreter
        // explicitly a Runnable, unlike the calls, which tests rely on to intercept only this task
        val closing = try {
            executor.submit(Runnable {
                try {
                    interpreter?.close()
                } finally {
                    // defensive only: nothing can use the interpreter afterward, as the executor is shut down
                    interpreter = null
                }
            })
        } finally {
            executor.shutdown()
        }
        // the task of close() can run only after the running call, which might wait for this close(),
        // called by a Kotlin function, called from Python, or by a thread it waits for, so waiting here could deadlock;
        // read after setting closed, so that a call, which is not running yet, sees it closed before running any code,
        // and then the task of close() runs right after it
        if (running) return
        try {
            closing.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            // the interpreter is still closed by its task, which is not cancelled
            throw e
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
                        // assigned only once checked, so that a later call does not use an interpreter failing the check
                        PythonEnvironment.starter.createInterpreter(
                            create = newInterpreter,
                            check = { PythonEnvironment.checkUtf8Mode(it) }
                        ).also { interpreter = it }
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
            // a call still waiting for its turn never runs, while one already taken by the thread of this Python completes
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
     * Replaces each Python object of the [value], also nested in a [Collection], a [Map] or an array, with its `repr`,
     * as it is bound to the thread of this interpreter.
     * A Python object is a [PyObject], or a proxy of a Java interface, which Jep creates for it, see [toPyObject].
     *
     * Only a top-level [PyObject] is closed, as it is created by Jep for this value.
     * A nested one can be held by a Java collection, which Python can still use, e.g. set with `python["k"] = list`,
     * so it is only left out of the returned copy, and Jep releases it once it is garbage collected.
     * Neither is the [PyObject] of a proxy closed, also of a top-level one,
     * as the proxy can be held by Python, e.g. created with `jep.jproxy`, which Jep returns as it is.
     */
    private fun SharedInterpreter.toJava(value: Any?): Any? = when (value) {
        is PyObject -> value.use { PyObjectConverter(this).use { converter -> converter.convert(it) } }
        else -> if (value.toPyObject() != null || value is Collection<*> || value is Map<*, *> || value is Array<*>) {
            PyObjectConverter(this).use { it.convert(value) }
        } else {
            value
        }
    }

    /**
     * Converts a value returned by Jep, see [toJava].
     *
     * A value without any Python object, a [PyObject] or a proxy of one, is returned as it is.
     * Otherwise each [Collection], [Map] and array reachable from it is copied, never changed,
     * a [Set] to a [LinkedHashSet], another [Collection] than a [List] to an [ArrayList],
     * and an array to an array of the same component type, unless it cannot hold a `repr`, like `PyObject[]`,
     * which is widened to `Object[]`, also each dimension of a nested array, like `PyObject[][]` to `Object[][]`,
     * or unless it cannot hold the copy of an element, like `Runnable[]` of proxies,
     * which is widened to `Object[]` as well, so that the copy can be cast to the type requested from [eval],
     * also when it contains itself, so that the copy contains itself as well.
     * The same Python object becomes the same `repr`.
     */
    private class PyObjectConverter(private val interpreter: SharedInterpreter) : AutoCloseable {

        private var repr: PyCallable? = null

        /** The copy of each converted value, by its identity, so that a cycle is copied once. */
        private val copies = IdentityHashMap<Any, Any>()

        fun convert(value: Any?): Any? =
            if (containsPyObject(value, Collections.newSetFromMap(IdentityHashMap()))) copy(value) else value

        // only the containers are tracked, which can form a cycle, not to track each element of a large list
        // a Python object first, as a proxy can also implement a Collection or a Map, and must not be copied as one
        private fun containsPyObject(value: Any?, visited: MutableSet<Any>): Boolean =
            value.toPyObject() != null || containsPyObjectElement(value, visited)

        private fun containsPyObjectElement(value: Any?, visited: MutableSet<Any>): Boolean = when (value) {
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
            value.toPyObject()?.let { pyObject -> return repr(pyObject).also { copies[value] = it } }
            return when (value) {
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
                is Array<*> -> newArray(arrayCopyClass(value), value.size).also { copy ->
                    copies[value] = copy
                    value.forEachIndexed { index, element -> copy[index] = copy(element) }
                }
                else -> value
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun newArray(arrayClass: Class<*>, size: Int): Array<Any?> =
            java.lang.reflect.Array.newInstance(arrayClass.componentType, size) as Array<Any?>

        /** The class of the copy of each array, by its identity, decided before copying it. */
        private val arrayCopyClasses = IdentityHashMap<Array<*>, Class<*>>()

        /** The arrays whose class of the copy is being decided, to detect a cycle. */
        private val deciding = Collections.newSetFromMap(IdentityHashMap<Array<*>, Boolean>())

        /**
         * Returns the class of the copy of the [array]:
         * its own class, widened by [copyType], so that no array of a [PyObject] type is left, even an empty one,
         * unless it cannot hold the copy of an element, like a `repr` of a proxy in `Runnable[]`,
         * then an array of [Any] of the same dimensions, like `Object[][]`, or `Object[]` as the last resort.
         *
         * It is decided before copying the array, so that the copy can contain itself.
         * An array reached again while deciding, i.e. in a cycle, gets a class not depending on its elements,
         * as the arrays deciding in the meantime rely on it: its own one, if it can hold a copy of any element,
         * like `Object[]` or `Serializable[]`, or `Object[]` otherwise.
         */
        private fun arrayCopyClass(array: Array<*>): Class<*> {
            arrayCopyClasses[array]?.let { return it }
            val declared = copyType(array.javaClass)
            if (!deciding.add(array)) {
                return (if (canHoldAnyCopy(declared.componentType)) declared else Array<Any?>::class.java)
                    .also { arrayCopyClasses[array] = it }
            }
            val decided = when {
                // the common case, which can hold anything, so that its elements are not checked
                declared == Array<Any?>::class.java || canHoldCopies(declared, array) -> declared
                else -> objectArrayClass(dimensions(declared)).takeIf { canHoldCopies(it, array) }
                    ?: Array<Any?>::class.java
            }
            deciding.remove(array)
            // already decided, if reached again in a cycle
            return arrayCopyClasses.getOrPut(array) { decided }
        }

        /** Whether the [type] can hold any copy of an element, which is not copied as it is. */
        private fun canHoldAnyCopy(type: Class<*>): Boolean = listOf(
            String::class.java, ArrayList::class.java, LinkedHashSet::class.java,
            LinkedHashMap::class.java, Array<Any?>::class.java
        ).all { type.isAssignableFrom(it) }

        private fun canHoldCopies(arrayClass: Class<*>, array: Array<*>): Boolean = array.all { element ->
            copyClass(element)?.let { arrayClass.componentType.isAssignableFrom(it) } ?: true
        }

        /**
         * Widens a type which can hold a [PyObject], or its subtype, but not its `repr`, to [Any],
         * also each dimension of an array.
         */
        private fun copyType(type: Class<*>): Class<*> = when {
            type.isArray -> arrayClass(copyType(type.componentType))
            type.isAssignableFrom(PyObject::class.java) && !type.isAssignableFrom(String::class.java) -> Any::class.java
            PyObject::class.java.isAssignableFrom(type) -> Any::class.java
            else -> type
        }

        private fun dimensions(type: Class<*>): Int = if (type.isArray) 1 + dimensions(type.componentType) else 0

        private fun objectArrayClass(dimensions: Int): Class<*> =
            (1..dimensions).fold(Any::class.java as Class<*>) { type, _ -> arrayClass(type) }

        private fun arrayClass(componentType: Class<*>): Class<*> =
            java.lang.reflect.Array.newInstance(componentType, 0).javaClass

        /** Returns the class of the [copy] of the [value], or `null` for `null`. */
        private fun copyClass(value: Any?): Class<*>? = when {
            value == null -> null
            value.toPyObject() != null -> String::class.java
            value is Set<*> -> LinkedHashSet::class.java
            value is Collection<*> -> ArrayList::class.java
            value is Map<*, *> -> LinkedHashMap::class.java
            value is Array<*> -> arrayCopyClass(value)
            else -> value.javaClass
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

        /**
         * Creates a `Python`, whose interpreter is created by [newInterpreter], on the thread of this `Python`,
         * so that a test can make closing the interpreter fail.
         */
        internal fun withInterpreterFactory(
            newInterpreter: () -> SharedInterpreter
        ): Python = Python({ it }, newInterpreter)

        private val threadCounter = AtomicInteger()

        /**
         * Returns this value as a [PyObject], also when it is a proxy of a Java interface,
         * which Jep creates for a Python object converted to an interface,
         * or `null` if it is not a Python object.
         */
        private fun Any?.toPyObject(): PyObject? = when {
            this is PyObject -> this
            this != null && Proxy.isProxyClass(javaClass) ->
                (Proxy.getInvocationHandler(this) as? InvocationHandler)?.pyObject
            else -> null
        }

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
