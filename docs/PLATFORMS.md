# CodeForge AI — Platform Guide

Every command you need, for every supported platform.

Version 1.0.5

---

## 1. Support matrix

| Platform | Device / CPU | Host stack | Client | llama.cpp runtime | Status |
| --- | --- | --- | --- | --- | --- |
| Windows 10/11 | x64 | Native build (MinGW) | `CodeForge.exe`, browser | `win-cpu-x64` | **Verified** |
| Windows 11 | ARM64 | x64 build under emulation, or native ARM64 build | Browser | `win-cpu-arm64` with an ARM64 JDK, `win-cpu-x64` under emulation | Buildable |
| Linux | x64 (glibc) | Native build (gcc/clang) | Browser | `ubuntu-x64` | **Compile-verified** (x86_64-linux-musl) |
| Linux | ARM64 (Raspberry Pi 4/5, ARM servers, Asahi) | Native build | Browser | `ubuntu-arm64` | Buildable |
| Linux | s390x | Native build | Browser | `ubuntu-s390x` | Buildable |
| Linux | ARMv7 / x86 32-bit | Native build | Browser | Source build via `llm.llama_server` | Core builds; manual runtime |
| Linux | Alpine / musl (x64, ARM64) | Native build | Browser | glibc assets do not run; source build via `llm.llama_server` | Buildable; manual runtime |
| macOS 12+ | Apple silicon | Native build (clang) | Browser | `macos-arm64` | Buildable |
| macOS 12+ | Intel | Native build (clang) | Browser | `macos-x64` | Buildable |
| Android 8+ | ARM64 phone / tablet | Termux | Browser, Kotlin APK, LAN tunnel | `android-arm64` (automatic) | Supported; on-device runtime validation pending |
| Android 8+ | x86_64 emulator, ARMv7 | Termux (slow) | Kotlin APK | Source build via `llm.llama_server` | Client role recommended |
| iOS 15+ / iPadOS 15+ | iPhone / iPad | iSH (experimental) | Safari to a host, SwiftUI app | Use LAN inference | Client role recommended |

Runtime tags are chosen from `os.name`, `os.arch` and Termux markers, then
matched against the current `ggml-org/llama.cpp` release assets. Windows ARM64
running an x64 JDK reports `amd64` and selects `win-cpu-x64`; a native ARM64
JDK selects `win-cpu-arm64` first, with x64 as a fallback.

When no prebuilt asset exists for an ABI (32-bit ARM, 32-bit x86, iSH), build
llama.cpp from source and set `llm.llama_server` to the resulting binary, or
keep inference on another machine and point `llm.openai_url` at it. Every host
exposes the same console, so any device that can reach a host can act as a
client through an SSH tunnel or LAN proxy.

"Verified" means built and exercised end-to-end on the development machine.
"Compile-verified" means the POSIX sources compile for `x86_64-linux-musl` but
were not executed here.

---

## 2. What you need

| Component | Required? | Purpose |
| --- | --- | --- |
| Python 3.10+ | Yes | Bootstrap, build, self-test driver |
| C compiler (gcc/clang) | Yes | Native helper |
| C++17 compiler | Yes | Main core (and Win32 shell on Windows) |
| Java 17+ | Yes | Control server (portable Temurin is downloaded if missing, except on Termux and Alpine/musl) |
| curl + libcurl headers | POSIX only | HTTP client in the core |
| CMake 3.16+ | Optional | Alternative build path |
| Git | Optional | Cloning the repo |
| Android Studio / SDK | APK only | Kotlin client |
| Xcode 15+ | iOS app only | SwiftUI client |
| XcodeGen | iOS app only | Generates the Xcode project |

CodeForge has no runtime dependency on Ollama, Node.js, or Docker. Ollama is
supported as an optional backend; Node.js is not required at all.

---

## 3. Windows

### 3.1 Install the toolchain

PowerShell (recommended):

```powershell
winget install --id Python.Python.3.13 -e
winget install --id BrechtSanders.WinLibs.POSIX.UCRT -e
winget install --id Kitware.CMake -e
winget install --id Git.Git -e
```

Optional system JDK (otherwise a portable Temurin JDK 17 is downloaded into
`native\jdk\`):

```powershell
winget install --id EclipseAdoptium.Temurin.17.JDK -e
```

MSYS2 alternative:

```powershell
winget install --id MSYS2.MSYS2 -e
# then in the MSYS2 UCRT64 shell:
pacman -S --needed mingw-w64-ucrt-x86_64-gcc make cmake
```

Verify:

```powershell
python --version
gcc --version
g++ --version
javac -version
```

Open a new terminal after installing so PATH is refreshed.

### 3.2 Build

```powershell
cd $env:USERPROFILE\Desktop\CodeForge-AI
python aux-python\bootstrap.py --all
```

`--all` performs: portable JDK check → C helper → C++ core → Win32 shell →
Java compile → JAR packaging → core self-test.

Individual stages:

```powershell
python aux-python\bootstrap.py --jdk
python aux-python\bootstrap.py --build
python aux-python\bootstrap.py --selftest
```

Manual build (when you want to see every flag):

```powershell
cd $env:USERPROFILE\Desktop\CodeForge-AI
New-Item -ItemType Directory -Force native\bin | Out-Null

gcc -O2 -std=c17 -Wall -Wextra -o native\bin\cforge_native.exe `
    native-c\native_tool.c native-c\sha256.c -lshell32

g++ -std=c++17 -O2 -Wall -Wextra -static -static-libgcc -static-libstdc++ `
    -o native\bin\CodeForgeCore.exe `
    core\main.cpp core\config.cpp core\util.cpp core\process.cpp `
    core\http.cpp core\llm.cpp core\runners.cpp core\debugger.cpp `
    -lwinhttp -lshell32

g++ -std=c++17 -O2 -Wall -Wextra -municode -Icore `
    -static -static-libgcc -static-libstdc++ `
    -o native\bin\CodeForge.exe `
    client\main.cpp core\http.cpp core\util.cpp `
    -lwinhttp -lshell32 -lgdi32 -luser32 -lcomctl32 -ldwmapi -lole32

javac --add-modules jdk.httpserver -encoding UTF-8 -d server-java\build\classes `
    server-java\src\codeforge\server\*.java

jar --create --file server-java\build\CodeForgeServer.jar `
    --main-class codeforge.server.ServerMain -C server-java\build\classes .
```

CMake alternative (MinGW Makefiles):

```powershell
cmake -S . -B build -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
```

Do not mix `build\` (CMake) with `server-java\build\` (Java classes).

### 3.3 Run

Option A — native control center:

```powershell
.\native\bin\CodeForge.exe
```

It starts the Java server, shows the control center, and opens the console in
Edge app mode. Use the tray icon to stop everything.

Option B — console only:

```powershell
python aux-python\bootstrap.py --run --port 8765
```

Open: <http://127.0.0.1:8765/console/>

Option C — server without Python:

```powershell
java --add-modules jdk.httpserver -Dfile.encoding=UTF-8 `
  -jar server-java\build\CodeForgeServer.jar `
  --root "$env:USERPROFILE\Desktop\CodeForge-AI" --port 8765
```

Edge app window manually:

```powershell
& "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
  --app=http://127.0.0.1:8765/console/ `
  --window-size=1680,1050
```

### 3.4 Daily operations

```powershell
# health
curl.exe -s http://127.0.0.1:8765/api/health

# model registry
curl.exe -s http://127.0.0.1:8765/api/models

# download a model (returns immediately)
curl.exe -s -X POST http://127.0.0.1:8765/api/models/download `
  -H "Content-Type: application/json" -d '{\"id\":\"qwen3-4b-instruct-2507\"}'

# install the llama.cpp runtime
curl.exe -s -X POST http://127.0.0.1:8765/api/runtime/install `
  -H "Content-Type: application/json" -d "{}"

# start inference
curl.exe -s -X POST http://127.0.0.1:8765/api/runtime/start `
  -H "Content-Type: application/json" -d '{\"model_id\":\"qwen3-4b-instruct-2507\"}'

# stop inference
curl.exe -s -X POST http://127.0.0.1:8765/api/runtime/stop `
  -H "Content-Type: application/json" -d "{}"

# shutdown the server
curl.exe -s -X POST http://127.0.0.1:8765/api/shutdown `
  -H "Content-Type: application/json" -d "{}"
```

Logs and sessions:

```powershell
Get-Content logs\llama-server.log -Tail 100 -Wait
Get-Content logs\server.log -Tail 50 -Wait
explorer workspace
explorer models\downloads
```

### 3.5 Firewall and exposure audit

The server binds loopback only. To confirm nothing is exposed:

```powershell
Get-NetTCPConnection -LocalPort 8765 -State Listen | Format-Table
Get-NetTCPConnection -LocalPort 8080 -State Listen | Format-Table
netstat -ano | findstr :8765
```

If an unexpected listener appears, find and stop it:

```powershell
Get-Process -Id (Get-NetTCPConnection -LocalPort 8765).OwningProcess
Stop-Process -Id <pid>
```

Do **not** create an inbound allow rule for 8765. If you need remote access,
use an SSH tunnel instead (section 10).

### 3.6 Start automatically

Startup folder shortcut:

```powershell
$startup = [Environment]::GetFolderPath('Startup')
$ws = New-Object -ComObject WScript.Shell
$sc = $ws.CreateShortcut("$startup\CodeForge AI.lnk")
$sc.TargetPath = "$env:USERPROFILE\Desktop\CodeForge-AI\native\bin\CodeForge.exe"
$sc.WorkingDirectory = "$env:USERPROFILE\Desktop\CodeForge-AI"
$sc.Save()
```

Task Scheduler (starts at logon, no window):

```powershell
$root = "$env:USERPROFILE\Desktop\CodeForge-AI"
$action = New-ScheduledTaskAction -Execute "java" `
  -Argument "--add-modules jdk.httpserver -Dfile.encoding=UTF-8 -jar `"$root\server-java\build\CodeForgeServer.jar`" --root `"$root`" --port 8765" `
  -WorkingDirectory $root
$trigger = New-ScheduledTaskTrigger -AtLogOn
Register-ScheduledTask -TaskName "CodeForge AI Server" -Action $action -Trigger $trigger -RunLevel Limited
```

Remove it:

```powershell
Unregister-ScheduledTask -TaskName "CodeForge AI Server" -Confirm:$false
```

### 3.7 Uninstall / clean

```powershell
# stop first
curl.exe -s -X POST http://127.0.0.1:8765/api/shutdown -H "Content-Type: application/json" -d "{}"

# remove generated artifacts but keep sources
Remove-Item -Recurse -Force build, server-java\build, native\jdk, runtime, workspace, logs -ErrorAction SilentlyContinue

# remove downloaded models too (irreversible)
Remove-Item -Recurse -Force models\downloads -ErrorAction SilentlyContinue
```

### 3.8 Windows 11 ARM64

The x64 release runs on Windows 11 ARM64 through the built-in x64 emulation;
no separate installer is needed. An x64 JDK reports `os.arch=amd64`, so the
runtime installer selects `win-cpu-x64`, which also runs under emulation.

Check what the server selected:

```powershell
curl.exe -s http://127.0.0.1:8765/api/status |
  python -c "import json,sys; d=json.load(sys.stdin); print(d['server']['arch'], d['runtime'].get('platform'))"
```

A native ARM64 build of the C++ core requires an ARM64 C++17 toolchain and an
ARM64 JDK 17; the runtime installer then prefers `win-cpu-arm64` and falls back
to `win-cpu-x64`. Prebuilt ARM64 Windows binaries are not shipped in 1.0.5;
the emulated x64 bundle is the supported route.

Native build with MSYS2 CLANGARM64:

```powershell
winget install MSYS2.MSYS2
```

Open the **MSYS2 CLANGARM64** shell, then:

```bash
pacman -S --needed mingw-w64-clang-aarch64-clang \
    mingw-w64-clang-aarch64-cmake mingw-w64-clang-aarch64-curl \
    mingw-w64-clang-aarch64-make git
git clone <your-repository-url> CodeForge-AI
cd CodeForge-AI
cmake -S . -B build -G "Unix Makefiles" -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
```

Install a Windows ARM64 JDK 17 (Adoptium `windows/aarch64` build) and keep
`native/jdk` absent so bootstrap uses it. With only an x64 JDK, Java reports
`amd64` and the installer keeps selecting `win-cpu-x64`, which still runs
under emulation.

---

## 4. Linux

### 4.1 Debian / Ubuntu / Mint / Pop!_OS

```bash
sudo apt update
sudo apt install -y build-essential python3 python3-venv curl libcurl4-openssl-dev \
                    openjdk-17-jdk cmake zip unzip git
```

### 4.2 Fedora / RHEL / Rocky

```bash
sudo dnf install -y gcc gcc-c++ make python3 curl libcurl-devel \
                    java-17-openjdk-devel cmake zip unzip git
```

### 4.3 Arch / Manjaro

```bash
sudo pacman -S --needed base-devel python curl jdk17-openjdk cmake zip unzip git
```

### 4.4 openSUSE

```bash
sudo zypper install -y gcc gcc-c++ make python3 curl libcurl-devel \
                      java-17-openjdk-devel cmake zip unzip git
```

### 4.5 Alpine (iSH, containers)

```bash
apk add build-base python3 curl curl-dev openjdk17-jdk cmake zip unzip git bash
```

### 4.6 Build

```bash
cd ~/Desktop/CodeForge-AI       # or wherever you cloned it
python3 aux-python/bootstrap.py --all
```

If `javac` is missing and you do not want a portable JDK, install a distro JDK
17; bootstrap prefers the system JDK when its version is 17, 21 or 22.
On s390x there is no Temurin build — install `openjdk-17-jdk` from the distro
(or IBM Semeru for s390x) before running bootstrap.

Manual build:

```bash
mkdir -p native/bin

gcc -O2 -std=c17 -Wall -Wextra -o native/bin/cforge_native \
    native-c/native_tool.c native-c/sha256.c -lm

g++ -std=c++17 -O2 -Wall -Wextra -o native/bin/CodeForgeCore \
    core/main.cpp core/config.cpp core/util.cpp core/process.cpp \
    core/http.cpp core/llm.cpp core/runners.cpp core/debugger.cpp \
    -lcurl -lpthread

mkdir -p server-java/build/classes
javac --add-modules jdk.httpserver -encoding UTF-8 \
      -d server-java/build/classes \
      server-java/src/codeforge/server/*.java

jar --create --file server-java/build/CodeForgeServer.jar \
    --main-class codeforge.server.ServerMain -C server-java/build/classes .
```

CMake:

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j"$(nproc)"
```

or:

```bash
./build.sh
```

CPU architectures. The same sources build on every Linux ABI; only the managed
llama.cpp asset differs:

```bash
uname -m
# x86_64  -> ubuntu-x64
# aarch64 -> ubuntu-arm64
# s390x   -> ubuntu-s390x
# armv7l / i686 -> no prebuilt asset
```

On 32-bit ARM/x86 or Alpine/musl, build llama.cpp locally and point the server
at it:

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp
cmake -S llama.cpp -B llama.cpp/build -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF
cmake --build llama.cpp/build -j"$(nproc)" --target llama-server
export CODEFORGE_LLAMA_SERVER="$PWD/llama.cpp/build/bin/llama-server"
python3 aux-python/bootstrap.py --run --port 8765
```

### 4.7 Run

```bash
python3 aux-python/bootstrap.py --run --port 8765
# open http://127.0.0.1:8765/console/
```

or without Python:

```bash
java --add-modules jdk.httpserver -Dfile.encoding=UTF-8 \
  -jar server-java/build/CodeForgeServer.jar \
  --root "$PWD" --port 8765
```

Headless machine (server on a remote box, UI on your laptop):

```bash
ssh -L 8765:127.0.0.1:8765 user@server
# then open http://127.0.0.1:8765/console/ on the laptop
```

### 4.8 systemd user service

```bash
mkdir -p ~/.config/systemd/user
cat > ~/.config/systemd/user/codeforge.service <<'EOF'
[Unit]
Description=CodeForge AI local server
After=network.target

[Service]
Type=simple
WorkingDirectory=%h/Desktop/CodeForge-AI
ExecStart=/usr/bin/java --add-modules jdk.httpserver -Dfile.encoding=UTF-8 -jar %h/Desktop/CodeForge-AI/server-java/build/CodeForgeServer.jar --root %h/Desktop/CodeForge-AI --port 8765
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now codeforge.service
systemctl --user status codeforge.service
journalctl --user -u codeforge.service -f
```

Stop and disable:

```bash
systemctl --user disable --now codeforge.service
```

Note: the service starts only the server, not llama-server. Start inference
through the API/UI after login so it runs under your session and can access the
models directory.

### 4.9 Firewall audit

UFW:

```bash
sudo ufw status verbose
sudo ufw deny 8765/tcp
sudo ufw deny 8080/tcp
```

firewalld:

```bash
sudo firewall-cmd --state
sudo firewall-cmd --list-all
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" port port="8765" protocol="tcp" reject'
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" port port="8080" protocol="tcp" reject'
sudo firewall-cmd --reload
```

Confirm listeners:

```bash
ss -ltnp | grep -E '8765|8080'
lsof -nP -iTCP:8765 -sTCP:LISTEN
```

### 4.10 Linux desktop entry

`~/.local/share/applications/codeforge.desktop`:

```ini
[Desktop Entry]
Type=Application
Name=CodeForge AI
Comment=Local-first intelligent dev workbench
Exec=sh -c 'cd %h/Desktop/CodeForge-AI && python3 aux-python/bootstrap.py --run'
Terminal=false
Categories=Development;
```

```bash
chmod +x ~/.local/share/applications/codeforge.desktop
update-desktop-database ~/.local/share/applications 2>/dev/null || true
```

### 4.11 Clean

```bash
rm -rf build server-java/build native/jdk runtime workspace logs
# models are large; delete only if you mean it
rm -rf models/downloads
```

---

## 5. macOS

### 5.1 Install the toolchain

```bash
xcode-select --install
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
brew install python@3.13 cmake curl openjdk@17
```

Apple Silicon note: Homebrew installs to `/opt/homebrew`; make sure
`/opt/homebrew/bin` is on PATH:

```bash
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zshrc
source ~/.zshrc
```

Verify:

```bash
clang --version
python3 --version
java -version
```

### 5.2 Build and run

```bash
cd ~/Desktop/CodeForge-AI
python3 aux-python/bootstrap.py --all
python3 aux-python/bootstrap.py --run --port 8765
open http://127.0.0.1:8765/console/
```

Manual build (Apple clang):

```bash
mkdir -p native/bin

clang -O2 -std=c17 -Wall -Wextra -o native/bin/cforge_native \
      native-c/native_tool.c native-c/sha256.c

clang++ -std=c++17 -O2 -Wall -Wextra -o native/bin/CodeForgeCore \
      core/main.cpp core/config.cpp core/util.cpp core/process.cpp \
      core/http.cpp core/llm.cpp core/runners.cpp core/debugger.cpp \
      -lcurl -lpthread

mkdir -p server-java/build/classes
javac --add-modules jdk.httpserver -encoding UTF-8 \
      -d server-java/build/classes server-java/src/codeforge/server/*.java
jar --create --file server-java/build/CodeForgeServer.jar \
    --main-class codeforge.server.ServerMain -C server-java/build/classes .
```

If `-lcurl` fails, use Homebrew's curl:

```bash
export CPPFLAGS="-I/opt/homebrew/opt/curl/include"
export LDFLAGS="-L/opt/homebrew/opt/curl/lib"
```

### 5.3 Run as a background agent (launchd)

`~/Library/LaunchAgents/ai.codeforge.server.plist`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>ai.codeforge.server</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/java</string>
    <string>--add-modules</string><string>jdk.httpserver</string>
    <string>-Dfile.encoding=UTF-8</string>
    <string>-jar</string>
    <string>/Users/YOU/Desktop/CodeForge-AI/server-java/build/CodeForgeServer.jar</string>
    <string>--root</string>
    <string>/Users/YOU/Desktop/CodeForge-AI</string>
    <string>--port</string><string>8765</string>
  </array>
  <key>WorkingDirectory</key>
  <string>/Users/YOU/Desktop/CodeForge-AI</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><false/>
  <key>StandardOutPath</key>
  <string>/Users/YOU/Desktop/CodeForge-AI/logs/launchd.out.log</string>
  <key>StandardErrorPath</key>
  <string>/Users/YOU/Desktop/CodeForge-AI/logs/launchd.err.log</string>
</dict>
</plist>
```

Replace `YOU` with your user name, then:

```bash
launchctl load ~/Library/LaunchAgents/ai.codeforge.server.plist
launchctl list | grep codeforge
launchctl unload ~/Library/LaunchAgents/ai.codeforge.server.plist
```

### 5.4 Gatekeeper and quarantine

If you downloaded a release ZIP and macOS blocks the binaries:

```bash
xattr -dr com.apple.quarantine CodeForge-AI
```

### 5.5 Audit and firewall

```bash
lsof -nP -iTCP:8765 -sTCP:LISTEN
lsof -nP -iTCP:8080 -sTCP:LISTEN
/usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate
```

macOS saves GUI apps' state; for a headless server use `launchd` as above.

---

## 6. Windows Subsystem for Linux (optional)

WSL2 works for the server and core, but loopback is namespaced: the Windows
browser cannot reach `127.0.0.1:8765` inside WSL by default. Options:

```bash
# inside WSL
python3 aux-python/bootstrap.py --run --port 8765
hostname -I     # note the WSL IP
```

Then open `http://<wsl-ip>:8765/console/`. The server still binds 127.0.0.1
inside WSL, so this will not work without changing the bind address — which the
project intentionally does not expose. The supported pattern is to run
CodeForge on Windows itself, or use an SSH tunnel:

```bash
# on Windows PowerShell
ssh -L 8765:127.0.0.1:8765 user@wsl-host
```

WSL is best used as a toolchain host, not as the server location.

---

## 7. Android — Termux route

This runs the full server, core, and inference **on the phone**. Nothing leaves
the device.

### 7.1 Install Termux

Install from F-Droid (the Play Store build is outdated):

<https://f-droid.org/packages/com.termux/>

### 7.2 Prepare the environment

```bash
pkg update -y && pkg upgrade -y
pkg install -y python clang make cmake git curl libcurl openjdk-17 zip unzip
termux-setup-storage
```

Copy the project to Termux's home (recommended; storage emulation is slower and
may break permissions):

```bash
cp -r ~/storage/downloads/CodeForge-AI ~/
cd ~/CodeForge-AI
```

If you cloned from Git:

```bash
git clone <your-repository-url> CodeForge-AI
cd CodeForge-AI
```

### 7.3 Build

```bash
python aux-python/bootstrap.py --all
```

Bootstrap uses `clang`/`clang++` automatically when `gcc`/`g++` are absent. The
portable JDK downloader is skipped because Termux's `openjdk-17` provides
`javac` 17; never download the glibc Temurin build for Android — it cannot run
on bionic.

### 7.4 Run the server

```bash
python aux-python/bootstrap.py --run --port 8765
```

Open in the phone browser: <http://127.0.0.1:8765/console/>

Keep Termux alive while downloading models:

```bash
termux-wake-lock
# release later
termux-wake-unlock
```

Prevent Android from killing Termux: disable battery optimization for Termux in
Android settings, and enable the persistent notification.

### 7.5 Inference runtime on Termux

llama.cpp publishes Android ARM64 archives. On an ARM64 phone or tablet the
normal install works: CodeForge detects Termux through `TERMUX_VERSION` or
`PREFIX` and resolves the generic `android-arm64` asset, with
`android-arm64-snapdragon` as a fallback.

From the Models tab press **Install runtime**, or call the API:

```bash
curl -s -X POST http://127.0.0.1:8765/api/runtime/install \
  -H "Content-Type: application/json" -d '{}'
```

Then start inference from the UI or API:

```bash
curl -s -X POST http://127.0.0.1:8765/api/runtime/start \
  -H "Content-Type: application/json" -d '{"model_id":"qwen3-4b-instruct-2507"}'
```

For 32-bit ARM, x86_64 emulators, or a custom build, compile llama.cpp on the
device and point CodeForge at the binary. This override works on every
platform and takes priority over the managed runtime:

```bash
pkg install -y git cmake
git clone --depth 1 https://github.com/ggml-org/llama.cpp ~/llama.cpp
cd ~/llama.cpp
cmake -B build -DCMAKE_BUILD_TYPE=Release -DLLAMA_CURL=OFF
cmake --build build -j"$(nproc)" --target llama-server
export CODEFORGE_LLAMA_SERVER="$HOME/llama.cpp/build/bin/llama-server"
cd ~/CodeForge-AI
python aux-python/bootstrap.py --run --port 8765
```

To persist the override in `config.json` instead of exporting it each time:

```bash
cd ~/CodeForge-AI
python - <<'PY'
import json, pathlib
path = pathlib.Path("config.json")
data = json.loads(path.read_text(encoding="utf-8"))
data["llm"]["llama_server"] = str(pathlib.Path.home() / "llama.cpp/build/bin/llama-server")
path.write_text(json.dumps(data, indent=2), encoding="utf-8")
print(data["llm"]["llama_server"])
PY
```

`runtime/runtime.json` is written by automatic installs; do not edit it by hand
when the config override is available. Model sizing on phones: 4B Q4 (~2.5 GB)
is the practical ceiling for 8 GB devices; 8B needs 12 GB+ free RAM and will be
slow. Keep the screen on during the first load.

### 7.6 Access from a PC browser (optional)

If you want the desktop browser UI while inference runs on the phone, do not
expose the port. Use SSH from the PC:

```bash
# PC
ssh -L 8765:127.0.0.1:8765 -p 8022 u0_a123@phone-ip
# then open http://127.0.0.1:8765/console/
```

Termux SSH:

```bash
pkg install -y openssh
sshd
whoami   # gives the username, e.g. u0_a123
```

### 7.7 Storage and cleanup

```bash
du -sh ~/CodeForge-AI/models/downloads
rm -rf ~/CodeForge-AI/workspace/*
```

---

## 8. Android — APK thin client

The APK is a WebView shell for a CodeForge server running on a PC, Mac, or the
same phone. It does not bundle the server.

### 8.1 Requirements

- Android Studio (Koala or newer) **or** the bundled Gradle 8.7 wrapper
  (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) — no separate Gradle install needed
- JDK 17 for Gradle
- Android SDK 34 (`ANDROID_HOME` or `mobile/android/local.properties`)
- The client APK is pure Kotlin/Java, so it runs on every Android ABI
  (arm64-v8a, armeabi-v7a, x86, x86_64) and on phones, tablets and emulators

### 8.2 Build with Android Studio

1. **File → Open** → `mobile/android`.
2. Let Gradle sync (the wrapper pins Gradle 8.7 automatically).
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**.
4. Output: `mobile/android/app/build/outputs/apk/debug/app-debug.apk`.

### 8.3 Build from the command line

The wrapper downloads Gradle 8.7 on first use (~130 MB, cached in `~/.gradle`):

```bash
cd mobile/android
chmod +x gradlew       # after a ZIP download or a clone created on Windows
./gradlew assembleDebug
# Windows: gradlew.bat assembleDebug
```

With system SDK configured (`ANDROID_HOME` or `local.properties`):

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
```

### 8.4 Install

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Manual: copy the APK to the phone and open it (allow install from unknown
sources for the file manager).

### 8.5 Configure the server URL

On first launch the client opens:

- Emulator: `http://10.0.2.2:8765/console/` (the emulator's alias for the host)
- Physical device: edit the URL via the in-app settings (long-press the screen
  or use the gear) to `http://<pc-lan-ip>:8765/console/`

Because the server binds `127.0.0.1`, a physical device cannot reach it
directly. Choose one:

```bash
# A. SSH tunnel from the phone (Termux) to the PC
ssh -L 8765:127.0.0.1:8765 user@pc-lan-ip
# then point the app at http://127.0.0.1:8765/console/

# B. Authenticating reverse proxy on the PC (advanced, not provided)
#    Do not expose 8765 directly.
```

### 8.6 Notes

- Cleartext HTTP is enabled intentionally for LAN use.
- The app requests only `INTERNET`.
- No Play Services, no analytics, no background services.
- Uninstall with `adb uninstall ai.codeforge.thin`.

---

## 9. iOS

### 9.1 iSH route (Alpine terminal on the iPhone/iPad)

iSH is an x86 Alpine emulator. It is useful as a **client** to a CodeForge
server running on a PC/Mac, and only experimentally as a host.

Install iSH from the App Store, then:

```sh
apk update
apk add python3 curl openjdk17-jdk build-base curl-dev cmake git bash zip unzip
```

Client role (recommended):

```sh
# On the PC, ensure CodeForge runs locally, then from iSH:
ssh -L 8765:127.0.0.1:8765 user@pc-lan-ip
# in another iSH session, use curl or a browser at http://127.0.0.1:8765
curl -s http://127.0.0.1:8765/api/health
```

iSH cannot run a full desktop browser; use Safari on the same device pointed at
the tunnel is not possible because the tunnel lives inside iSH. Practical
patterns:

- Use `curl` inside iSH for status, chat and debug calls.
- Use Safari with a URL served by your PC's authenticating proxy (advanced).
- Use the Xcode-built app (below) for a native WebView experience.

Host role (experimental):

```sh
cd ~/CodeForge-AI
python3 aux-python/bootstrap.py --all
python3 aux-python/bootstrap.py --run --port 8765
```

Bootstrap refuses to download the glibc Temurin JDK on Alpine/musl; install
`openjdk17-jdk` first. iSH is a 32-bit x86 emulator, so llama.cpp has no
matching prebuilt runtime and local inference is not practical. To use a PC's
inference while keeping the control server in iSH:

```sh
cd ~/CodeForge-AI
python3 - <<'PY'
import json, pathlib
path = pathlib.Path("config.json")
data = json.loads(path.read_text(encoding="utf-8"))
data["llm"]["backend"] = "openai"
data["llm"]["openai_url"] = "http://192.168.1.10:8080"
path.write_text(json.dumps(data, indent=2), encoding="utf-8")
print(data["llm"])
PY
curl -s http://127.0.0.1:8765/api/health
```

Do not press **Start inference** in this mode: the external service is already
running on the LAN host, and CodeForge's Chat/Debug paths call it directly.

### 9.2 Native iOS app (Xcode)

Requirements: macOS with Xcode 15+, XcodeGen, an Apple ID for signing.

```bash
brew install xcodegen
cd mobile/ios
xcodegen generate
open CodeForge.xcodeproj
```

In Xcode:

1. Select the **CodeForge** target → **Signing & Capabilities** → choose your
   team; change the bundle id if needed (`ai.codeforge.thin` is a placeholder).
2. Select a simulator or a connected device.
3. **Product → Run** (⌘R).

Command-line build:

```bash
# Simulator
xcodebuild -project CodeForge.xcodeproj -scheme CodeForge \
  -configuration Debug -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 15' build

# Device (unsigned archive; signing required to install)
xcodebuild -project CodeForge.xcodeproj -scheme CodeForge \
  -configuration Release -sdk iphoneos \
  -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

Install on a device:

```bash
xcrun devicectl list devices
xcrun devicectl device install app --device <UDID> \
  build/Build/Products/Release-iphoneos/CodeForge.app
```

Configuration:

- Simulator reaches the Mac host at `http://127.0.0.1:8765`.
- A physical iPhone must use a LAN address or a tunnel. The app's settings
  sheet lets you set the URL; presets include localhost, `10.0.2.2` (emulator
  style), and a custom host.
- ATS allows arbitrary loads for local networking. Do not ship this to the App
  Store without tightening it and adding TLS.

---

## 10. Remote access patterns

The server is loopback-only by design. Three safe patterns:

### 10.1 SSH tunnel (recommended)

```bash
ssh -L 8765:127.0.0.1:8765 user@host
# browse http://127.0.0.1:8765/console/
```

The tunnel carries loopback traffic inside SSH; nothing is exposed.

### 10.2 VPN

```bash
# Tailscale example
tailscale up
tailscale ip -4
# on the client: ssh -L 8765:127.0.0.1:8765 user@100.x.y.z
```

### 10.3 Authenticating reverse proxy (advanced)

If you must serve multiple users, put a hardened proxy in front (Caddy, nginx,
Traefik) with TLS and authentication, and keep the upstream bound to
`127.0.0.1`. Example Caddyfile sketch:

```
codeforge.example.com {
    basic_auth {
        alice $2a$14$...hash...
    }
    reverse_proxy 127.0.0.1:8765
}
```

Anyone who authenticates can execute code as the server user. Treat proxy
access as shell access. Do not expose the API to the public internet.

---

## 11. Command reference

### 11.1 Bootstrap

```bash
python aux-python/bootstrap.py --all
python aux-python/bootstrap.py --jdk
python aux-python/bootstrap.py --build
python aux-python/bootstrap.py --selftest
python aux-python/bootstrap.py --run --port 8765
python aux-python/bootstrap.py --help
```

Environment overrides:

| Variable | Effect |
| --- | --- |
| `CODEFORGE_FORCE_JDK=1` | Ignore system JDK, download portable Temurin |
| `CODEFORGE_LLM_BACKEND` | `auto`, `openai`, `ollama`, `mock` |
| `CODEFORGE_OPENAI_URL` | OpenAI-compatible base URL |
| `CODEFORGE_OLLAMA_URL` | Ollama base URL |
| `CODEFORGE_LLM_API_KEY` | Bearer token for a gateway |
| `CODEFORGE_LLM_MODEL` | Model name override |
| `CODEFORGE_LLM_TEMPERATURE` | 0.0–2.0 |
| `CODEFORGE_LLM_MAX_TOKENS` | 64–393216 |
| `CODEFORGE_REQUEST_TIMEOUT_MS` | 5 000–3 600 000 |
| `CODEFORGE_DEBUG_ITERATIONS` | 1–8 |
| `CODEFORGE_RUN_TIMEOUT_MS` | 1 000–600 000 |
| `CODEFORGE_COMPILE_TIMEOUT_MS` | 5 000–600 000 |
| `CODEFORGE_SERVER_PORT` | 1024–65535 |
| `CODEFORGE_LLAMA_SERVER` | Absolute or relative path to a llama-server binary; overrides the managed runtime |

### 11.2 Core CLI

```bash
native/bin/CodeForgeCore version --root .
echo '{}' | native/bin/CodeForgeCore status --root .
echo '{"messages":[{"role":"user","content":"hi"}]}' | native/bin/CodeForgeCore chat --root .
echo '{"code":"print(1/0)","language":"python","iterations":1}' | native/bin/CodeForgeCore debug --root .
echo '{"code":"print(42)","language":"python"}' | native/bin/CodeForgeCore run --root .
native/bin/CodeForgeCore selftest --root .
```

Windows: `native\bin\CodeForgeCore.exe`.

### 11.3 Native helper

```bash
native/bin/cforge_native hash path/to/file
native/bin/cforge_native verify path/to/file <expected-sha256>
native/bin/cforge_native sysinfo
```

Windows: `native\bin\cforge_native.exe`. `verify` exits `0` on match, `3` on
mismatch, and prints JSON.

### 11.4 llama-server directly

```bash
runtime/llama.cpp/llama-server -m models/downloads/<id>/<file>.gguf \
  --host 127.0.0.1 --port 8080 -c 16384 --parallel 1 -t 4 --no-webui
```

Windows path: `runtime\llama.cpp\llama-server.exe`.

A source-built or distro-installed binary can replace the managed runtime:

```bash
export CODEFORGE_LLAMA_SERVER=/opt/llama.cpp/build/bin/llama-server
# or set llm.llama_server in config.json
python aux-python/bootstrap.py --run --port 8765
```

Test it:

```bash
curl -s http://127.0.0.1:8080/v1/models
curl -s http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"local","messages":[{"role":"user","content":"hello"}]}'
```

### 11.5 API

See [API.md](API.md) for the complete list. Quick checks:

```bash
curl -s http://127.0.0.1:8765/api/health
curl -s http://127.0.0.1:8765/api/status
curl -s http://127.0.0.1:8765/api/models
curl -s http://127.0.0.1:8765/api/downloads
curl -s "http://127.0.0.1:8765/api/runtime/logs?lines=80"
```

### 11.6 Model management

```bash
# list registry + install state
curl -s http://127.0.0.1:8765/api/models | python -m json.tool

# resolve upstream URL and hash
curl -s "http://127.0.0.1:8765/api/models/resolve?id=qwen3-4b-instruct-2507"

# download (background)
curl -s -X POST http://127.0.0.1:8765/api/models/download \
  -H "Content-Type: application/json" -d '{"id":"deepseek-r1-0528-qwen3-8b"}'

# watch progress
watch -n 2 'curl -s http://127.0.0.1:8765/api/downloads'
```

Windows `watch` equivalent:

```powershell
while ($true) { curl.exe -s http://127.0.0.1:8765/api/downloads; Start-Sleep 2 }
```

---

## 12. Troubleshooting by platform

### All platforms

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Main core is not built` | Missing binary | `python aux-python/bootstrap.py --build` |
| `gcc/g++ not found` | PATH | Install toolchain, reopen terminal |
| `javac not found` | No JDK 17+ | Install JDK or let bootstrap download Temurin |
| `Content-Type must be application/json` | POST without JSON header | Add `-H "Content-Type: application/json"` |
| `Cross-origin request rejected` | Origin differs from Host | Use the served console or a same-origin client |
| Port already in use | Another server | `netstat`/`ss`/`lsof`, stop the process, or pass `--port` |
| Download stuck at 0% | Network/DNS | Check `curl -I https://hf-mirror.com`; set `llm.model_mirror` if needed |
| Download fails at 45% | Connection drop | Automatic resume; check `/api/downloads` message |
| `SHA-256 mismatch` | Corrupted `.part` | Delete the `.part` and retry |
| Model loads forever | Too little RAM | Choose a smaller model |
| Inference 500 | llama-server died | Check `logs/llama-server.log`, restart runtime |

### Windows

| Symptom | Fix |
| --- | --- |
| `'python' is not recognized` | Reinstall Python with "Add to PATH", or use `py -3` |
| `undefined reference to WinMain` | Add `-municode` when linking the Win32 shell |
| Console window flashes | Launch from PowerShell to see errors |
| Edge app window blank | Server not running; check `curl.exe -s http://127.0.0.1:8765/api/health` |
| Antivirus blocks executables | Add `native\bin` to exclusions, or rebuild from source |
| Path too long | Enable long paths or move the project to `C:\CodeForge-AI` |

### Linux

| Symptom | Fix |
| --- | --- |
| `curl/curl.h: No such file` | Install `libcurl4-openssl-dev` / `libcurl-devel` / `curl-dev` |
| `cannot find -lcurl` | Same as above; verify `pkg-config --libs libcurl` |
| Permission denied starting server | Port < 1024? Use 8765 |
| `java: command not found` | Install JDK 17/21, or use the JAR with a full path |

### macOS

| Symptom | Fix |
| --- | --- |
| `xcrun: error` | `xcode-select --install` |
| `'openssl/ssl.h' not found` | `brew install openssl` and set CPPFLAGS/LDFLAGS |
| `-lcurl` failure | Use Homebrew curl flags from section 5.2 |
| App blocked by Gatekeeper | `xattr -dr com.apple.quarantine CodeForge-AI` |
| `zsh: command not found: brew` | Add Homebrew shellenv to `~/.zshrc` |

### Termux / iSH

| Symptom | Fix |
| --- | --- |
| `pkg: command not found` | You are not in Termux/iSH |
| Build killed (OOM) | Build with `-j1`, close other apps |
| Termux sleeps during download | `termux-wake-lock`, disable battery optimization |
| Runtime asset is glibc | Build llama.cpp from source (section 7.5) |
| `llama-server` permission denied | `chmod +x runtime/llama.cpp/llama-server` |
| iSH extremely slow | Expected; use it as a client, not a host |

### Android APK

| Symptom | Fix |
| --- | --- |
| Gradle sync fails | Use JDK 17, not 21/22, for AGP 8.5 |
| `SDK location not found` | Create `local.properties` with `sdk.dir=` |
| App cannot connect | Use the correct host; emulator uses `10.0.2.2` |
| Cleartext blocked | The app config allows cleartext; check the URL scheme is `http` |
| TLS error | Local servers usually have no TLS; use `http` on a trusted LAN |

### iOS

| Symptom | Fix |
| --- | --- |
| `xcodegen: command not found` | `brew install xcodegen` |
| Signing error | Set your team, change the bundle id |
| Device cannot reach server | Use LAN URL or SSH tunnel; loopback is the Mac/phone itself |
| ATS blocks HTTP | Already configured for local networking; for public hosts use HTTPS |
| App Store rejection | Expected: remove arbitrary ATS loads, add TLS, and use a real backend |

---

## 13. Ports and processes

| Port | Process | Bind | Stop |
| --- | --- | --- | --- |
| 8765 | Java server | 127.0.0.1 | `POST /api/shutdown`, tray, Ctrl+C |
| 8080 | llama-server | 127.0.0.1 | `POST /api/runtime/stop` |

Find who owns a port:

```bash
# Windows
netstat -ano | findstr :8765
tasklist /FI "PID eq <pid>"

# Linux/macOS
lsof -nP -iTCP:8765 -sTCP:LISTEN
ss -ltnp | grep 8765
```

Kill the whole tree on Windows:

```powershell
taskkill /PID <pid> /T /F
```

POSIX:

```bash
kill -TERM <pid>
kill -KILL -<process-group-id>   # only if you know the group
```

---

## 14. Disk and model planning

| Model | File size | Min RAM | Practical on |
| --- | ---: | ---: | --- |
| Qwen3 4B Instruct 2507 Q4_K_M | 2.5 GB | 6 GB | 8 GB laptops, high-end phones |
| DeepSeek R1 0528 Qwen3 8B Q4_K_M | 5.0 GB | 8 GB | 16 GB machines |
| Yi-Coder 9B Chat Q4_K_M | 5.3 GB | 8 GB | 16 GB machines |
| DeepSeek-Coder V2 Lite Q4_K_M | 10.4 GB | 14 GB | 24 GB machines |
| Qwen3-Coder 30B-A3B Q4_K_M | 18.6 GB | 24 GB | 32 GB+ workstations |

Space needed = model size + `.part` during download + ~1 GB workspace/runtime.
A 2.5 GB model temporarily needs ~5.5 GB free.

Check free space:

```bash
# Windows
Get-PSDrive C | Select-Object Used,Free
# Linux/macOS
df -h .
# Android
df -h ~
```

---

## 15. Related documents

- [Manual](MANUAL.md) — complete operating walkthrough
- [API](API.md) — endpoint reference
- [Architecture](ARCHITECTURE.md) — how the components interact
- [Security](SECURITY.md) — exposure rules and hardening
- [Models](MODELS.md) — registry and selection guide
