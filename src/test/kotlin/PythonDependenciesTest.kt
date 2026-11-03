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
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readAttributes
import kotlin.test.AfterTest
import kotlin.test.Test

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class PythonDependenciesTest {

    private val python = Path(System.getenv("VIRTUAL_ENV"), "bin", "python").toString()

    private val directory = createTempDirectory("python-dependencies")

    private val constraints = createTempFile("python-constraints", ".txt")

    @AfterTest
    fun deleteFiles() {
        directory.deleteRecursively()
        constraints.deleteRecursively()
    }

    private fun pipInstall(vararg requirements: String): Pair<Int, String> = runProcess(
        PythonDependencies.pipInstallCommand(python, directory.toString(), constraints.toString(), requirements.toList())
    )

    private fun Path.fileKey(): Any? = readAttributes<BasicFileAttributes>().fileKey()

    /**
     * pip does not see the distributions of a `--target` directory as installed,
     * so it would install them again, replacing the files of the modules, which might be already loaded.
     */
    @Test
    fun `should keep distribution of dependency directory when pip installs dependency requiring it`() {
        // given
        val installed = pipInstall("idna")
        val idnaModule = directory.resolve("idna").resolve("__init__.py")
        val idnaKey = idnaModule.fileKey()

        // when
        val (exitCode, output) = pipInstall("requests")

        // then
        assert(installed.first == 0)
        assert(exitCode == 0, output)
        assert(directory.resolve("requests").exists())
        assert(idnaModule.fileKey() == idnaKey)
    }

    @Test
    fun `should replace distribution of dependency directory when pip installs newer version of it`() {
        // given
        val installed = pipInstall("idna==3.6")

        // when
        val (exitCode, output) = pipInstall("idna>=3.7")

        // then
        assert(installed.first == 0)
        assert(exitCode == 0, output)
        val (_, version) = runProcess(
            listOf(python, "-c", "import sys; sys.path.insert(0, sys.argv[1]); import idna; print(idna.__version__)", directory.toString())
        )
        assert(version != "3.6")
        assert(version.startsWith("3."))
    }

}
