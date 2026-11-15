#!/usr/bin/env python3
"""Build the CodeForge AI release artifacts.

Creates six archives in release/ plus SHA-256 checksums and a JSON manifest:
a full source ZIP, a documentation ZIP, a Windows x64 ZIP with prebuilt
binaries, and Linux, macOS and mobile source bundles. The POSIX bundles use
tar.gz so executable bits (build.sh) survive the transfer. Each platform
bundle ships a QUICKSTART.txt tailored to that platform.

Usage:
    python aux-python/package_release.py
    python aux-python/package_release.py --skip-windows
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import stat
import sys
import tarfile
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "release"
VERSION = "1.0.0"
STAMP = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

EXCLUDE_DIRS = {
    ".git", ".idea", ".vscode", "__pycache__",
    "models", "runtime", "workspace", "logs", "release", "memory",
    "toolchains", "jdk", "build", "classes", ".gradle", "DerivedData",
    "node_modules",
}
EXCLUDE_SUFFIXES = {".part", ".pyc", ".tmp", ".old"}
EXCLUDE_NAMES = {"installed.json", "runtime.json", ".env", "credentials.json", "secrets.json"}
EXECUTABLE_FILES = {"build.sh", "mobile/android/gradlew"}
# Wrapper jar ships with every source bundle so ./gradlew works without Gradle installed.
REQUIRED_JARS = {"mobile/android/gradle/wrapper/gradle-wrapper.jar"}

REQUIRED = [
    "README.md",
    "LICENSE",
    "CHANGELOG.md",
    "config.json",
    "docs/MANUAL.md",
    "docs/PLATFORMS.md",
    "docs/ARCHITECTURE.md",
    "docs/API.md",
    "docs/SECURITY.md",
    "docs/MODELS.md",
    "docs/images/console-chat.png",
    "QUICKSTART-LINUX.txt",
    "QUICKSTART-MACOS.txt",
    "QUICKSTART-MOBILE.txt",
    "mobile/android/gradlew",
    "mobile/android/gradle/wrapper/gradle-wrapper.jar",
]

WINDOWS_BINARIES = [
    "native/bin/CodeForge.exe",
    "native/bin/CodeForgeCore.exe",
    "native/bin/cforge_native.exe",
    "server-java/build/CodeForgeServer.jar",
]

PLATFORM_QUICKSTARTS = {
    "linux": ("QUICKSTART-LINUX.txt", "QUICKSTART.txt"),
    "macos": ("QUICKSTART-MACOS.txt", "QUICKSTART.txt"),
    "mobile": ("QUICKSTART-MOBILE.txt", "QUICKSTART.txt"),
}
ROOT_QUICKSTARTS = {source for source, _ in PLATFORM_QUICKSTARTS.values()}
PLATFORM_LABELS = {
    "source": "all platforms (source)",
    "docs": "documentation",
    "windows": "Windows 10/11 x64 (prebuilt)",
    "linux": "Linux x64, ARM64, s390x, 32-bit (source)",
    "macos": "macOS arm64 + x64 (source)",
    "mobile": "Android Termux + iOS iSH (source)",
}

QUICKSTART_WINDOWS = """CodeForge AI {version} — Windows quick start
=====================================================

1. Install Java 17 or newer (winget install EclipseAdoptium.Temurin.17.JDK),
   or run this once from the project root to provision a portable JDK,
   rebuild everything and run the self-test:

       python aux-python\\bootstrap.py --all

2. Launch the control center:

       native\\bin\\CodeForge.exe

   It starts the local server on http://127.0.0.1:8765 and opens the console.
   Closing the window keeps CodeForge in the tray; use the tray menu to exit.

   Windows 11 ARM64 runs this x64 bundle under the built-in x64 emulation.
   A native ARM64 JDK and build makes the runtime installer prefer
   win-cpu-arm64 instead of win-cpu-x64.

3. In the Models tab:
   - click "Install runtime" to fetch the llama.cpp CPU runtime;
   - download a model that fits your RAM (Qwen3 4B needs about 6 GB);
   - click "Start inference";
   - chat, or paste failing code in Debug and press "Auto-debug".

Everything runs on this machine. Models and the runtime are downloaded on
demand and are not part of this archive.

Documentation: docs/MANUAL.md, docs/PLATFORMS.md, docs/API.md
License: MIT (see LICENSE). Model weights keep their own licenses.
"""


def log(stage: str, message: str) -> None:
    print(f"[{stage}] {message}", flush=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def excluded(relative: Path, mode: str) -> bool:
    if mode == "windows" and relative.as_posix() in WINDOWS_BINARIES:
        return False
    parts = relative.parts
    for part in parts[:-1]:
        if part in EXCLUDE_DIRS:
            return True
    name = relative.name
    if name in EXCLUDE_NAMES:
        return True
    if relative.as_posix() in ROOT_QUICKSTARTS and mode in PLATFORM_QUICKSTARTS:
        return True
    if name.startswith("_") and relative.suffix.lower() == ".png":
        return True
    if relative.suffix.lower() in EXCLUDE_SUFFIXES:
        return True
    if mode != "windows" and relative.suffix.lower() in {".exe", ".jar", ".dll", ".lib", ".o", ".a"}:
        if relative.as_posix() not in REQUIRED_JARS:
            return True
    if mode == "docs" and parts[0] not in {"docs"} and name not in {
            "README.md", "LICENSE", "CHANGELOG.md"} and name not in ROOT_QUICKSTARTS:
        return True
    return False


def collect(mode: str) -> list[Path]:
    files: list[Path] = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT)
        if excluded(relative, mode):
            continue
        files.append(path)
    return sorted(files, key=lambda item: rel(item).lower())


def quickstart_payload(mode: str) -> bytes:
    if mode == "windows":
        return QUICKSTART_WINDOWS.format(version=VERSION).encode("utf-8")
    source, _ = PLATFORM_QUICKSTARTS[mode]
    return (ROOT / source).read_text(encoding="utf-8").encode("utf-8")


def quickstart_name(mode: str) -> str:
    if mode == "windows":
        return "QUICKSTART-WINDOWS.txt"
    return PLATFORM_QUICKSTARTS[mode][1]


def add_quickstart(zf: zipfile.ZipFile, mode: str) -> None:
    data = quickstart_payload(mode)
    info = zipfile.ZipInfo("CodeForge-AI-" + VERSION + "/" + quickstart_name(mode))
    info.date_time = time.localtime()[:6]
    info.compress_type = zipfile.ZIP_DEFLATED
    zf.writestr(info, data)


def add_quickstart_tar(tf: tarfile.TarFile, mode: str) -> None:
    data = quickstart_payload(mode)
    info = tarfile.TarInfo("CodeForge-AI-" + VERSION + "/" + quickstart_name(mode))
    info.size = len(data)
    info.mode = 0o644
    info.mtime = time.time()
    info.type = tarfile.REGTYPE
    tf.addfile(info, io.BytesIO(data))


def zip_write(zf: zipfile.ZipFile, path: Path, arcname: str) -> None:
    info = zipfile.ZipInfo.from_file(path, arcname)
    info.create_system = 3
    mode = 0o755 if rel(path) in EXECUTABLE_FILES else 0o644
    info.external_attr = (stat.S_IFREG | mode) << 16
    info.compress_type = zipfile.ZIP_DEFLATED
    zf.writestr(info, path.read_bytes())


def tar_mode(info: tarfile.TarInfo) -> tarfile.TarInfo:
    prefix = "CodeForge-AI-" + VERSION + "/"
    member = info.name[len(prefix):] if info.name.startswith(prefix) else info.name
    if member in EXECUTABLE_FILES:
        info.mode = 0o755
    elif info.isreg():
        info.mode = 0o644
    return info


def build_zip(mode: str, target: Path) -> dict:
    files = collect(mode)
    OUT.mkdir(parents=True, exist_ok=True)
    if target.exists():
        target.unlink()
    total_bytes = 0
    log("package", f"{target.name}: {len(files)} files")
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED,
                         compresslevel=9, allowZip64=True) as zf:
        for index, path in enumerate(files, start=1):
            arcname = "CodeForge-AI-" + VERSION + "/" + rel(path)
            zip_write(zf, path, arcname)
            total_bytes += path.stat().st_size
            if index % 40 == 0 or index == len(files):
                log("package", f"  {index}/{len(files)} entries, "
                               f"{total_bytes / 1048576:.1f} MB uncompressed")
        if mode == "windows" or mode in PLATFORM_QUICKSTARTS:
            add_quickstart(zf, mode)
    entry_count = 0
    with zipfile.ZipFile(target) as zf:
        bad = zf.testzip()
        if bad is not None:
            raise RuntimeError(f"corrupt archive entry: {bad}")
        entry_count = len(zf.namelist())
    digest = sha256_file(target)
    size = target.stat().st_size
    log("package", f"{target.name}: {entry_count} entries, {size / 1048576:.1f} MB, "
                   f"sha256={digest}")
    return {
        "file": target.name,
        "mode": mode,
        "platform": PLATFORM_LABELS[mode],
        "format": "zip",
        "entries": entry_count,
        "archive_bytes": size,
        "source_bytes": total_bytes,
        "sha256": digest,
    }


def build_tar(mode: str, target: Path) -> dict:
    files = collect(mode)
    OUT.mkdir(parents=True, exist_ok=True)
    if target.exists():
        target.unlink()
    total_bytes = 0
    log("package", f"{target.name}: {len(files)} files")
    with tarfile.open(target, "w:gz", compresslevel=9) as tf:
        for index, path in enumerate(files, start=1):
            arcname = "CodeForge-AI-" + VERSION + "/" + rel(path)
            tf.add(path, arcname=arcname, recursive=False, filter=tar_mode)
            total_bytes += path.stat().st_size
            if index % 40 == 0 or index == len(files):
                log("package", f"  {index}/{len(files)} entries, "
                               f"{total_bytes / 1048576:.1f} MB uncompressed")
        add_quickstart_tar(tf, mode)
    with tarfile.open(target, "r:gz") as tf:
        entry_count = len(tf.getmembers())
    if entry_count == 0:
        raise RuntimeError(f"empty archive: {target.name}")
    digest = sha256_file(target)
    size = target.stat().st_size
    log("package", f"{target.name}: {entry_count} entries, {size / 1048576:.1f} MB, "
                   f"sha256={digest}")
    return {
        "file": target.name,
        "mode": mode,
        "platform": PLATFORM_LABELS[mode],
        "format": "tar.gz",
        "entries": entry_count,
        "archive_bytes": size,
        "source_bytes": total_bytes,
        "sha256": digest,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Package CodeForge AI releases")
    parser.add_argument("--skip-windows", action="store_true",
                        help="do not require or bundle Windows binaries")
    args = parser.parse_args()

    missing = [name for name in REQUIRED if not (ROOT / name).is_file()]
    if missing:
        for name in missing:
            log("error", f"missing required file: {name}")
        return 1
    have_windows = all((ROOT / name).is_file() for name in WINDOWS_BINARIES)
    if not have_windows and not args.skip_windows:
        log("error", "Windows binaries are missing; run bootstrap.py --all first "
                     "or pass --skip-windows")
        for name in WINDOWS_BINARIES:
            if not (ROOT / name).is_file():
                log("error", f"  absent: {name}")
        return 1

    started = time.time()
    artifacts = [build_zip("source", OUT / f"CodeForge-AI-{VERSION}-source.zip")]
    if have_windows:
        artifacts.append(build_zip(
            "windows", OUT / f"CodeForge-AI-{VERSION}-windows-x64.zip"))
    artifacts.extend([
        build_tar("linux", OUT / f"CodeForge-AI-{VERSION}-linux-source.tar.gz"),
        build_tar("macos", OUT / f"CodeForge-AI-{VERSION}-macos-source.tar.gz"),
        build_zip("mobile", OUT / f"CodeForge-AI-{VERSION}-mobile-source.zip"),
        build_zip("docs", OUT / f"CodeForge-AI-{VERSION}-docs.zip"),
    ])

    checksums = OUT / "SHA256SUMS.txt"
    with open(checksums, "w", encoding="utf-8", newline="\n") as handle:
        for item in artifacts:
            handle.write(f"{item['sha256']}  {item['file']}\n")
    log("package", f"checksums written: {checksums.name}")

    manifest = {
        "name": "CodeForge AI",
        "version": VERSION,
        "created_at": STAMP,
        "license": "MIT (original source); model weights keep their own licenses",
        "artifacts": artifacts,
        "notes": [
            "Model weights and the llama.cpp runtime are not bundled.",
            "Windows ships prebuilt x64 binaries and the server jar.",
            "Linux, macOS and mobile bundles are source releases: bootstrap.py or build.sh produces local binaries.",
            "Linux and macOS bundles use tar.gz so executable bits survive; each carries a platform QUICKSTART.txt.",
            "The mobile bundle covers Android via Termux, iOS via iSH, and the Kotlin/Swift thin clients.",
            "The source bundle builds on Windows, Linux, macOS, Termux and iSH.",
            "Private user state (memory/, workspace/, logs/) and all API keys are excluded from every artifact.",
            "Verify downloads with: sha256sum -c SHA256SUMS.txt",
        ],
    }
    manifest_path = OUT / f"manifest-{VERSION}.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    log("package", f"manifest written: {manifest_path.name}")
    log("done", f"release artifacts ready in {OUT} in {time.time() - started:.1f}s")
    for item in artifacts:
        log("done", f"  {item['file']}  {item['archive_bytes'] / 1048576:.1f} MB  "
                    f"{item['sha256'][:16]}…")
    return 0


if __name__ == "__main__":
    sys.exit(main())
