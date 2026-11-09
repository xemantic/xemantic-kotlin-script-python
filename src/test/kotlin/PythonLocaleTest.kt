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
import kotlin.io.path.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test

/**
 * The embedded interpreter under a non-UTF-8 locale,
 * in a JVM of its own, as the Python environment can be initialized only once per JVM.
 */
class PythonLocaleTest {

    @Test
    fun `should read UTF-8 file and print non-ASCII text in embedded Python under non-UTF-8 locale`() {
        // given
        val file = createTempFile("python-locale", ".txt")
        file.writeText("zażółć gęślą jaźń 日本", Charsets.UTF_8)
        val code = "print(open(${file.toString().pythonLiteral()}).read().upper(), flush=True)"

        try {
            // when
            val (exitCode, output) = runInJvm(
                EmbeddedPythonMain::class.java.name,
                input = code,
                environment = mapOf("LC_ALL" to "C", "PYTHONUTF8" to "0", "PYTHONCOERCECLOCALE" to "0")
            )

            // then
            assert(output == "ZAŻÓŁĆ GĘŚLĄ JAŹŃ 日本")
            assert(exitCode == 0)
        } finally {
            file.deleteIfExists()
        }
    }

    private fun String.pythonLiteral() = "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"

    /**
     * Runs the [mainClass] of the test classpath in a new JVM with the [environment] variables
     * added to the inherited ones, unlike [runProcess], which would put Python in UTF-8 mode,
     * writing the [input] to its standard input, as UTF-8.
     *
     * @return the exit code and the output, merged with the standard error, decoded as UTF-8.
     */
    private fun runInJvm(
        mainClass: String,
        input: String,
        environment: Map<String, String>
    ): Pair<Int, String> {
        val java = Path(System.getProperty("java.home"), "bin", "java").toString()
        val builder = ProcessBuilder(
            java,
            "--enable-native-access=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"),
            mainClass
        ).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        return process.waitFor() to output
    }

}

/** Executes the Python code of its standard input, read as UTF-8, in an embedded [Python]. */
object EmbeddedPythonMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val code = System.`in`.readBytes().toString(Charsets.UTF_8)
        Python().use { it.exec(code) }
    }

}
