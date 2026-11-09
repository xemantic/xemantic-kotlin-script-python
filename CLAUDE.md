# CLAUDE.md

This file captures only what cannot be inferred from the codebase itself.

## Rules for editing this file

Both developers and AI agents are expected to add entries as they encounter surprises.

- **Add an entry** when you encounter something unexpected: a build quirk, a non-obvious constraint, a dependency gotcha, or any behavior that would surprise the next agent or developer.
- **Add an entry** when a developer flags an anti-pattern produced by AI — describe the anti-pattern and the preferred alternative.
- **Do not** add codebase overviews, directory listings, or anything discoverable by reading the source.
- Keep entries concise: the lesson and its non-obvious *why*, grouped under a heading if a theme emerges.
  Name a symbol or test as a pointer, but don't restate what its source comment already says.

## Conventions

### Markdown authoring

Markdown files use [semantic line breaks](https://sembr.org/):
break a line after a sentence,
and optionally at clause boundaries within a long sentence,
so that diffs stay meaningful and reviewable.

There is no column width limit —
never reflow or hard-wrap a paragraph to fit some character count.
Modern editors soft-wrap Markdown visually,
see [DEVELOPMENT.md](DEVELOPMENT.md#markdown-soft-wrapping-in-the-ide) for how to enable it.

### Tests

- **Write the failing test first.**
  For any newly found bug, edge case or use case, add a test that reproduces it *before* touching the implementation, watch it fail, then make it pass.
  Add it at the narrowest level that exercises the behavior — an end-to-end test can pass while the bug hides in an untested combination.
  Scratch tests used to probe behavior are fine, but replace them with a named regression test before finishing.
- Tests keep the `// given`, `// when`, `// then` comment structure — AI agents tend to omit it.
  In a functional-style test where the setup is inlined into the action, `// given` may be omitted, but `// when` and `// then` stay.
- Assert a boolean expression with `assert(expr)` from `com.xemantic.kotlin.test` — it is power-assert-instrumented in `build.gradle.kts`, so a failure prints every subvalue of the expression.
  Do not fall back to `assertEquals` / `assertTrue` / `assertNotNull` from `kotlin.test`, which discard that information.
- Never assert on wall-clock time (a ratio or a bound): tests run under whatever CI load there is, and fails with no code change.
  To guard against, say, quadratic backtracking, feed an input large enough that the regression hangs for minutes while the correct code takes milliseconds.

### Working with AI agents

- In Claude Code "auto mode", never commit on your own — leave the changes in the working tree so the developer can review the diff first, and commit only when explicitly asked.
- The copyright year range (e.g. 2025-2026) is applied on autosave — a new file uses only the current year.

## Known gotchas

- After upgrading the Gradle wrapper, `test` may fail with `NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`, because the results of the previous Gradle version are stale — delete `build/test-results` (or run `clean`) and retry.
- After a fresh Gradle daemon starts, the first build reports `configuration cache cannot be reused because file '.../caches/jreleaser/jreleaser/<version>/marker.txt' has changed` —
  the JReleaser plugin bumps a banner counter in that file behind Gradle's back on every configuration,
  so the cache is invalidated once per daemon lifetime and reused normally afterwards; it is harmless.
- The Gradle configuration cache is enabled (`gradle.properties`), so a task action (`doLast { … }`) must not call a function declared in the build script — the script object is not serializable.
  Inline the logic or move the helper into `buildSrc` / an included build, and register tasks with `tasks.register(...)` rather than the `by tasks.registering` delegate.
  This includes a lambda like `onlyIf { }` capturing a top-level `val` of the build script — copy it into a local `val` within the task configuration first.
- `xemantic-kotlin-script` is resolved from `mavenLocal()` as a SNAPSHOT until it is released —
  after changing it, run `./gradlew publishToMavenLocal` in that project, or this one keeps building against the stale copy.
- With an `internal` primary constructor, the Kotlin ABI dump (`checkKotlinAbi`) omits a `public` secondary constructor as well, so `checkKotlinAbi` fails on a public API which did not change —
  make the primary constructor `private`, and expose a test seam through an `internal` factory instead (see `Python.withDecoratedExecutor`).
- Removing a package from `installPythonPackages` does not uninstall it from `.venv` — pip only ever adds —
  so a test can keep passing on a package it no longer declares; delete `.venv` and let Gradle recreate it.

### Jep

- `import jep` fails in a standalone Python with `Jep is not supported in standalone Python` — it is not a broken install;
  to check one, look for the `libjep.*` file next to the package, found with `importlib.util.find_spec('jep')`, which does not execute it.
- `MainInterpreter.setJepLibraryPath` and `MainInterpreter.setInitParams` can be called only once per JVM, before the first interpreter,
  so the environment is global (`PythonEnvironment`) — a test cannot switch to another Python within the same test JVM.
- A Jep interpreter, and a `PyObject` it returns, are bound to the thread which created the interpreter, also for `close()` —
  never return a `PyObject` out of `Python`, convert to Java types on its thread.
- `org.ninia:jep` was `black.ninia:jep` before 4.3, which now only relocates to it.
- The embedded CPython does not detect a virtual environment by itself —
  without the venv's `python` as its program name (`PyConfig.setProgramName`), it ignores `pyvenv.cfg` and also imports from the global and user `site-packages` of the base Python.
  When a module is unexpectedly importable, check `sys.path` of the embedded interpreter, not of `.venv/bin/python`.
- Jep passes the `PyConfig` strings to CPython as JNI *modified* UTF-8 (`GetStringUTFChars`), not plain UTF-8 —
  a character outside the BMP becomes a CESU-8 surrogate pair, which CPython does not decode as that character.
  For a path under a non-UTF-8 locale, see `pythonPath`.
- A machine may have only the `C`, `C.utf8` and `POSIX` locales,
  but `localedef -i en_US -f ISO-8859-1 <dir>/en_US.ISO-8859-1` with `LOCPATH=<dir> LC_ALL=en_US.ISO-8859-1` gives a JVM an ISO-8859-1 `sun.jnu.encoding` without root (see `PythonLocaleTest`).

## Anti-patterns to avoid

- Do not add content to this file that is already discoverable by reading the source or build scripts — that inflates context without adding signal, reducing AI agent task success rates (see [arxiv 2602.11988](https://arxiv.org/abs/2602.11988)).
