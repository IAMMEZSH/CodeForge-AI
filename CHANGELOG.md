# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.0.6 — 2026-11-19

### Added

- **One-shot build and desktop delivery in every supported language.** A new
  `codeforge_build` agent tool (also exposed as `POST /api/agent/build`) takes
  the source the model just wrote and produces a double-clickable result on the
  Desktop: a native `.exe` for C, C++, Go, Rust and C#; a runnable `.jar` plus
  launcher for Java; interpreter-pinned launchers for Python (`.py`/`.pyw`),
  JavaScript, TypeScript and PHP; and an `index.html` page with a shortcut for
  HTML/CSS. Every delivery gets its own Desktop folder and a real `.lnk`
  (`.desktop` / `.command` elsewhere) shortcut, Unicode names and paths work,
  and compiler errors are returned to the model so it fixes the source and
  retries instead of handing back an unbuilt file. The tool is deterministic —
  the model never needs to know compiler flags, manifest syntax or working
  directories.
- README, manual, API reference, platform guide, security notes, quick starts,
  diagrams and console screenshots were refreshed for 1.0.6.

### Fixed

- **Malformed model tool calls are executed instead of echoed.** Some models
  emit the XML/DSML tool envelope with a bare JSON body, an unwrapped
  `<parameter>` tag or missing closing tags. All four shapes are now parsed —
  tagged `<codeforge_build>`, standard DSML, JSON inside `<invoke>` and a JSON
  object that follows the tool name — so a build request is never dumped back
  into the chat as raw text.
- **Python deliveries stay clean.** The syntax-check step no longer leaves a
  `__pycache__` directory next to the shipped program.
- **System C/C++, Java and other runtimes are detected on Windows.** `PATH` and
  other environment variables are resolved case-insensitively, `JAVA_HOME` is
  honoured as a javac fallback, and the Environment panel lists system
  toolchains even when no managed runtime is installed.
- **Every saved API key is blanked in shipped config files,** including the
  `llm.keys` list, in every archive.

### Changed

- The server jar, native binaries, web console, mobile projects and release
  manifest all report 1.0.6.