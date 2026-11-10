@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalAbiValidation::class, ExperimentalPathApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jreleaser.model.Active
import java.io.ByteArrayOutputStream
import java.nio.file.LinkOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.power.assert)
    alias(libs.plugins.dokka)
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.xemantic.conventions)
}

group = "com.xemantic.kotlin"

xemantic {
    description = "Stateful Python, embedded with Jep, in Kotlin scripts"
    inceptionYear = "2026"
    applyAllConventions()
}

fun MavenPomDeveloperSpec.projectDevs() {
    developer {
        id = "morisil"
        name = "Kazik Pogoda"
        url = "https://github.com/morisil"
    }
}

val javaTarget = libs.versions.javaTarget.get()
val kotlinTarget = KotlinVersion.fromVersion(libs.versions.kotlinTarget.get())

kotlin {

    explicitApi()

    abiValidation {}

    // set up according to https://jakewharton.com/gradle-toolchains-are-rarely-a-good-idea/
    compilerOptions {
        apiVersion = kotlinTarget
        languageVersion = kotlinTarget
        jvmTarget = JvmTarget.fromTarget(javaTarget)
        freeCompilerArgs.add("-Xjdk-release=$javaTarget")
        extraWarnings = true
        progressiveMode = true
    }

}

java {
    sourceCompatibility = JavaVersion.toVersion(javaTarget)
    targetCompatibility = JavaVersion.toVersion(javaTarget)
}

repositories {
    mavenCentral()
}

dependencies {
    // the Java side of Jep, its native library comes with the jep Python package, installed with pip
    api(libs.jep)

    // only the tests run Python in a script session, the main code does not depend on xemantic-kotlin-script
    testImplementation(libs.xemantic.kotlin.script)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.xemantic.kotlin.test)
    // only the tests bind an SLF4J backend, a library leaves this choice to the application
    testRuntimeOnly(libs.logback.classic)
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.ow2.asm") {
            useVersion(libs.versions.asm.get())
        }
    }
}

// the Python virtual environment of the tests, with the jep package matching the org.ninia:jep library
val pythonVenv = layout.projectDirectory.dir(".venv")
val venvPython = pythonVenv.file("bin/python")

// the services which a task action can use, as the configuration cache cannot serialize the project
interface InjectedServices {
    @get:Inject
    val exec: ExecOperations
}

val createPythonVenv = tasks.register("createPythonVenv") {
    description = "Creates the Python virtual environment of the tests, unless a complete one of the base Python exists"
    // the PATH of the Gradle daemon can differ from the one of the shell, e.g. resolve to an old python3 of macOS,
    // so the base Python can be chosen with -PbasePython=..., or the ORG_GRADLE_PROJECT_basePython variable on CI;
    // only when chosen, it can replace a complete environment, so that a daemon of an IDE with another python3
    // on its PATH keeps the environment created in a shell
    // blank like unset, as a CI matrix can pass an empty ORG_GRADLE_PROJECT_basePython
    val chosenBasePython = providers.gradleProperty("basePython").orNull?.takeIf { it.isNotBlank() }
    val basePython = chosenBasePython ?: "python3"
    // local values, as the configuration cache cannot serialize a reference to the build script
    val exec = objects.newInstance<InjectedServices>().exec
    val venv = pythonVenv.asFile.toPath()
    val python = venvPython.asFile
    // the previous environment, moved aside while creating a new one, to be restored when it fails
    val previousVenv = layout.projectDirectory.dir(".venv.previous").asFile.toPath()
    // the identity of the base Python of the environment, an input of the tests, so that they rerun when it changes
    val identityFile = pythonVenv.file("base-python.txt").asFile
    outputs.file(identityFile)
    outputs.upToDateWhen { false }
    doLast {
        // isolated (-I) from the environment variables and the user site-packages, prints the identity of the base Python:
        // its prefix and version, same for python3 and /usr/bin/python3, but not for python3.12 and python3.13;
        // exits with 3 when too old for jep, 4 without pip, which only a virtual environment needs
        fun probe(python: String): Triple<Int, List<String>, String>? {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val result = try {
                exec.exec {
                    commandLine(
                        python, "-I", "-c",
                        "import sys, importlib.util; print(sys.base_prefix); print('%d.%d.%d' % sys.version_info[:3]); " +
                            "sys.exit(3 if sys.version_info < (3, 10) else 4 if importlib.util.find_spec('pip') is None else 0)"
                    )
                    standardOutput = out
                    errorOutput = err
                    isIgnoreExitValue = true
                }
            } catch (e: Exception) {
                return null
            }
            // the last lines, as a sitecustomize or a .pth file of the site-packages can still print before
            return Triple(result.exitValue, out.toString().trim().lines(), err.toString().trim())
        }
        fun identity(lines: List<String>) = lines.takeLast(2).joinToString("\n")

        // a creation interrupted before, e.g. by killing the build, left the previous environment aside
        if (previousVenv.exists(LinkOption.NOFOLLOW_LINKS)) {
            venv.deleteRecursively()
            previousVenv.moveTo(venv)
        }
        // not just the empty directory which Gradle creates for the identity file
        val venvPresent = venv.isDirectory() && venv.listDirectoryEntries().isNotEmpty()
        val venvProbe = if (python.exists()) probe(python.absolutePath) else null
        val venvIdentity = venvProbe?.takeIf { it.first == 0 && it.second.size >= 2 }?.let { identity(it.second) }
        val identity = if (venvIdentity != null && chosenBasePython == null) {
            venvIdentity
        } else {
            val hint = "set another one with -PbasePython=<path>"
            val (exitValue, lines, error) = probe(basePython)
                ?: throw GradleException("Cannot run the base Python '$basePython', jep requires Python 3.10 or newer, $hint")
            when (exitValue) {
                0, 4 -> {}
                3 -> throw GradleException(
                    "The base Python '$basePython' is version ${lines.last()}, but jep requires Python 3.10 or newer, $hint"
                )
                else -> throw GradleException(
                    "The base Python '$basePython' failed with exit value $exitValue, " +
                        "jep requires Python 3.10 or newer, $hint:\n$error"
                )
            }
            val baseIdentity = identity(lines)
            if (baseIdentity != venvIdentity) {
                val version = lines.last()
                logger.lifecycle(
                    when {
                        !venvPresent -> "Creating the Python virtual environment $venv with $basePython ($version)"
                        venvIdentity == null -> "The Python virtual environment $venv is too old or incomplete, " +
                            "creating it again with $basePython ($version)"
                        else -> "The Python virtual environment $venv is of another base Python, " +
                            "creating it again with $basePython ($version)"
                    }
                )
                // moved aside instead of deleted, to be restored when the new one fails, unless without its python,
                // like the empty directory which Gradle creates for the identity file;
                // not created elsewhere and moved, as the scripts of a virtual environment contain its absolute path
                val hasPrevious = python.toPath().exists(LinkOption.NOFOLLOW_LINKS)
                if (hasPrevious) venv.moveTo(previousVenv) else venv.deleteRecursively()
                var created = false
                try {
                    val out = ByteArrayOutputStream()
                    val result = exec.exec {
                        commandLine(basePython, "-m", "venv", venv.toString())
                        standardOutput = out
                        errorOutput = out
                        isIgnoreExitValue = true
                    }
                    if (result.exitValue != 0) throw GradleException(
                        "Cannot create the Python virtual environment with '$basePython', exit value ${result.exitValue}" +
                            (if (hasPrevious) ", the previous one is kept" else "") + ":\n" + out.toString().trim()
                    )
                    created = true
                } finally {
                    // on any failure, as a half-made environment, e.g. without pip, would be taken for a complete one
                    if (!created) {
                        venv.deleteRecursively()
                        if (hasPrevious) previousVenv.moveTo(venv)
                    }
                }
                previousVenv.deleteRecursively()
            }
            baseIdentity
        }
        if (!identityFile.isFile || identityFile.readText() != identity) identityFile.writeText(identity)
    }
}

val installPythonPackages = tasks.register<Exec>("installPythonPackages") {
    description = "Installs jep into the virtual environment of the tests"
    dependsOn(createPythonVenv)
    // only jep, like a clean environment of an agent, the tests declare the packages they use as dependencies
    val packages = listOf("jep==${libs.versions.jep.get()}")
    inputs.property("packages", packages)
    // in the virtual environment, so that the packages are installed again when it is recreated
    val marker = pythonVenv.file("installed-packages.txt")
    outputs.file(marker)
    commandLine(listOf(venvPython.asFile.absolutePath, "-m", "pip", "install", "--quiet", "--disable-pip-version-check") + packages)
    doLast {
        marker.asFile.writeText(packages.joinToString("\n"))
    }
}

tasks.test {
    // the installed packages and the base Python of the environment as inputs, so that the tests rerun after changing them
    inputs.files(installPythonPackages, createPythonVenv)
    environment("VIRTUAL_ENV", pythonVenv.asFile.absolutePath)
    // the dependencies declared by the tests are installed from scratch on every run, not into the user cache,
    // so that a test can check that a dependency is not installed yet
    val dependencyDirectory = layout.buildDirectory.dir("python-dependencies").get().asFile
    systemProperty("xemantic.kotlin.script.python.dependencyDirectory", dependencyDirectory.absolutePath)
    doFirst {
        dependencyDirectory.deleteRecursively()
    }
    // Jep loads its native library with System.load, restricted since JDK 24
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    useJUnitPlatform()
    if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_23)) {
        // the fast JAR file system of the Kotlin compiler calls sun.misc.Unsafe, the option is unknown before JDK 23
        jvmArgs("--sun-misc-unsafe-memory-access=allow")
    }
}

powerAssert {
    functions = listOf(
        "com.xemantic.kotlin.test.assert",
        "com.xemantic.kotlin.test.have"
    )
}

// https://kotlinlang.org/docs/dokka-migration.html#adjust-configuration-options
dokka {
    pluginsConfiguration.html {
        footerMessage = xemantic.copyright
    }
}

versionCatalogUpdate {
    // preserve the manual, logically-grouped ordering of libs.versions.toml
    sortByKey = false
    keep {
        // kotlinTarget / javaTarget are plain version constants with no version.ref
        versions = setOf("kotlinTarget", "javaTarget", "asm")
        keepUnusedVersions = false
    }
}

mavenPublishing {

    signAllPublications()

    publishToMavenCentral(automaticRelease = true)

    pom {

        name = rootProject.name
        description = xemantic.description
        inceptionYear = xemantic.inceptionYear
        url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}"

        organization {
            name = xemantic.organization
            url = xemantic.organizationUrl
        }

        licenses {
            license {
                name = "GNU Affero General Public License v3.0"
                url = "https://www.gnu.org/licenses/agpl-3.0.txt"
                distribution = "https://www.gnu.org/licenses/agpl-3.0.txt"
            }
        }

        scm {
            url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}"
            connection = "scm:git:git://github.com/${xemantic.gitHubAccount}/${rootProject.name}.git"
            developerConnection = "scm:git:ssh://git@github.com/${xemantic.gitHubAccount}/${rootProject.name}.git"
        }

        ciManagement {
            system = "GitHub"
            url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}/actions"
        }

        issueManagement {
            system = "GitHub"
            url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}/issues"
        }

        developers {
            projectDevs()
        }

    }

}

val releaseAnnouncementSubject = """🚀 ${rootProject.name} $version has been released!"""
val releaseAnnouncement = """
$releaseAnnouncementSubject

${xemantic.description}

${xemantic.releasePageUrl}
""".trim()

jreleaser {

    announce {
        webhooks {
            create("discord") {
                active = Active.ALWAYS
                message = releaseAnnouncement
                messageProperty = "content"
                structuredMessage = true
            }
        }
        linkedin {
            active = Active.ALWAYS
            subject = releaseAnnouncementSubject
            message = releaseAnnouncement
        }
        bluesky {
            active = Active.ALWAYS
            status = releaseAnnouncement
        }
    }

}
