# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.0.3 — 2026-11-16

### Changed

- **Offline model downloads now use a domestic source by default.** Model
  metadata and files resolve through `https://hf-mirror.com` first, with
  `https://huggingface.co` kept as an automatic fallback. The upstream
  repositories, filenames and SHA-256 values are unchanged.
- The mirror is configurable with `llm.model_mirror` in `config.json` or
  `CODEFORGE_MODEL_MIRROR`; comma-separated sources are tried in order.
- Fixed SHA-256 extraction for Hugging Face metadata that reports
  `lfs.sha256` instead of the legacy `lfs.oid`.
- Version, native binaries, server jar, web console, mobile projects,
  documentation and diagrams updated to 1.0.3.

### Notes

- A mirror-only metadata response that omits size or SHA-256 is rejected and
  the next source is tried, so an unverified model is never registered.

## 1.0.2 — 2026-11-16

### Added

- **User-selectable context window and slots.** Models → Inference runtime now
  has Context (2K–512K) and Slots (1–8) selectors. The choice is remembered in
  the browser and passed to `POST /api/runtime/start` as `context_size` and
  `parallel`; it applies to the next managed llama-server start.
- The runtime status reports configured and active context/slot values plus
  the supported limits.

### Changed

- The software ceiling is raised from 262,144 to 524,288 tokens. The published
  default remains 16,384 tokens and one slot so 8 GB machines still work.
- Version, native binaries, server jar, web console, mobile projects,
  documentation and diagrams updated to 1.0.2.

### Notes

- 512K is effective only when the selected model has a native 512K window
  (Seed-OSS 36B) and the machine has enough RAM; Qwen3 4B stops at 256K. The
  runtime panel keeps the choice but the model architecture remains the
  effective ceiling.

## 1.0.1 — 2026-11-16

### Fixed

- **Context-window overflow.** The managed llama.cpp runtime now starts with a
  configurable context window (`llm.context_size`, default 16384) and an
  explicit slot count (`llm.parallel`, default 1). Requests above the old
  4096-token limit no longer fail with HTTP 400
  `exceed_context_size_error`; an 8,087-token request was verified end-to-end.
  The value is clamped to 2,048–262,144 tokens. Qwen3 4B supports a 256K
  context, but 262,144 tokens needs about 40 GB of RAM for the Q4 model and
  long CPU prefill times, so raise it only on a machine with headroom.

### Changed

- Version, documentation and diagrams updated to 1.0.1.
- `CODEFORGE_CONTEXT_SIZE` and `CODEFORGE_PARALLEL` environment overrides added.
- The managed runtime now passes an explicit `--parallel` slot count.

## 1.0.0 — 2026-11-15

First public release. A complete local-first programming workbench written in
C17, C++17, Java 17 and Python 3.

### Highlights

- **Interruptible generation and live reasoning trace.** The composer turns
  the send key into **INTERJECT** while a turn is running; a second press
  stops the in-flight answer and sends the correction immediately, while an
  empty box turns it into **STOP**. The server exposes
  `GET /api/chat/progress` and `POST /api/chat/cancel`, kills the core process
  on cancellation, and skips memory writes for the aborted turn. During
  generation the bubble shows a stage-by-stage trace (compliance screen,
  memory recall, workspace scan, live research, context assembly, generation,
  output screen), a live token counter and decoding rate read from the local
  runtime, an adaptive remaining-time estimate and a progress bar.
- **Dual-brain routing.** A DeepSeek-compatible online brain is used when a
  key is configured and the network is reachable; the C++ core probes again and
  automatically falls back to local inference on any failure. Every response
  reports the requested route, the actual route and a fallback reason.
- **Persistent memory that survives disconnection.** Append-only JSONL store
  with CJK-bigram + Latin tokenization, SHA-256 deduplication, kind weights,
  recency decay, pruning and a compact context builder. Chat turns, web
  findings, debug outcomes and distilled online lessons are recalled offline.
- **Workspace index.** Project sources are indexed into
  `memory/project-index.jsonl` and injected into chat/debug prompts with real
  paths and excerpts. Secrets, configuration, builds, models and memory are
  excluded.
- **Current-law compliance layer.** 13 non-disableable baseline prohibitions,
  14 jurisdiction packs with official legal portals, network geolocation,
  stale-brief refresh, bilingual refusals, hashed audit log and a
  “guardrail, not legal advice” prompt notice. Screening covers chat, debug and
  one-shot run; a dedicated code-artifact rule closes the “no verb” bypass
  while an explicit defensive/analysis context still allows review work.
  Output screening is narrower than input screening to avoid false positives.
- **Four-language architecture.** A C++17 main core owns orchestration,
  compilation, execution, diagnostics, LLM calls and the agentic debug loop.
  Java 17 serves the REST API, downloads and llama.cpp lifecycle. A C17 helper
  provides SHA-256 and host telemetry. Python 3 provisions the toolchain,
  builds, tests and packages releases.
- **Agentic debug loop.** Compile → run → collect stderr and exit status →
  ask the local model for a full corrected program → re-run, for up to eight
  iterations (default three). Every iteration is recorded with command,
  phase, duration, exit code and captured output.
- **Four execution targets.** Python, C, C++ and Java compile and run inside
  per-session workspaces with process-tree timeouts.
- **12 curated offline models.** A balanced China/Global library including
  Qwen3-Coder 30B-A3B, DeepSeek-Coder V2 Lite, DeepSeek R1 0528 Qwen3 8B,
  Qwen3 4B Instruct, Yi-Coder 9B, CodeGeeX4 ALL 9B, Seed-OSS 36B, GPT-OSS 20B,
  Devstral Small 2507, Phi-4, Gemma 3 27B and Codestral 22B.
- **Managed llama.cpp runtime.** Automatic GitHub release resolution per
  platform and CPU, streaming download with HTTP Range resume and up to 12
  retries, SHA-256 verification, safe ZIP/tar.gz extraction, health-probed
  startup on `127.0.0.1:8080`.
- **Architecture-aware runtime selection.** Windows x64/ARM64, macOS
  arm64/x64, Linux x64/ARM64/s390x, Android ARM64 (generic and Snapdragon) and
  an x64 fallback on Windows ARM64. A source-built `llama-server` can be
  selected with `llm.llama_server` or `CODEFORGE_LLAMA_SERVER` on ABIs without
  an official asset (32-bit ARM/x86, Alpine/musl, iSH).
- **Portable JDK architecture handling.** x64, aarch64, 32-bit x86 and ARM
  detection; Windows ARM64 tries aarch64 then x64; Termux and Alpine use the
  system JDK instead of downloading an incompatible glibc build.
- **Bundled Gradle 8.7 wrapper.** The Android thin client ships `gradlew`,
  `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar`, so
  `cd mobile/android && ./gradlew assembleDebug` works without a separate
  Gradle install. The APK is universal across Android ABIs.
- **Executable-bit-safe release archives.** tar.gz bundles record mode 755 for
  `build.sh` and `gradlew`; ZIP bundles carry Unix mode attributes for the same
  files so extraction on Linux and macOS keeps them runnable.
- **Model-independent local operations.** RUN/PREVIEW/SAVE on every code block,
  an AUTO COMPILE toggle, and a deterministic LOCAL OPS chat layer
  (`/run`, `/save`, `/place`, `/launcher`, `/install`, `/help` plus natural
  language) execute through the local server without calling the model. A 4B
  offline model, a frontier online model and no model at all get the same
  execution, file and placement capabilities.
- **Extended language execution and on-demand runtimes.** JavaScript,
  TypeScript, Go, Rust, PHP, C# and HTML/CSS preview join the C++ core's
  Python/C/C++/Java path. Missing runtimes are downloaded from official
  sources into `native/toolchains/<runtime>` with progress, live bytes and
  automatic environment injection; a run attempts provisioning and succeeds on
  the next run once ready. Go falls back across `go.dev`, `golang.google.cn`,
  Aliyun and USTC mirrors when a source is unreachable. The .NET SDK version is
  resolved from Microsoft's release metadata (currently the .NET 10 channel) and
  installed as a portable official archive; archives of 32 MB or more download
  over up to eight parallel HTTP ranges, resume from a partial file and retry
  short ranges. C# projects target the installed SDK's own major version.
  HTML/CSS previews are served from per-run sessions with a strict CSP sandbox.
- **Arbitrary-path files and placement.** `/api/files/write`,
  `/api/files/write-batch` (one destination per file), `/api/files/reveal`,
  `/api/desktop/install` and `/api/project/place` accept exactly the path the
  user provides. Missing folders return `can_create` confirmation instead of
  being created silently; invalid Windows names, missing drives and a
  destination inside the project are reported explicitly. Launchers are real
  `.lnk`/`.desktop`/`.command` entries; the project copy excludes models,
  runtime, workspace, logs, memory and build caches.
- **Futuristic web console.** Chat, Debug, Models and System views with canvas
  particles, markdown tables, code blocks, live chips and a download queue.
  Identical UI on desktop, Android WebView and iOS WKWebView.
- **Win32 control center.** Native C++ window with live readiness, tray icon,
  auto-start of the Java server, Edge app-mode launch and log access.
- **Mobile paths.** Termux (Android) and iSH (iOS) host route, plus Kotlin and
  Swift thin clients. Full commands in `docs/PLATFORMS.md`.
- **Documentation set.** README, user manual, platform guide, architecture,
  API reference, security model, model library, release notes and explanatory
  diagrams under `docs/images/`.

### Security

- Server binds to `127.0.0.1` only by default.
- Every non-GET request must carry `Content-Type: application/json` (HTTP 415
  otherwise) and, when an `Origin` header is present, it must equal the Host
  origin (HTTP 403 otherwise). This blocks browser-based cross-origin requests
  against the local code-execution API.
- 8 MB request-body ceiling; API rejects unknown routes with a JSON error.
- Model and runtime downloads are size-checked and SHA-256 verified before use.
- Archive extraction rejects path traversal (`..`) and absolute paths.
- No telemetry, no accounts, no analytics. Outbound traffic is limited to
  actions the user enables: Hugging Face model downloads, GitHub release
  metadata/runtime downloads, the JDK bootstrap, web research and current-law
  refreshes while online, and the optional online brain when a key is set.
- Compliance screens both the request and the model output; every decision is
  appended to a hashed `memory/compliance.log`.

### Verification record

Measured on Windows 10 Pro (x64), local inference with Qwen3 4B Instruct 2507
Q4_K_M served by llama.cpp:

| Check | Result |
|---|---|
| `bootstrap.py --all` | JDK, C17 helper, C++ core, Win32 client and Java jar built |
| Self-test (Python/C/C++/Java) | All four languages compiled and executed successfully |
| Short chat completion | Reply returned in ~3.2 s |
| Long markdown-table answer | ~2 minutes, two tables rendered correctly |
| Interjection UI | Long C++ task interrupted mid-generation; the console showed `INTERRUPTED BY YOU · PREVIOUS ANSWER DISCARDED`, then returned a fresh Python answer; the discarded reply was not rendered |
| Live trace and token ETA | Seven stages rendered; local runtime reported `21/2048 tokens @ 8.6 tok/s`; remaining label showed `~2m 29s left`; progress bar never moved backwards |
| Cancellation API | `POST /api/chat/cancel` returned `core_stopped:true`; the in-flight `/api/chat` call returned `cancelled:true` at ~14.4 s; no chat entry was written to memory |
| ETA calibration | A short code request predicted 187 output tokens, the countdown at 29 s showed ~18 s left, and the reply completed at 42.7 s |
| Debug loop, `std::vector::at` overflow | Iteration 1 exit 3 (`std::out_of_range`), AI repaired to a valid index, iteration 2 exit 0 |
| Debug loop, Python syntax error | 2 iterations, repaired and executed, `route=local`, workspace hits recorded |
| Workspace index | 130 files / 364 KB indexed; `debugger` query returned `core/debugger.cpp` first |
| Memory recall offline | Online answer stored as a lesson, then recalled with 3 memory hits while `CODEFORGE_ONLINE=0` |
| Dual-brain fallback | Requested `online` with an unreachable URL → `route=local`, `route_error` naming the failed endpoint |
| Dual-brain online path | Simulated OpenAI-compatible endpoint → `route=online`, distilled `lesson` memory written |
| Compliance block | Prohibited request returned `blocked=true` with a bilingual refusal and audit entry |
| Code submission screening | A keylogger artifact was refused on both `/api/debug` and `/api/run`; sandbox-labelled defensive code still executed |
| Citation discipline | Workspace-context replies cited real relative paths and produced no fabricated repository URLs |
| Jurisdiction detection | `geo:ipwho.is` resolved the country; law packs and official sources injected |
| API guard, missing Content-Type | `415 {"ok":false,"error":"Content-Type must be application/json"}` |
| API guard, foreign Origin | `403 {"ok":false,"error":"Cross-origin request rejected"}` |
| Same-origin POST | `200` with expected JSON |
| Unknown endpoint | `404` JSON `"Unknown endpoint: /api/nope"` |

### Known limitations

- The bundled inference path is **CPU-only**. GPU acceleration requires a
  custom llama.cpp build and manual configuration; it is not managed by the UI.
- A small GGUF model cannot literally out-reason a frontier online model.
  “Smarter offline” is delivered through durable memory, distilled online
  lessons, workspace context, current-law retrieval and the compile/run/debug
  feedback loop — not through raw parameter count.
- Only the lightweight Qwen3 4B model was exercised end-to-end on the
  reference machine. Larger listed models are valid GGUF targets but were not
  downloaded or benchmarked for this release.
- Executed code runs as the current user. There is no OS-level sandbox, so do
  not run untrusted code on a machine you care about.
- The local API has no authentication token. It is safe only while bound to
  loopback. If you expose it through a proxy or tunnel, you own the risk.
- Termux and iSH hosting of the full stack is experimental; the thin-client
  route is the recommended mobile deployment.
- Model licenses differ. DeepSeek, CodeGeeX, Gemma and Codestral weights carry
  non-MIT terms; review `docs/MODELS.md` before commercial use.

### Not included

- No bundled model weights or llama.cpp binaries are shipped in the source
  archive. They are downloaded on demand by the application.
- No accounts, cloud sync, telemetry or remote update service.
