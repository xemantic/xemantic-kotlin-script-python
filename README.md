# xemantic-kotlin-script-python

Stateful Python, embedded with [Jep](https://github.com/ninia/jep), in Kotlin scripts

[<img alt="Maven Central Version" src="https://img.shields.io/maven-central/v/com.xemantic.kotlin/xemantic-kotlin-script-python">](https://central.sonatype.com/artifact/com.xemantic.kotlin/xemantic-kotlin-script-python)
[<img alt="GitHub Release Date" src="https://img.shields.io/github/release-date/morisil/xemantic-kotlin-script-python">](https://github.com/morisil/xemantic-kotlin-script-python/releases)
[<img alt="license" src="https://img.shields.io/github/license/morisil/xemantic-kotlin-script-python?color=blue">](https://github.com/morisil/xemantic-kotlin-script-python/blob/main/LICENSE)

[<img alt="GitHub Actions Workflow Status" src="https://img.shields.io/github/actions/workflow/status/morisil/xemantic-kotlin-script-python/build-main.yml">](https://github.com/morisil/xemantic-kotlin-script-python/actions/workflows/build-main.yml)
[<img alt="GitHub branch check runs" src="https://img.shields.io/github/check-runs/morisil/xemantic-kotlin-script-python/main">](https://github.com/morisil/xemantic-kotlin-script-python/actions/workflows/build-main.yml)
[<img alt="GitHub commits since latest release" src="https://img.shields.io/github/commits-since/morisil/xemantic-kotlin-script-python/latest">](https://github.com/morisil/xemantic-kotlin-script-python/commits/main/)
[<img alt="GitHub last commit" src="https://img.shields.io/github/last-commit/morisil/xemantic-kotlin-script-python">](https://github.com/morisil/xemantic-kotlin-script-python/commits/main/)

[<img alt="GitHub contributors" src="https://img.shields.io/github/contributors/morisil/xemantic-kotlin-script-python">](https://github.com/morisil/xemantic-kotlin-script-python/graphs/contributors)
[<img alt="GitHub commit activity" src="https://img.shields.io/github/commit-activity/t/morisil/xemantic-kotlin-script-python">](https://github.com/morisil/xemantic-kotlin-script-python/commits/main/)
[<img alt="GitHub code size in bytes" src="https://img.shields.io/github/languages/code-size/morisil/xemantic-kotlin-script-python">]()
[<img alt="GitHub Created At" src="https://img.shields.io/github/created-at/morisil/xemantic-kotlin-script-python">](https://github.com/morisil/xemantic-kotlin-script-python/commits)
[<img alt="kotlin version" src="https://img.shields.io/badge/dynamic/toml?url=https%3A%2F%2Fraw.githubusercontent.com%2Fxemantic%2Fxemantic-project-template%2Fmain%2Fgradle%2Flibs.versions.toml&query=versions.kotlin&label=kotlin">](https://kotlinlang.org/docs/releases.html)
[<img alt="discord users online" src="https://img.shields.io/discord/811561179280965673">](https://discord.gg/vQktqqN2Vn)
[![Bluesky](https://img.shields.io/badge/Bluesky-0285FF?logo=bluesky&logoColor=fff)](https://bsky.app/profile/xemantic.com)

## Why?

[xemantic-kotlin-script](https://github.com/xemantic/xemantic-kotlin-script) runs Kotlin snippets in stateful sessions,
with diagnostics an LLM can act on.
Some tasks are better served by the Python ecosystem (numpy, pandas, scikit-learn, …),
so this project embeds a CPython interpreter into a Kotlin script session,
keeping the Python state between the snippets as well.

## Requirements

CPython 3.10 or newer, the oldest version supported by the `jep` package.
Before Python 3.11, which brings `tomllib`,
reading the [inline script metadata](#usage) of the code requires either the `tomli` or the `pip` package to be importable.
An environment without `pip`, like the one of `uv venv`, can get `tomli` with `pip install tomli` or `uv pip install tomli`.

Jep is a native CPython extension:
the Java library comes from Maven Central,
but its native part comes with the `jep` Python package, which has to be installed in the Python environment in use.
The version of the `jep` package must match the version of the `org.ninia:jep` Java library, 4.3.2 in these examples:

```shell
python -m pip install jep==4.3.2
```

A virtual environment without `pip`, like the one of `uv venv`, can get it with `uv pip install --python <python> jep==4.3.2`.

An externally managed Python ([PEP 668](https://peps.python.org/pep-0668/)),
like the one of Homebrew or of most Linux distributions,
refuses `pip install`, so install it into a virtual environment instead:

```shell
python3 -m venv ~/.venvs/jep
~/.venvs/jep/bin/python -m pip install jep==4.3.2
```

and point to it with an absolute path, as `~` is expanded only by a shell, not by an IDE:
either with the `VIRTUAL_ENV` environment variable set to the virtual environment, e.g. `/home/me/.venvs/jep`,
or with `PythonEnvironment.pythonExecutable` set to its Python, e.g. `/home/me/.venvs/jep/bin/python`,
which is `Scripts\python.exe` of the virtual environment on Windows.

PyPI has no wheels of `jep`, so pip always builds it from its source,
which requires a C compiler, the Python headers,
and a JDK, found through `JAVA_HOME`, which is optional on Linux and macOS, where `javac` on the `PATH` is found as well.
On Debian/Ubuntu, the Python headers, and the `venv` module, come with `apt install python3-dev python3-venv`.

The Python executable is, in this order:
1. `PythonEnvironment.pythonExecutable`, if set before the first use of `Python`,
2. the one of the virtual environment of the `VIRTUAL_ENV` environment variable,
3. `python3` on the `PATH`.

The Python code imports the modules from the same paths as this executable,
e.g. the `site-packages` of a virtual environment, but not the ones of its base Python.

The embedded Python, like every Python process started by this library, e.g. `pip`,
runs in the [UTF-8 mode](https://peps.python.org/pep-0540/),
whatever the locale, e.g. cp1252 on Windows, or `C` on Linux:
the standard streams, and `open` without an `encoding`, use UTF-8.
Only a `PYTHONIOENCODING` environment variable of the JVM, if set, still takes precedence over it,
for the standard streams of the embedded Python.

On Linux, or another POSIX system, with a locale whose encoding is not UTF-8, e.g. ISO-8859-1,
the path of the Python executable, e.g. of the virtual environment, must be ASCII:
Jep passes it to the embedded Python as JNI modified UTF-8, which is then another path,
so that the embedded Python would not find the virtual environment,
and fail with `ModuleNotFoundError: No module named 'jep'`.
It does not apply to macOS and Windows, where Python decodes a path as UTF-8, whatever the locale.
Other paths, like the one of the dependency directory in a home directory like `/home/josé`, can be non-ASCII.

## Usage

In `build.gradle.kts` add:

```kotlin
dependencies {
    implementation("com.xemantic.kotlin:xemantic-kotlin-script-python:0.1.0")
    // only for running Python in a ScriptSession, like below, as Python does not depend on it
    implementation("com.xemantic.kotlin:xemantic-kotlin-script:0.2.1")
}
```

Then:

```kotlin
import com.xemantic.kotlin.script.ScriptResult
import com.xemantic.kotlin.script.ScriptSession
import com.xemantic.kotlin.script.python.Python

suspend fun main() {
    Python().use { python ->
        ScriptSession {
            "python" provides python
        }.use { session ->
            session.execute("""python.exec("import numpy as np; a = np.arange(10)")""")
            session.execute("""python["factor"] = 3""")
            val result = session.execute("""python.eval<List<Long>>("(a * factor).tolist()")""")
            println((result as ScriptResult.Value).value) // [0, 3, 6, 9, 12, 15, 18, 21, 24, 27]
        }
    }
}
```

`ScriptSession.execute` is a suspending function,
called here from a `suspend fun main()`,
and otherwise from any coroutine, e.g. within `runBlocking`.

The examples assume `numpy` installed in the Python environment,
otherwise the code can declare it as a dependency, see below.

* A `Python` is stateful:
  the global variables, functions, classes and imports of the executed code stay visible to the code executed after it,
  also across the snippets of a `ScriptSession`.
* `exec(code)` executes Python code, `eval<T>(expression)` evaluates an expression converted to `T`,
  and `python["name"]` reads or writes a global variable.
  The values are converted by Jep, e.g. a Python `int` becomes a `Long`, a `str` a `String`, and a `list` a `List`.
* `python(code)` executes Python code like a cell of a Jupyter notebook,
  returning the value of its last statement, if it is an expression, or `null` otherwise,
  so that the code written by an LLM can either provide a result, or be executed only for its effects:
  ```kotlin
  python("import numpy as np; a = np.arange(10)") // null
  python("a.sum()")                                 // 45L
  ```
  The value is converted by Jep to its default Java type.
* A value returned by `python(code)`, `eval` or `python["name"]`, which Jep cannot convert,
  like a module, a function or an instance of a Python class,
  is returned as its Python `repr`, also when nested in a `List`, a `Set`, a `Map` or an array,
  as a Python object can be used only on the thread of its `Python`:
  ```kotlin
  python("[len, 1]") // listOf("<built-in function len>", 1L)
  ```
  If different keys of a `dict`, or elements of a `Set`, have the same `repr`,
  an `IllegalStateException` is thrown, not to drop an entry silently,
  and `eval<PyObject>` (or an array of them) fails with an `IllegalArgumentException`.
* For an AI agent, the `Python` can be started lazily, only when a script uses it,
  as the first one initializes the Python environment:
  ```kotlin
  class Agency : AutoCloseable {
      private val lazyPython = lazy { Python() }
      val python: Python by lazyPython
      override fun close() {
          if (lazyPython.isInitialized()) python.close()
      }
  }
  ```
  With `"agency" provides agency`, a script can then call `agency.python("...")`, or set `agency.python["name"] = value`.
* Each `Python` has its own global variables,
  while the imported modules are shared by all of them,
  so that native extension modules, like `numpy`, can be used.
* A Jep interpreter can be used only by the thread which created it,
  so each `Python` runs its code on a thread of its own, and can be called from any thread.
* A `ScriptSession` does not close its dependencies, close the `Python` when no longer needed.
* The code passed to `exec`, or `python(code)`, can declare its dependencies,
  like a `@file:DependsOn` of a Kotlin snippet,
  with [inline script metadata](https://packaging.python.org/en/latest/specifications/inline-script-metadata/),
  as with `uv run`:
  ```python
  # /// script
  # dependencies = ["rdkit"]
  # ///
  from rdkit import Chem
  ```
  The missing dependencies are installed before executing the code,
  with [uv](https://docs.astral.sh/uv/) if it is on the `PATH`, or with `pip` of the Python executable otherwise,
  into `PythonEnvironment.dependencyDirectory`,
  by default the directory of the `xemantic.kotlin.script.python.dependencyDirectory` system property, if set,
  or otherwise `~/.cache/xemantic-kotlin-script-python/<python>-<hash of the environment>`,
  which becomes importable once any code declares its dependencies.
  The installer runs in the root directory of the file system,
  so that uv does not read a `uv.toml`, or a `pyproject.toml` with a `[tool.uv]` section,
  of the working directory of the JVM, or of its parents, e.g. one written by an agent, which could change the package index —
  only the user and system configuration of uv apply, and its environment variables, like `UV_CONFIG_FILE` or `UV_DEFAULT_INDEX`.
  The installed dependencies are shared by all the `Python` interpreters of the JVM,
  and they cannot change the versions of the distributions of the Python environment itself,
  nor the ones already imported from the dependency directory, as a module cannot be replaced once imported.
  The installations into the dependency directory are serialized, also across processes,
  e.g. JVMs sharing the default directory in the user cache,
  with a lock of its `.xemantic-kotlin-script-python.lock` file,
  so a call with missing dependencies can wait for an installation of another process,
  and the directory must be writable when something has to be installed.
  A dependency which cannot be installed fails the call with `PythonDependencyException`, with the output of the installer.
* A Python exception fails the call with `jep.JepException`,
  reported by the `ScriptSession` as an `ExecutionPhase.EVALUATION` error, like:
  ```
  jep.JepException: <class 'ValueError'>: boom
    at <string>.<module>(<string>:1)
  ```

## Development

The tests run in the `.venv` Python virtual environment of the project,
created by Gradle with `python3`, or another base Python set with the `basePython` Gradle property
(see [DEVELOPMENT.md](DEVELOPMENT.md#the-python-of-the-tests)),
with only the `jep` package of the version of the library installed,
like a clean environment of an agent.
The tests declare the Python packages they use, like `numpy`, as dependencies,
installed from PyPI into `build/python-dependencies`, which Gradle cleans before every test run,
so the tests need network access.

See [DEVELOPMENT.md](DEVELOPMENT.md) for the documentation conventions,
and for maintenance notes on updating the gradle wrapper and the project dependencies.
