# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.0.4 — 2026-11-16

### Added

- **Selectable CPU or CUDA llama.cpp runtime.** The Models panel now carries a
  Backend selector with **Auto**, **CPU** and **CUDA**. Auto uses the CUDA build
  when `runtime/llama.cpp-cuda` contains a valid `llama-server`; otherwise it
  stays on the managed CPU build. Choosing CUDA starts the server with
  `-ngl 99`, while CPU remains available for machines without an NVIDIA GPU or
  when the GPU build cannot load. The choice is persisted in
  `runtime/selection.json` and exposed through `/api/runtime/select`,
  `runtime_variants` and the `backend` / `backend_selected` status fields.
  Measured on an RTX 4070 12 GB: ~83.6 tok/s with CUDA versus ~4.5 tok/s on
  CPU for Qwen3 4B; a direct `add(a,b)` completion returned code-only in 0.8 s.
- **Action-first short prompts.** Reasoning is capped
  (`--reasoning-budget 256`, template `enable_thinking:false`) so simple
  calculator, command and code requests return executable code immediately
  instead of producing a long deliberation. A capped-reasoning calculator
  request returned code in 4.6 s.
- **Local command agent on every supported platform.** Chat can emit
  `<codeforge_shell>` blocks that the host executes through PowerShell, cmd,
  bash, sh or zsh, with an optional working directory and timeout. The new
  `/api/agent` settings and Command Agent console panel expose tool and
  application discovery, up to 256 tool steps, command/install timeouts,
  automatic tool downloads, online learning and the publishing guard. The
  agent prompt carries installed tools, installed applications and a
  per-platform software control reference, so it can operate office suites,
  browsers, media tools, archives, cloud CLIs and databases as well as
  compilers.
- **Automatic missing-tool provisioning.** When a command is absent, the host
  resolves a portable toolchain first and otherwise uses the native package
  manager (winget on Windows; apt, dnf, yum, pacman, zypper, apk or brew on
  Unix), then pip or npm when mapped; PATH is refreshed, and the command is
  retried once. On this Windows host the
  mapping covers 70 unique tools, including compilers, database clients,
  GitHub/AWS/Azure/Google Cloud CLIs, Kubernetes, media, OCR, document and
  archive utilities. Provisionable names are deduplicated in the API and UI.
  On Windows, yt-dlp prefers pip and falls back to winget with
  `--skip-dependencies`, avoiding its optional ~190 MB FFmpeg build; FFmpeg
  remains a separate tool.
- **Autonomous web tools for the agent.** `<codeforge_search>`,
  `<codeforge_fetch>` and `<codeforge_learn>` let the model search every
  configured source, read a page as text, or research unfamiliar software and
  errors before acting. A genuinely missing simple command also triggers one
  automatic web-research pass whose ranked sources are fed back into the loop.
- **Publishing with an explicit guard.** Local commits, builds, packages and
  exports stay available. `git push`, release/PR/issue creation, package
  registry uploads, container pushes, remote copies, cloud deploys and outbound
  write HTTP methods are blocked until `agent.allow_publish` is enabled or a
  direct API run sets `allow_publish=true` for that action. Wrapped commands
  (`cmd /c`, PowerShell, bash -c) are normalized before matching, and blocked
  calls report `blocked`, `permission_required` and `publish_guard`.
- **Long-loop context compaction.** Before each model call the agent keeps the
  leading system prompt and the newest tool results within the feedback
  budget, inserts a compaction marker when older output is dropped, and returns
  accumulated `tool_calls` even when the model call fails. A 16-command loop
  now completes without the former "No usable AI backend" failure.
- **Reply limit corrected to 393,216 tokens.** This is the largest value
  accepted by the active DeepSeek endpoint; the shared setting, console input
  and documentation had advertised 524,288, which returned HTTP 400. The
  configurable context window remains up to 524,288 tokens.

### Changed

- **Parallel multi-source web research.** Google, Baidu, Baidu Zhidao,
  DuckDuckGo, Bing, 360 Search and Wikipedia are queried at the same time.
  Unreachable, blocked or captcha-walled sources are skipped instead of
  blocking the request, and are retried after a ten-minute cooldown.
  Results are deduplicated by URL, filtered for query overlap, and ranked by
  relevance, source trust, cross-source agreement and executable snippets.
  The strongest candidate is marked as `BEST CANDIDATE` in the prompt and in
  the research step, so the local model compares the available answers
  instead of trusting a single engine.
- **Streamlined request handling.** Chat, debug and run now go directly from
  the selected brain to the executor. The auxiliary review service, its
  configuration keys, status fields and console panel are gone, and the core
  system prompts were cleaned accordingly.
- **Release artifacts are built from a sanitized tree.** The packager blanks
  both API key fields in the shipped `config.json` and excludes `.prebuild`,
  `.prebuild.exe` and `.pre-stream-bak` leftovers, so no local secret or build
  scratch file can enter an archive.
- Documentation, diagrams and console copy were refreshed to match: the
  manual, API reference, architecture notes, model guide and rendered diagrams
  now describe CPU/CUDA selection instead of CPU-only inference. Five
  remaining v1.0.3 screenshots (debug, model library, control center and two
  mobile views) were regenerated at 1.0.4, and the native C helper now reports
  version 1.0.4.

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
  generation the bubble shows a stage-by-stage trace (memory recall,
  workspace scan, live research, context assembly, generation, response
  rendering), a live token counter and decoding rate read from the local
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
  metadata/runtime downloads, the JDK bootstrap, web research, and the
  optional online brain when a key is set.

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
| Citation discipline | Workspace-context replies cited real relative paths and produced no fabricated repository URLs |
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
