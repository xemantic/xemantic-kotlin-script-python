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

import com.xemantic.kotlin.script.ExecutionPhase
import com.xemantic.kotlin.script.ScriptResult
import com.xemantic.kotlin.script.ScriptSession
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.should
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** The triple quote of a Kotlin raw string, which cannot be nested in the raw string of a snippet. */
private const val TQ = "\"\"\""

/**
 * The tests of [Python] used by the Kotlin scripts of an AI agent, like [honest-annie](https://github.com/xemantic/honest-annie),
 * where the scripts written by an LLM access Python through the agency provided to them.
 */
class PythonScriptSessionTest {

    /**
     * The agency of an agent, outliving the scripts of its session,
     * starting its [Python] only when a script uses it, as starting the first one initializes the Python environment.
     */
    class Agency : AutoCloseable {

        private val lazyPython = lazy { Python() }

        val python: Python by lazyPython

        val pythonStarted: Boolean get() = lazyPython.isInitialized()

        override fun close() {
            if (lazyPython.isInitialized()) python.close()
        }

    }

    private val agency = Agency()

    private val session = ScriptSession {
        "agency" provides agency
    }

    @AfterTest
    fun tearDown() {
        session.close()
        agency.close()
    }

    @Test
    fun `should not start Python until script uses it`() = runTest {
        // when
        val result = session.execute("listOf(1, 2, 3).sum()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 6)
        }
        assert(!agency.pythonStarted)
    }

    @Test
    fun `should keep Python state between snippets`() = runTest {
        // given
        session.execute("""agency.python("a = list(range(10))")""")
        session.execute("""agency.python["factor"] = 3""")

        // when
        val result = session.execute("""agency.python("[x * factor for x in a]")""")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == listOf(0L, 3L, 6L, 9L, 12L, 15L, 18L, 21L, 24L, 27L))
        }
    }

    @Test
    fun `should pass values between Kotlin and Python`() = runTest {
        // given
        session.execute(
            """
            val names = listOf("Alice", "Bob")
            agency.python["count"] = names.size
            """.trimIndent()
        )

        // when
        val result = session.execute("""(agency.python("count * 10") as Long) + names.size""")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 22L)
        }
    }

    @Test
    fun `should call Kotlin function defined in snippet from Python`() = runTest {
        // given
        session.execute(
            """
            val prices = mapOf("apple" to 3L, "pear" to 5L)
            agency.python["price"] = { name: String -> prices.getValue(name) }
            """.trimIndent()
        )

        // when
        val result = session.execute(
            """
            agency.python($TQ
                total = sum(price(fruit) for fruit in ['apple', 'pear', 'apple'])
                total
            $TQ.trimIndent())
            """.trimIndent()
        )

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 11L)
        }
    }

    /**
     * The use case of an AI harness, where an LLM directs a persistent session over several invocations:
     * it checks if RDKit is installed, finds it is not, and then declares it as a dependency of the Python code
     * computing molecular descriptors of the molecules declared, and partially computed, in Kotlin,
     * which it processes further in Kotlin.
     *
     * RDKit is downloaded from PyPI, into the dependency directory of the tests, cleaned by Gradle before they run.
     */
    @Test
    fun `should install Python dependency declared by LLM in later snippet`() = runTest(timeout = 10.minutes) {
        // given
        val check = session.execute(
            """
            agency.python($TQ
                import importlib.util
                importlib.util.find_spec("rdkit") is not None
            $TQ.trimIndent())
            """.trimIndent()
        )
        check should {
            be<ScriptResult.Value>()
            have(value == false)
        }

        // when
        val result = session.execute(
            """
            val drugs = mapOf(
                "aspirin" to "CC(=O)OC1=CC=CC=C1C(=O)O",
                "caffeine" to "CN1C=NC2=C1C(=O)N(C(=O)N2C)C",
                "ibuprofen" to "CC(C)CC1=CC=C(C=C1)C(C)C(=O)O"
            )
            // the primary alcohols with 1, 3 and 5 carbon atoms
            val alcohols = listOf("methanol", "propanol", "pentanol").mapIndexed { index, name ->
                name to "C".repeat(2 * index + 1) + "O"
            }
            agency.python["smiles"] = drugs + alcohols
            
            @Suppress("UNCHECKED_CAST")
            val molecules = agency.python($TQ
                # /// script
                # dependencies = ["rdkit"]
                # ///
                from rdkit import Chem
                from rdkit.Chem import Descriptors, rdMolDescriptors
            
                molecules = []
                for name, code in smiles.items():
                    molecule = Chem.MolFromSmiles(code)
                    molecules.append({
                        "name": name,
                        "formula": rdMolDescriptors.CalcMolFormula(molecule),
                        "weight": round(Descriptors.MolWt(molecule), 2),
                        "logP": round(Descriptors.MolLogP(molecule), 2),
                    })
                molecules
            $TQ.trimIndent()) as List<Map<String, Any>>
            
            molecules
                .sortedByDescending { it["weight"] as Double }
                .joinToString("\n") { molecule ->
                    val solubility = if ((molecule["logP"] as Double) < 0) "hydrophilic" else "lipophilic"
                    listOf(molecule["name"], molecule["formula"], molecule["weight"], solubility).joinToString(" ")
                }
            """.trimIndent()
        )

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == """
                ibuprofen C13H18O2 206.28 lipophilic
                caffeine C8H10N4O2 194.19 hydrophilic
                aspirin C9H8O4 180.16 lipophilic
                pentanol C5H12O 88.15 lipophilic
                propanol C3H8O 60.1 lipophilic
                methanol CH4O 32.04 hydrophilic
            """.trimIndent())
        }
    }

    @Test
    fun `should report Python exception as evaluation error`() = runTest {
        // when
        val result = session.execute("""agency.python("raise ValueError('boom')")""")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                jep.JepException: <class 'ValueError'>: boom
                  at <string>.<module>(<string>:1)
            """.trimIndent()
        }
    }

}
