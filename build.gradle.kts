@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalAbiValidation::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jreleaser.model.Active

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

val createPythonVenv = tasks.register<Exec>("createPythonVenv") {
    description = "Creates the Python virtual environment of the tests"
    // a local value, as the configuration cache cannot serialize a reference to the build script
    val python = venvPython.asFile
    outputs.file(python)
    onlyIf { !python.exists() }
    commandLine("python3", "-m", "venv", pythonVenv.asFile.absolutePath)
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
    // the installed packages as an input, so that the tests rerun after changing them
    inputs.files(installPythonPackages)
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
