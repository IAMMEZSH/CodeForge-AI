#!/usr/bin/env python3
"""CodeForge AI auxiliary bootstrap.

Downloads a portable Temurin JDK, builds the C/C++ main core and packages
the Java server. Python is the build/automation layer only; inference and
debugging run in the native core.

Usage:
    python aux-python/bootstrap.py --all
    python aux-python/bootstrap.py --jdk
    python aux-python/bootstrap.py --build
    python aux-python/bootstrap.py --run --port 8765
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NATIVE_BIN = ROOT / "native" / "bin"
JDK_ROOT = ROOT / "native" / "jdk"
BUILD = ROOT / "server-java" / "build"
CLASSES = BUILD / "classes"
JAR = BUILD / "CodeForgeServer.jar"
VERSION = "1.0.9"

C_SOURCES = [
    ROOT / "native-c" / "native_tool.c",
    ROOT / "native-c" / "sha256.c",
]
CORE_SOURCES = [
    "core/main.cpp",
    "core/config.cpp",
    "core/util.cpp",
    "core/process.cpp",
    "core/http.cpp",
    "core/llm.cpp",
    "core/runners.cpp",
    "core/debugger.cpp",
]
JAVA_SOURCES = sorted(
    path.name for path in (ROOT / "server-java" / "src" / "codeforge" / "server").glob("*.java")
)

IS_WINDOWS = os.name == "nt"
EXE = ".exe" if IS_WINDOWS else ""


def log(stage: str, message: str) -> None:
    print(f"[{stage}] {message}", flush=True)


def run(command: list[str], cwd: Path | None = None, env: dict | None = None) -> None:
    printable = " ".join(str(part) for part in command)
    log("run", printable)
    subprocess.run(command, cwd=str(cwd or ROOT), env=env, check=True)


def find_tool(*names: str) -> str | None:
    for name in names:
        path = shutil.which(name)
        if path:
            return path
    return None


def http_json(url: str) -> dict:
    request = urllib.request.Request(url, headers={
        "User-Agent": "CodeForge-AI-Bootstrap/1.0",
        "Accept": "application/vnd.github+json",
    })
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def download(url: str, target: Path, expected_sha256: str = "") -> str:
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    offset = partial.stat().st_size if partial.exists() else 0
    headers = {"User-Agent": "CodeForge-AI-Bootstrap/1.0"}
    if offset:
        headers["Range"] = f"bytes={offset}-"
    request = urllib.request.Request(url, headers=headers)
    mode = "ab" if offset else "wb"
    log("download", f"{url} -> {target.name} ({'resume at ' + str(offset) if offset else 'new'})")
    with urllib.request.urlopen(request, timeout=120) as response:
        status = getattr(response, "status", 200)
        if status == 416 and offset:
            partial.rename(target)
            return sha256_file(target)
        if status != 206 and offset:
            offset = 0
            mode = "wb"
        length = int(response.headers.get("Content-Length") or 0)
        total = offset + length
        written = offset
        started = time.time()
        last_print = 0.0
        with open(partial, mode) as output:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                output.write(chunk)
                written += len(chunk)
                now = time.time()
                if now - last_print >= 1.0 or written == total:
                    percent = (written * 100.0 / total) if total else 0.0
                    speed = (written - offset) / max(now - started, 0.001) / 1048576
                    sys.stdout.write(
                        f"\r[download] {percent:5.1f}%  {written / 1048576:7.1f} / "
                        f"{total / 1048576:7.1f} MB  {speed:5.1f} MB/s")
                    sys.stdout.flush()
                    last_print = now
        sys.stdout.write("\n")
    digest = sha256_file(partial)
    if expected_sha256 and digest.lower() != expected_sha256.lower():
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"SHA-256 mismatch for {target.name}: {digest}")
    partial.replace(target)
    log("download", f"verified {target.name} sha256={digest[:16]}…")
    return digest


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def jdk_asset_patterns() -> list[str]:
    system = platform.system().lower()
    machine = platform.machine().lower()
    if machine in ("x86_64", "amd64", "x64"):
        arch_order = ["x64"]
    elif machine in ("aarch64", "arm64"):
        arch_order = ["aarch64"]
    elif machine in ("i386", "i486", "i586", "i686", "x86"):
        arch_order = ["x86-32", "x86"]
    elif machine in ("armv7l", "armv6l", "armv8l", "arm"):
        arch_order = ["arm"]
    else:
        raise RuntimeError(f"unsupported CPU architecture for portable JDK: {system} {machine}")
    if system == "windows":
        if arch_order == ["aarch64"]:
            arch_order.append("x64")  # Windows 11 ARM64 runs the x64 JDK under emulation.
        suffix = "windows_hotspot_"
    elif system == "darwin":
        suffix = "mac_hotspot_"
    elif system == "linux":
        suffix = "linux_hotspot_"
    else:
        raise RuntimeError(f"unsupported platform for portable JDK: {system} {machine}")
    return [f"OpenJDK17U-jdk_{arch}_{suffix}" for arch in arch_order]


def is_termux() -> bool:
    return bool(os.environ.get("TERMUX_VERSION")) or \
        "com.termux" in os.environ.get("PREFIX", "")


def portable_jdk_supported() -> bool:
    if is_termux():
        return False
    if Path("/etc/alpine-release").exists():
        return False
    return platform.system().lower() in ("windows", "darwin", "linux")


def find_portable_jdk() -> Path | None:
    if not JDK_ROOT.is_dir():
        return None
    for candidate in sorted(JDK_ROOT.iterdir()):
        javac = candidate / "bin" / f"javac{EXE}"
        if javac.is_file():
            return candidate
    return None


def ensure_jdk() -> Path:
    existing = find_portable_jdk()
    if existing:
        log("jdk", f"portable JDK present: {existing}")
        return existing
    system_javac = find_tool("javac")
    if system_javac and not os.environ.get("CODEFORGE_FORCE_JDK"):
        try:
            probe = subprocess.run([system_javac, "-version"], capture_output=True,
                                   text=True, timeout=15)
            version_text = (probe.stderr or probe.stdout).strip()
            if "17" in version_text or "21" in version_text or "22" in version_text:
                log("jdk", f"using system JDK: {system_javac} ({version_text.splitlines()[0]})")
                return Path(system_javac).parent.parent
        except Exception:
            pass

    log("jdk", "no JDK 17+ found; downloading portable Temurin 17")
    if not portable_jdk_supported():
        raise RuntimeError(
            "No JDK 17+ found. Install it with your package manager, then rerun: "
            "Termux -> pkg install openjdk-17; "
            "Alpine/iSH -> apk add openjdk17-jdk")
    releases = [
        "https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest",
        "https://api.github.com/repos/adoptium/temurin17-binaries/releases/tags/jdk-17.0.21%2B9",
    ]
    release = None
    last_error: Exception | None = None
    for url in releases:
        try:
            release = http_json(url)
            break
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            log("jdk", f"release query failed: {exc}")
    if release is None:
        raise RuntimeError(f"cannot query Temurin releases: {last_error}")

    try:
        patterns = jdk_asset_patterns()
    except RuntimeError as exc:
        raise RuntimeError(
            f"{exc}. Install JDK 17 from your distribution instead, for example "
            "sudo apt install openjdk-17-jdk (or the s390x build from IBM Semeru), "
            "then rerun this command.") from exc
    archive_asset = None
    for pattern in patterns:
        for asset in release.get("assets", []):
            name = asset.get("name", "")
            if name.startswith(pattern) and (name.endswith(".zip") or name.endswith(".tar.gz")):
                archive_asset = asset
                break
        if archive_asset is not None:
            break
    if archive_asset is None:
        raise RuntimeError(f"no JDK asset matching {' or '.join(patterns)}* "
                           f"in {release.get('tag_name')}")
    checksum_asset = None
    for asset in release.get("assets", []):
        if asset.get("name") == archive_asset["name"] + ".sha256.txt":
            checksum_asset = asset
            break

    JDK_ROOT.mkdir(parents=True, exist_ok=True)
    archive = JDK_ROOT / archive_asset["name"]
    expected = ""
    if checksum_asset:
        try:
            with urllib.request.urlopen(urllib.request.Request(
                    checksum_asset["browser_download_url"],
                    headers={"User-Agent": "CodeForge-AI-Bootstrap/1.0"}), timeout=30) as response:
                text = response.read().decode("utf-8", "replace").strip()
            expected = text.split()[0]
        except Exception as exc:  # noqa: BLE001
            log("jdk", f"checksum lookup failed: {exc}")
    elif archive_asset.get("digest"):
        expected = archive_asset["digest"].replace("sha256:", "")
    download(archive_asset["browser_download_url"], archive, expected)

    staging = JDK_ROOT / "_staging"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir()
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(staging)
    else:
        with tarfile.open(archive, "r:gz") as bundle:
            bundle.extractall(staging)
    entries = [item for item in staging.iterdir() if item.is_dir()]
    if not entries:
        raise RuntimeError("JDK archive did not contain a directory")
    target = JDK_ROOT / entries[0].name
    if target.exists():
        shutil.rmtree(target)
    moved = False
    for attempt in range(6):
        try:
            entries[0].rename(target)
            moved = True
            break
        except PermissionError:
            time.sleep(1.5 + attempt)
    if not moved:
        log("jdk", "rename blocked, falling back to copy")
        shutil.copytree(entries[0], target)
        shutil.rmtree(entries[0], ignore_errors=True)
    shutil.rmtree(staging, ignore_errors=True)
    archive.unlink(missing_ok=True)
    log("jdk", f"installed portable JDK at {target}")
    return target


def build_native() -> None:
    NATIVE_BIN.mkdir(parents=True, exist_ok=True)
    gcc = find_tool("gcc", "clang")
    gxx = find_tool("g++", "clang++")
    if not gcc or not gxx:
        raise RuntimeError("gcc/g++ (or clang) not found in PATH")
    if IS_WINDOWS:
        run([gcc, "-O2", "-std=c17", "-Wall", "-Wextra", "-o",
             str(NATIVE_BIN / "cforge_native.exe")] + [str(path) for path in C_SOURCES]
            + ["-lshell32"])
        run([gxx, "-std=c++17", "-O2", "-Wall", "-Wextra", "-static",
             "-static-libgcc", "-static-libstdc++", "-o",
             str(NATIVE_BIN / "CodeForgeCore.exe")]
            + [str(ROOT / item) for item in CORE_SOURCES]
            + ["-lwinhttp", "-lshell32"])
        res_obj = []
        windres = find_tool("windres")
        if windres:
            BUILD.mkdir(parents=True, exist_ok=True)
            obj = BUILD / "codeforge-app-res.o"
            run([windres, "-O", "coff", "-I", str(ROOT / "client"),
                 "-i", str(ROOT / "client" / "app.rc"), "-o", str(obj)])
            res_obj = [str(obj)]
        run([gxx, "-std=c++17", "-O2", "-Wall", "-Wextra", "-municode", "-I" + str(ROOT / "core"),
             "-static", "-static-libgcc", "-static-libstdc++", "-o",
             str(NATIVE_BIN / "CodeForge.exe")]
            + [str(ROOT / "client" / "main.cpp"),
               str(ROOT / "core" / "http.cpp"), str(ROOT / "core" / "util.cpp")]
            + res_obj
            + ["-lwinhttp", "-lshell32", "-lgdi32", "-luser32", "-lcomctl32",
               "-ldwmapi", "-lole32"])
    else:
        run([gcc, "-O2", "-std=c17", "-Wall", "-Wextra", "-o",
             str(NATIVE_BIN / "cforge_native")] + [str(path) for path in C_SOURCES] + ["-lm"])
        run([gxx, "-std=c++17", "-O2", "-Wall", "-Wextra", "-o",
             str(NATIVE_BIN / "CodeForgeCore")]
            + [str(ROOT / item) for item in CORE_SOURCES] + ["-lcurl", "-lpthread"])
    log("native", "C and C++ build complete")


def build_java(jdk: Path) -> None:
    javac = jdk / "bin" / f"javac{EXE}"
    jar = jdk / "bin" / f"jar{EXE}"
    if not javac.is_file():
        raise RuntimeError(f"javac not found under {jdk}")
    if CLASSES.exists():
        shutil.rmtree(CLASSES)
    CLASSES.mkdir(parents=True)
    sources = [str(ROOT / "server-java" / "src" / "codeforge" / "server" / name)
               for name in JAVA_SOURCES]
    run([str(javac), "--add-modules", "jdk.httpserver", "-encoding", "UTF-8",
         "-d", str(CLASSES)] + sources)
    BUILD.mkdir(parents=True, exist_ok=True)
    JAR.unlink(missing_ok=True)
    run([str(jar), "--create", "--file", str(JAR),
         "--main-class", "codeforge.server.ServerMain", "-C", str(CLASSES), "."])
    log("java", f"server packaged: {JAR}")


def java_command(jdk: Path, port: int) -> list[str]:
    java = jdk / "bin" / f"java{EXE}"
    if not java.is_file():
        java = Path(find_tool("java") or "java")
    return [str(java), "--add-modules", "jdk.httpserver", "-Dfile.encoding=UTF-8",
            "-jar", str(JAR), "--root", str(ROOT), "--port", str(port)]


def call_selftest(jdk: Path) -> None:
    java = jdk / "bin" / f"java{EXE}"
    core = NATIVE_BIN / f"CodeForgeCore{EXE}"
    if not core.is_file():
        return
    log("selftest", "running native core self-test")
    result = subprocess.run([str(core), "selftest", "--root", str(ROOT)],
                            capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=180)
    print(result.stdout.strip())
    if result.returncode != 0:
        log("selftest", f"core exited {result.returncode}: {result.stderr.strip()}")


def main() -> int:
    parser = argparse.ArgumentParser(description="CodeForge AI bootstrap")
    parser.add_argument("--jdk", action="store_true", help="ensure portable JDK only")
    parser.add_argument("--build", action="store_true", help="build C/C++ and Java")
    parser.add_argument("--all", action="store_true", help="JDK + full build + self-test")
    parser.add_argument("--run", action="store_true", help="start the Java server")
    parser.add_argument("--selftest", action="store_true", help="run the C++ core self-test")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if not any([args.jdk, args.build, args.all, args.run, args.selftest]):
        args.all = True
    try:
        jdk = ensure_jdk()
        if args.all or args.build:
            build_native()
            build_java(jdk)
        if args.all or args.selftest:
            call_selftest(jdk)
        if args.all:
            log("done", f"CodeForge AI {VERSION} is ready")
            log("done", f"server jar: {JAR}")
            log("done", f"start with: python aux-python/bootstrap.py --run --port {args.port}")
        if args.run:
            if not JAR.is_file():
                raise RuntimeError("server jar missing; run --build first")
            command = java_command(jdk, args.port)
            log("server", " ".join(command))
            if os.name == "nt":
                # os.execv drops the quoting on Windows paths that contain spaces.
                return subprocess.call(command)
            os.execv(command[0], command)
    except subprocess.CalledProcessError as exc:
        log("error", f"command failed with exit code {exc.returncode}")
        return exc.returncode or 1
    except Exception as exc:  # noqa: BLE001
        log("error", str(exc))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
