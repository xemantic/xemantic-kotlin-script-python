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
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Tests of [Python.close] racing the calls made from other threads, or called from a Kotlin callback.
 *
 * The races are made deterministic with latches:
 * a Kotlin callback blocks a running call on the Python thread,
 * and the submission of a call to the Python thread, and the shutdown of the executor,
 * are intercepted on the threads named in the test.
 * Each wait for a latch is bounded, only to fail a broken test instead of hanging the JVM.
 */
class PythonCloseTest {

    @Test
    fun `should fail call submitted after close with IllegalStateException`() {
        // given
        val racerSubmitting = CountDownLatch(1)
        val closed = CountDownLatch(1)
        Python.withDecoratedExecutor(interceptingExecutor(
            beforeSubmit = {
                if (Thread.currentThread().name == "racer") {
                    racerSubmitting.countDown()
                    assert(closed.await(10, SECONDS))
                }
            }
        )).use { python ->
            python.exec("x = 1")
            var failure: Throwable? = null
            // passes the check of being closed, and then waits before submitting the call
            val racer = thread(name = "racer") {
                failure = runCatching { python.eval<Long>("x") }.exceptionOrNull()
            }
            assert(racerSubmitting.await(10, SECONDS))

            // when
            python.close()
            closed.countDown()
            racer.join()

            // then
            assert(failure is IllegalStateException)
            assert(failure?.message == "Python is closed")
        }
    }

    @Test
    fun `should fail call submitted after task of close while close waits for running call`() {
        // given
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val racerSubmitting = CountDownLatch(1)
        val racerSubmitted = CountDownLatch(1)
        val closeSubmitted = CountDownLatch(1)
        var racerAccepted: Boolean? = null
        Python.withDecoratedExecutor(interceptingExecutor(
            beforeSubmit = {
                if (Thread.currentThread().name == "racer") {
                    racerSubmitting.countDown()
                    assert(closeSubmitted.await(10, SECONDS))
                }
            },
            afterSubmit = { accepted ->
                when (Thread.currentThread().name) {
                    "racer" -> {
                        racerAccepted = accepted
                        racerSubmitted.countDown()
                    }
                    "closer" -> closeSubmitted.countDown()
                }
            },
            // the racer submits between the task of close() and the shutdown of the executor
            beforeShutdown = {
                if (Thread.currentThread().name == "closer") {
                    assert(racerSubmitted.await(10, SECONDS))
                }
            }
        )).use { python ->
            python["block"] = { n: Long ->
                callbackEntered.countDown()
                assert(releaseCallback.await(10, SECONDS))
                n
            }
            var runningResult: Long? = null
            val running = thread { runningResult = python.eval<Long>("block(42)") }
            assert(callbackEntered.await(10, SECONDS))
            var racerResult: Long? = null
            var racerFailure: Throwable? = null
            // passes the check of being closed, and then waits before submitting the call
            val racer = thread(name = "racer") {
                runCatching { python.eval<Long>("1 + 1") }
                    .onSuccess { racerResult = it }
                    .onFailure { racerFailure = it }
            }
            assert(racerSubmitting.await(10, SECONDS))

            // when
            val closer = thread(name = "closer") { python.close() }
            assert(racerSubmitted.await(10, SECONDS))
            releaseCallback.countDown()
            running.join()
            racer.join()
            closer.join()

            // then
            assert(runningResult == 42L)
            // queued after the task of close(), not rejected by the executor
            assert(racerAccepted == true)
            // a result would mean a new interpreter, created after closing the previous one, and never closed
            assert(racerResult == null)
            assert(racerFailure is IllegalStateException)
            assert(racerFailure?.message == "Python is closed")
        }
    }

    @Test
    fun `should fail call waiting for its turn when closed`() {
        // given
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val waitingSubmitted = CountDownLatch(1)
        val closeSubmitted = CountDownLatch(1)
        Python.withDecoratedExecutor(interceptingExecutor(
            afterSubmit = {
                when (Thread.currentThread().name) {
                    "waiting" -> waitingSubmitted.countDown()
                    "closer" -> closeSubmitted.countDown()
                }
            }
        )).use { python ->
            python["block"] = { n: Long ->
                callbackEntered.countDown()
                assert(releaseCallback.await(10, SECONDS))
                n
            }
            var runningResult: Long? = null
            val running = thread { runningResult = python.eval<Long>("block(42)") }
            assert(callbackEntered.await(10, SECONDS))
            var waitingResult: Long? = null
            var waitingFailure: Throwable? = null
            // submitted before close, but waiting behind the running call
            val waiting = thread(name = "waiting") {
                runCatching { python.eval<Long>("1 + 1") }
                    .onSuccess { waitingResult = it }
                    .onFailure { waitingFailure = it }
            }
            assert(waitingSubmitted.await(10, SECONDS))

            // when
            val closer = thread(name = "closer") { python.close() }
            assert(closeSubmitted.await(10, SECONDS))
            releaseCallback.countDown()
            running.join()
            waiting.join()
            closer.join()

            // then
            assert(runningResult == 42L)
            assert(waitingResult == null)
            assert(waitingFailure is IllegalStateException)
            assert(waitingFailure?.message == "Python is closed")
        }
    }

    @Test
    fun `should complete running call calling back Python after close is called from another thread`() {
        // given
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val closeSubmitted = CountDownLatch(1)
        Python.withDecoratedExecutor(interceptingExecutor(
            afterSubmit = {
                if (Thread.currentThread().name == "closer") closeSubmitted.countDown()
            }
        )).use { python ->
            python.exec("factor = 3")
            python["scale"] = { n: Long ->
                callbackEntered.countDown()
                assert(releaseCallback.await(10, SECONDS))
                n * python.eval<Long>("factor")
            }
            var runningResult: Long? = null
            var runningFailure: Throwable? = null
            val running = thread {
                runCatching { python.eval<Long>("scale(7)") }
                    .onSuccess { runningResult = it }
                    .onFailure { runningFailure = it }
            }
            assert(callbackEntered.await(10, SECONDS))

            // when
            val closer = thread(name = "closer") { python.close() }
            assert(closeSubmitted.await(10, SECONDS))
            releaseCallback.countDown()
            running.join()
            closer.join()

            // then
            assert(runningFailure == null)
            assert(runningResult == 21L)
        }
    }

    @Test
    fun `should close Python from Kotlin callback after running call returns`() {
        // given
        val events = CopyOnWriteArrayList<String>()
        val closeTaskRan = CountDownLatch(1)
        Python.withDecoratedExecutor(interceptingExecutor(
            // only the task of close() is submitted as a Runnable, the calls are submitted as a Callable
            decorateRunnable = { task ->
                Runnable {
                    task.run()
                    events += "closed on ${Thread.currentThread().name}"
                    closeTaskRan.countDown()
                }
            }
        )).use { python ->
            var pythonThread: String? = null
            python["stop"] = {
                pythonThread = Thread.currentThread().name
                python.close()
                events += "stop returned on ${Thread.currentThread().name}"
            }
            python["after"] = { events += "after on ${Thread.currentThread().name}" }
            var execFailure: Throwable? = null

            // when
            // bounded, only to fail instead of hanging the JVM, if it deadlocks
            val caller = thread(isDaemon = true, name = "caller") {
                execFailure = runCatching { python.exec("stop()\nafter()") }.exceptionOrNull()
            }
            caller.join(10_000)

            // then
            assert(!caller.isAlive)
            assert(execFailure == null)
            assert(closeTaskRan.await(10, SECONDS))
            assert(pythonThread?.startsWith("python-") == true)
            assert(events == listOf(
                "stop returned on $pythonThread",
                "after on $pythonThread",
                "closed on $pythonThread"
            ))
            val laterFailure = runCatching { python.eval<Long>("1 + 1") }.exceptionOrNull()
            assert(laterFailure is IllegalStateException)
            assert(laterFailure.message == "Python is closed")
        }
    }

    @Test
    fun `should let running call call back Python after close requested from Kotlin callback`() {
        // given
        Python().use { python ->
            python.exec("factor = 3")
            python["stop"] = { python.close() }
            python["scale"] = { n: Long -> n * python.eval<Long>("factor") }
            var result: Any? = null
            var failure: Throwable? = null

            // when
            // bounded, only to fail instead of hanging the JVM, if it deadlocks
            val caller = thread(isDaemon = true, name = "caller") {
                runCatching { python("stop()\nscale(7)") }
                    .onSuccess { result = it }
                    .onFailure { failure = it }
            }
            caller.join(10_000)

            // then
            assert(!caller.isAlive)
            assert(failure == null)
            assert(result == 21L)
            val laterFailure = runCatching { python.eval<Long>("1 + 1") }.exceptionOrNull()
            assert(laterFailure is IllegalStateException)
            assert(laterFailure.message == "Python is closed")
        }
    }

    @Test
    fun `should close Python from another thread a Kotlin callback waits for`() {
        // given
        val events = CopyOnWriteArrayList<String>()
        val closeTaskRan = CountDownLatch(1)
        Python.withDecoratedExecutor(interceptingExecutor(
            decorateRunnable = { task ->
                Runnable {
                    task.run()
                    events += "closed"
                    closeTaskRan.countDown()
                }
            }
        )).use { python ->
            python["stop"] = {
                thread(isDaemon = true, name = "closer") { python.close() }.join()
                events += "stop returned"
            }
            python["after"] = { events += "after" }
            var execFailure: Throwable? = null

            // when
            // bounded, only to fail instead of hanging the JVM, if it deadlocks
            val caller = thread(isDaemon = true, name = "caller") {
                execFailure = runCatching { python.exec("stop()\nafter()") }.exceptionOrNull()
            }
            caller.join(DEADLOCK_TIMEOUT)

            // then
            assert(!caller.isAlive)
            assert(execFailure == null)
            assert(closeTaskRan.await(DEADLOCK_TIMEOUT, MILLISECONDS))
            assert(events == listOf("stop returned", "after", "closed"))
            val laterFailure = runCatching { python.eval<Long>("1 + 1") }.exceptionOrNull()
            assert(laterFailure is IllegalStateException)
            assert(laterFailure.message == "Python is closed")
        }
    }

    @Test
    fun `should close Python from coroutine on another dispatcher a Kotlin callback blocks on`() {
        // given
        Python().use { python ->
            python["stop"] = { runBlocking(Dispatchers.IO) { python.close() } }
            var failure: Throwable? = null

            // when
            // bounded, only to fail instead of hanging the JVM, if it deadlocks
            val caller = thread(isDaemon = true, name = "caller") {
                failure = runCatching { python.exec("stop()") }.exceptionOrNull()
            }
            caller.join(DEADLOCK_TIMEOUT)

            // then
            assert(!caller.isAlive)
            assert(failure == null)
            val laterFailure = runCatching { python.eval<Long>("1 + 1") }.exceptionOrNull()
            assert(laterFailure is IllegalStateException)
            assert(laterFailure.message == "Python is closed")
        }
    }

    @Test
    fun `should close Python from Kotlin callback called on a thread started by Python`() {
        // given
        val closeTaskRan = CountDownLatch(1)
        Python.withDecoratedExecutor(interceptingExecutor(
            decorateRunnable = { task ->
                Runnable {
                    task.run()
                    closeTaskRan.countDown()
                }
            }
        )).use { python ->
            var stopThread: Thread? = null
            python["stop"] = {
                stopThread = Thread.currentThread()
                python.close()
            }
            var result: Any? = null
            var failure: Throwable? = null

            // when
            // bounded, only to fail instead of hanging the JVM, if it deadlocks
            val caller = thread(isDaemon = true, name = "caller") {
                runCatching {
                    python(
                        """
                        import threading
                        t = threading.Thread(target=stop)
                        t.start()
                        t.join()
                        'joined'
                        """.trimIndent()
                    )
                }
                    .onSuccess { result = it }
                    .onFailure { failure = it }
            }
            caller.join(DEADLOCK_TIMEOUT)

            // then
            assert(!caller.isAlive)
            assert(failure == null)
            assert(result == "joined")
            assert(closeTaskRan.await(DEADLOCK_TIMEOUT, MILLISECONDS))
            // a thread of Python, not the one running the calls
            assert(stopThread?.name?.startsWith("python-") == false)
        }
    }

    @Test
    fun `should wait for interpreter to be closed when closing Python without running call`() {
        // given
        val closeTaskRan = CountDownLatch(1)
        val python = Python.withDecoratedExecutor(interceptingExecutor(
            decorateRunnable = { task ->
                Runnable {
                    task.run()
                    closeTaskRan.countDown()
                }
            }
        ))
        python.exec("x = 1")

        // when
        python.close()

        // then
        assert(closeTaskRan.count == 0L)
    }

    /**
     * Decorates the executor of a [Python], to call on the calling thread:
     * [beforeSubmit] before, and [afterSubmit] after submitting each task, telling if it was accepted,
     * and [beforeShutdown] before shutting the executor down,
     * while each submitted [Runnable] is replaced with the one returned by [decorateRunnable].
     *
     * The task of [Python.close] is the only one submitted as a [Runnable], the calls are submitted as a [Callable],
     * so [decorateRunnable] intercepts only the closing of the interpreter.
     */
    private fun interceptingExecutor(
        beforeSubmit: () -> Unit = {},
        afterSubmit: (accepted: Boolean) -> Unit = {},
        beforeShutdown: () -> Unit = {},
        decorateRunnable: (Runnable) -> Runnable = { it }
    ): (ExecutorService) -> ExecutorService = { executor ->
        object : ExecutorService by executor {

            override fun <T> submit(task: Callable<T>): Future<T> = intercept { executor.submit(task) }

            override fun submit(task: Runnable): Future<*> = intercept { executor.submit(decorateRunnable(task)) }

            override fun shutdown() {
                beforeShutdown()
                executor.shutdown()
            }

            private fun <F> intercept(submit: () -> F): F {
                beforeSubmit()
                var accepted = false
                return try {
                    submit().also { accepted = true }
                } finally {
                    afterSubmit(accepted)
                }
            }

        }
    }

    private companion object {

        /** Generous, so that only a deadlock, never a load of the machine, fails a test. */
        const val DEADLOCK_TIMEOUT = 120_000L

    }

}
