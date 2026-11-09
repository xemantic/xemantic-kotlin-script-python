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
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
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

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `should import from non-ASCII dependency directory in embedded Python under ISO-8859-1 locale`() {
        // given
        val directory = createTempDirectory("python-locale")
        try {
            val locales = directory.resolve("locales").createDirectory()
            assumeTrue(createIso88591Locale(locales), "cannot create an ISO-8859-1 locale with localedef")
            // the bytes of josé in UTF-8, which are josÃ© in ISO-8859-1, the encoding of the paths of the forked JVM
            val dependencyDirectory = directory.resolve("josé").createDirectory()
            dependencyDirectory.resolve("latin_module.py").writeText("VALUE = 'imported'\n")
            // jep is installed in the environment, so nothing is installed, but the dependency directory is importable
            val code = """
                |# /// script
                |# dependencies = ["jep"]
                |# ///
                |import locale, latin_module
                |print(locale.nl_langinfo(locale.CODESET), latin_module.VALUE, flush=True)
                |""".trimMargin()

            // when
            val (exitCode, output) = runInJvm(
                EmbeddedPythonMain::class.java.name,
                input = code,
                environment = mapOf("LOCPATH" to locales.toString(), "LC_ALL" to "en_US.ISO-8859-1"),
                jvmArguments = listOf("-Dxemantic.kotlin.script.python.dependencyDirectory=$dependencyDirectory")
            )

            // then
            assert(output == "ISO-8859-1 imported")
            assert(exitCode == 0)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun String.pythonLiteral() = "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"

    /**
     * Creates the `en_US.ISO-8859-1` locale in the [directory], for the `LOCPATH` environment variable,
     * as the machine might have no locale of this encoding installed.
     *
     * @return `true` if created, `false` if `localedef` is not available, or fails.
     */
    private fun createIso88591Locale(directory: Path): Boolean {
        val locale = directory.resolve("en_US.ISO-8859-1")
        return try {
            // the exit code is not checked, as localedef can warn about the definition, and still create the locale
            runProcess(listOf("localedef", "-i", "en_US", "-f", "ISO-8859-1", locale.toString()))
            locale.resolve("LC_CTYPE").exists()
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Runs the [mainClass] of the test classpath in a new JVM, with the [jvmArguments],
     * and with the [environment] variables added to the inherited ones,
     * unlike [runProcess], which would put Python in UTF-8 mode,
     * writing the [input] to its standard input, as UTF-8.
     *
     * @return the exit code and the output, merged with the standard error, decoded as UTF-8.
     */
    private fun runInJvm(
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

}

/** Executes the Python code of its standard input, read as UTF-8, in an embedded [Python]. */
object EmbeddedPythonMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val code = System.`in`.readBytes().toString(Charsets.UTF_8)
        Python().use { it.exec(code) }
    }

}
