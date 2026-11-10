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

import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

/**
 * Runs the [mainClass] of the test classpath in a new JVM, with the [jvmArguments],
 * and with the [environment] variables added to the inherited ones,
 * unlike [runProcess], which would put Python in UTF-8 mode,
 * writing the [input] to its standard input, as UTF-8.
 *
 * A test of the embedded Python runs in a JVM of its own,
 * when it needs another Python environment, which can be initialized only once per JVM.
 *
 * @return the exit code and the output, merged with the standard error, decoded as UTF-8.
 */
internal fun runInJvm(
    mainClass: String,
    input: String,
    environment: Map<String, String>,
    jvmArguments: List<String> = emptyList()
): Pair<Int, String> {
    val java = Path(System.getProperty("java.home"), "bin", "java").toString()
    // a file, unlike a pipe, does not block the JVM when nobody reads it, so that it can be awaited with a timeout
    val outputFile = createTempFile("jvm-output", ".txt")
    try {
        val builder = ProcessBuilder(
            listOf(java, "--enable-native-access=ALL-UNNAMED") + jvmArguments +
                listOf("-cp", System.getProperty("java.class.path"), mainClass)
        ).redirectErrorStream(true).redirectOutput(outputFile.toFile())
        builder.environment().putAll(environment)
        val process = builder.start()
        try {
            process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
            val ended = process.waitFor(5, TimeUnit.MINUTES)
            val output = outputFile.readText(Charsets.UTF_8).trim()
            check(ended) { "The JVM of $mainClass has not ended in 5 minutes, its output:\n$output" }
            return process.exitValue() to output
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
    } finally {
        outputFile.deleteIfExists()
    }
}
