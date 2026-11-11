# Development

Notes for working on this project itself:
the conventions its documentation follows,
and the tooling and dependency updates worth doing from time to time.

## The Python of the tests

The build creates the virtual environment of the tests in `.venv` with `python3` on the `PATH` of the Gradle daemon,
which can differ from the one of your shell,
e.g. resolve to the old Python of Xcode on macOS.
To choose another base Python, set the `basePython` Gradle property,
either on the command line, in `~/.gradle/gradle.properties`,
or with the `ORG_GRADLE_PROJECT_basePython` environment variable, e.g. on CI:

```shell
./gradlew build -PbasePython=/opt/homebrew/bin/python3.13
```

It has to be Python 3.10 or newer, otherwise the build fails before creating the environment.
On Linux, a Python outside the system library directories, e.g. installed by `uv`,
does not need its `lib` directory in `LD_LIBRARY_PATH`,
as `PythonEnvironment` loads its `libpython` before `libjep.so` ([#54](https://github.com/xemantic/xemantic-kotlin-script-python/issues/54)).
`actions/setup-python` sets `LD_LIBRARY_PATH` on CI anyway,
so `PythonLibraryTest` removes it in the JVM it starts, to test the loading with such a base Python.
`pyenv` builds Python without a shared `libpython` by default,
build it with `PYTHON_CONFIGURE_OPTS="--enable-shared"`.

An existing `.venv` is judged by its own Python:
it is kept when this Python runs, is 3.10 or newer, and has `pip`,
otherwise it is created again.
Without `basePython` set, the `python3` on the `PATH` is not even checked then,
so that an IDE with another `python3` keeps the environment created in a shell.
With `basePython` set, `.venv` is also created again when it is of another base Python,
compared by the prefix and version of the interpreter, not by the path given.
The previous `.venv` is moved aside to `.venv.previous` meanwhile,
and restored when creating the new one fails or is interrupted.
An empty `basePython` counts as not set.
The base Python of `.venv` is recorded in `.venv/base-python.txt`,
an input of the tests, so that they run again after it changes.

Besides the build with the `python3` of the runner,
CI runs the tests with the minimum supported Python, and the newest one verified with the pinned jep,
set up by `actions/setup-python` and passed as `ORG_GRADLE_PROJECT_basePython`,
see [build-python.yml](.github/workflows/build-python.yml).
Update its matrix when the minimum is raised,
and bump the newest one after the tests pass with it locally (`-PbasePython`), usually together with a jep update.

## Update gradlew wrapper

```shell
./gradlew wrapper --gradle-version latest --distribution-type bin
```

## Update all the dependencies to the latest versions

All the gradle dependencies are managed by the
[libs.versions.toml](gradle/libs.versions.toml) file in the `gradle` dir.

To resolve the latest versions,
and apply them automatically to [libs.versions.toml](gradle/libs.versions.toml),
run the [version-catalog-update](https://github.com/littlerobots/version-catalog-update-plugin) plugin:

```shell
./gradlew versionCatalogUpdate
```

To review and pick the updates one by one instead of applying them all,
use the interactive mode:

```shell
./gradlew versionCatalogUpdate --interactive
```

then apply the staged changes with:

```shell
./gradlew versionCatalogApplyUpdates
```

> [!NOTE]
> The plugin is configured in [build.gradle.kts](build.gradle.kts)
> to preserve the manual ordering of `libs.versions.toml` (`sortByKey = false`),
> and to keep the `kotlinTarget`, `javaTarget`, and `asm` version constants,
> which have no `version.ref` and would otherwise be removed as unused.

## Documentation conventions

All the Markdown files in this project are authored with
[semantic line breaks](https://sembr.org/).
Each sentence starts on its own line,
and long sentences may be split further at clause boundaries.
This keeps `git diff` and code review focused on the sentence that actually changed,
instead of on a whole reflowed paragraph.

There is no maximum line length,
and paragraphs are never hard-wrapped to a fixed column.
Line length is a rendering concern,
so it is left to the editor.

### Markdown soft wrapping in the IDE

**IntelliJ IDEA**:
`Settings` → `Editor` → `General` → `Soft Wraps`,
enable `Soft-wrap these files` and make sure the mask contains `*.md`
(the default mask already does).
To toggle it for the file at hand only,
use `View` → `Active Editor` → `Soft-Wrap`.

**VS Code**:
add the following to your `settings.json`:

```json
{
  "[markdown]": {
    "editor.wordWrap": "on"
  }
}
```

Alternatively toggle it for the current file with `Alt`+`Z` (`Option`+`Z` on macOS).