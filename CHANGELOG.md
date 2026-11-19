# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.0.5 — 2026-11-19

### Fixed

- **System C/C++, Java and other runtimes are detected again on Windows.** The
  server looked up `PATH` through a case-sensitive environment-map read, but
  Windows stores the variable as `Path`, so the toolchain scan saw an empty
  search path and the Environment panel reported gcc, g++, javac and java as
  missing even when MinGW-W64 and the Microsoft JDK were installed and on PATH.
  `ToolchainManager` and `ToolProvisioner` now resolve `PATH` and other
  environment variables case-insensitively, and `JAVA_HOME` is honoured as a
  javac fallback. On the test host the Environment panel now lists g++ 16.2.0,
  javac 17.0.21, Node.js, Go, Rust, PHP and .NET as READY, and `/api/run`
  compiles and executes C++ and Java through the system toolchains.
- **Every saved API key is blanked in shipped config files.** The packager
  cleared the two legacy key fields but left entries in `llm.keys` untouched;
  all key fields, including the saved key list, are now blanked in every
  archive.

### Changed

- README, manual, API reference, platform guide, security notes, quick starts,
  diagrams and console screenshots were refreshed for 1.0.5; the server jar,
  native binaries, web console, mobile projects and release manifest all report
  1.0.5.
