# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.2.2 — 2026-11-22

### Added

- **Command-line downloader.** `CodeForgeCore download` fetches a direct URL or
  searches and ranks candidates through a running local server. Direct
  transfers use aria2c with 16 connections, `.part` resume and SHA-256
  verification, then fall back to curl (`-C -`, five retries). Every result is
  a single JSON object with path, byte count, hash, engine and error state.
- **Bundle and install packages from the terminal.** `CodeForgeCore install`
  detects winget, Chocolatey, Homebrew, apt-get, dnf, zypper, pacman or apk,
  runs non-interactively, and supports `--scope machine|user`, `--manager`,
  `--id` and `--interactive`.
- **PATH registration and wrapper.** `CodeForgeCore path show|add|remove`
  writes the machine PATH (HKLM, UAC self-elevation) or user PATH (HKCU),
  creates the `cforge` wrapper, verifies the change and reports the outcome.
  Linux and macOS update `/etc/profile.d/codeforge.sh` or `~/.profile`.
- **Bundled aria2c.** The Windows package ships aria2c 1.37.0 in
  `native/bin/`, so segmented resume works without a separate install.
- **New help surface.** `CodeForgeCore help` prints every command and its
  common flags, and the manual documents all download, install and PATH
  options with examples.

### Changed

- Windows builds are fully static (`-static -static-libgcc -static-libstdc++`)
  for both the core and the control center, removing the MinGW runtime DLL
  dependency.
- `bootstrap.py` now passes `-Icore -Inative-c` when compiling the core, and
  the Windows, Linux and macOS manual build commands match the CMake source
  list, including `core/cli_tools.cpp`, `native-c/sha256.c` and the Windows
  `advapi32`/`user32` libraries.
- Release packaging emits six platform archives plus `SHA256SUMS.txt` and
  `manifest-1.2.2.json`; every archive is reopened and CRC-tested, and the
  Windows archive includes the CLI binaries and aria2c.
- README, user manual, platform guide, architecture notes, screenshots and
  the demo document describe the current release only.

### Notes

- Local downloads and installs need no cloud account. Machine-scope PATH and
  package installs require the operating system's elevation prompt.
- The console Downloads view, the `codeforge_download` agent tool and the new
  command-line downloader share the same server job service for search and
  ranking.