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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlin.concurrent.thread
import kotlin.test.Test

/**
 * Tests of [Python.close] racing the calls made from other threads.
 *
 * The races are made deterministic with latches:
 * a Kotlin callback blocks a running call on the Python thread,
 * and the submission of a call to the Python thread is intercepted
 * on the threads named in the test.
 */
class PythonCloseTest {

    @Test
    fun `should fail call submitted after close with IllegalStateException`() {
        // given
        val racerSubmitting = CountDownLatch(1)
        val closed = CountDownLatch(1)
        Python.withDecoratedExecutor(interceptingSubmission(
            beforeSubmit = {
                if (Thread.currentThread().name == "racer") {
                    racerSubmitting.countDown()
                    closed.await()
                }
            }
        )).use { python ->
            python.exec("x = 1")
            var failure: Throwable? = null
            // passes the check of being closed, and then waits before submitting the call
            val racer = thread(name = "racer") {
                failure = runCatching { python.eval<Long>("x") }.exceptionOrNull()
            }
            racerSubmitting.await()

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
    fun `should fail call submitted while close waits for running call`() {
        // given
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val racerSubmitting = CountDownLatch(1)
        val racerSubmitted = CountDownLatch(1)
        val closeSubmitted = CountDownLatch(1)
        Python.withDecoratedExecutor(interceptingSubmission(
            beforeSubmit = {
                if (Thread.currentThread().name == "racer") {
                    racerSubmitting.countDown()
                    closeSubmitted.await()
                }
            },
            afterSubmit = {
                when (Thread.currentThread().name) {
                    "racer" -> racerSubmitted.countDown()
                    "closer" -> closeSubmitted.countDown()
                }
            }
        )).use { python ->
            python["block"] = { n: Long ->
                callbackEntered.countDown()
                releaseCallback.await()
                n
            }
            var runningResult: Long? = null
            val running = thread { runningResult = python.eval<Long>("block(42)") }
            callbackEntered.await()
            var racerResult: Long? = null
            var racerFailure: Throwable? = null
            // passes the check of being closed, and then waits before submitting the call
            val racer = thread(name = "racer") {
                runCatching { python.eval<Long>("1 + 1") }
                    .onSuccess { racerResult = it }
                    .onFailure { racerFailure = it }
            }
            racerSubmitting.await()

            // when
            val closer = thread(name = "closer") { python.close() }
            racerSubmitted.await()
            releaseCallback.countDown()
            running.join()
            racer.join()
            closer.join()

            // then
            assert(runningResult == 42L)
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
        Python.withDecoratedExecutor(interceptingSubmission(
            afterSubmit = {
                when (Thread.currentThread().name) {
                    "waiting" -> waitingSubmitted.countDown()
                    "closer" -> closeSubmitted.countDown()
                }
            }
        )).use { python ->
            python["block"] = { n: Long ->
                callbackEntered.countDown()
                releaseCallback.await()
                n
            }
            var runningResult: Long? = null
            val running = thread { runningResult = python.eval<Long>("block(42)") }
            callbackEntered.await()
            var waitingResult: Long? = null
            var waitingFailure: Throwable? = null
            // submitted before close, but waiting behind the running call
            val waiting = thread(name = "waiting") {
                runCatching { python.eval<Long>("1 + 1") }
                    .onSuccess { waitingResult = it }
                    .onFailure { waitingFailure = it }
            }
            waitingSubmitted.await()

            // when
            val closer = thread(name = "closer") { python.close() }
            closeSubmitted.await()
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

    /**
     * Decorates the executor of a [Python], to call [beforeSubmit] before,
     * and [afterSubmit] after submitting each task, also a rejected one,
     * on the submitting thread.
     */
    private fun interceptingSubmission(
        beforeSubmit: () -> Unit = {},
        afterSubmit: () -> Unit = {}
    ): (ExecutorService) -> ExecutorService = { executor ->
        object : ExecutorService by executor {

            override fun <T> submit(task: Callable<T>): Future<T> = intercept { executor.submit(task) }

            override fun submit(task: Runnable): Future<*> = intercept { executor.submit(task) }

            private fun <F> intercept(submit: () -> F): F {
                beforeSubmit()
                return try {
                    submit()
                } finally {
                    afterSubmit()
                }
            }

        }
    }

}
