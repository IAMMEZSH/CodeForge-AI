# CodeForge AI — Architecture

Version 1.0.7 · C17 · C++17 · Java 17 · Python 3.13

This document describes how the system is put together, why the work is split
across four languages, and exactly what happens on disk and on the wire for
every major operation.

---

## 1. Design goals

| Goal | Consequence |
| --- | --- |
| Fully offline inference | GGUF models run on llama.cpp; no cloud API is required at any point |
| Local-first privacy | Server binds to `127.0.0.1`; no telemetry or accounts; the online brain is opt-in |
| Real debugging, not chat theatre | The loop actually compiles, executes, reads diagnostics, patches and re-runs |
| Five desktop/mobile platforms | Windows, Linux, macOS share one POSIX build; Android/iOS get Termux/iSH plus native thin clients |
| Small trusted core | The C/C++ core is dependency-light and can be rebuilt from source with one script |
| Inspectable behavior | Every round is persisted as source files plus `report.json`; every download is hashed |

**Non-goals:** multi-user hosting, remote code execution as a service,
fine-tuning, GPU cluster orchestration, or replacing an IDE. CodeForge AI is a
local workbench and a reference implementation.

---

## 2. Why four languages

The split is functional, not cosmetic. Each language owns the layer where it
is the least risky choice.

| Layer | Language | Responsibility | Why |
| --- | --- | --- | --- |
| Native helper | C17 | SHA-256, file hashing, verification, host sysinfo | No runtime, tiny binary, identical behavior on every OS, usable from Java and scripts |
| Main core | C++17 | LLM protocol, prompt construction, toolchain detection, compilation, sandboxed execution, debug loop | Deterministic control over processes, timeouts, encodings, and job objects without a VM |
| Control server | Java 17 | HTTP API, model registry, resumable downloads, ZIP/tar.gz extraction, runtime supervision | Mature standard-library HTTP server, robust streams, simple deployment on one JAR |
| Auxiliary core | Python 3.13 | Bootstrap, build orchestration, packaging, self-test driver, mock LLM | Glue code changes often; Python keeps the build script readable and portable |
| Desktop shell | C++17 (Win32) | Tray icon, control center window, Edge app-mode launch | Native window with zero frameworks; launches the console like a product, not a dev server |
| Web console | HTML/CSS/JS | Chat, debug, models, environment UI | No build step, works on desktop and mobile browsers unmodified |

The C++ core never performs network downloads for models — that belongs to the
Java server, which has reliable streaming and hash handling. The Java server
never compiles or runs user code — that belongs to the C++ core, which can
supervise and kill process trees.

---

## 3. Component map

```
┌──────────────────────────────────────────────────────────────────────────┐
│ Desktop shell  client/main.cpp (Win32, 921 lines)                        │
│  tray icon · control center · start/stop server · open console           │
└───────────────┬──────────────────────────────────────────────────────────┘
                │ spawns / HTTP
┌───────────────▼──────────────────────────────────────────────────────────┐
│ Web console  server-java/web/console (app.js 1293 · styles.css 1043)     │
│  Chat · Debug · Models · Environment · hash routing · canvas particles   │
└───────────────┬──────────────────────────────────────────────────────────┘
                │ REST /api/*
┌───────────────▼──────────────────────────────────────────────────────────┐
│ Java control server  com.sun.net.httpserver on 127.0.0.1:8765           │
│  ApiHandler · ModelManager · RuntimeManager · MemoryStore · ProjectIndex │
│  OnlineService · AgentShell · ToolProvisioner · KeyStore · AppSettings   │
│  CoreBridge                                                              │
└───┬──────────────┬──────────────┬──────────────┬────────────────────────┘
    │ JSON command │ HTTPS        │ HTTPS        │ local files
┌───▼───────────┐ ┌▼────────────┐ ┌▼───────────┐ ┌▼─────────────────────┐
│ C++ main core │ │ DeepSeek /  │ │ SearXNG ·  │ │ memory/ · workspace/ │
│ CodeForgeCore │ │ OpenAI-     │ │ DuckDuckGo │ │ logs/ · models/      │
│ status chat   │ │ compatible  │ │ 360 · Wiki │ │ (persistent brain,   │
│ debug run     │ │ online LLM  │ │ 360 · Wiki │ │ index)               │
│ selftest      │ │ (optional)  │ │            │ └──────────────────────┘
└──┬──────────┬─┘ └─────────────┘ └────────────┘
   │ HTTP     │ CreateProcess / fork+exec
┌──▼────────┐ ┌▼─────────────────────────────────────────────────────────┐
│ llama-    │ │ gcc · g++ · python · javac · java                       │
│ server    │ │ user snippets, timeouts, process-tree kill              │
│ :8080     │ └────────────────────────────────────────────────────────┘
└───────────┘
       ▲
┌──────┴──────────────────────┐
│ C native helper             │
│ cforge_native hash/verify/  │
│ sysinfo (called by scripts  │
│ and available to the core)  │
└─────────────────────────────┘
```

Directory-level layout:

```
CodeForge-AI/
├── core/                 C++17 main core
│   ├── main.cpp          CLI entry, JSON command dispatch, status/selftest
│   ├── llm.cpp/.hpp      backend detection, OpenAI/Ollama clients, prompts
│   ├── debugger.cpp      repair loop, code-block extraction, report writing
│   ├── runners.cpp       language detection, compiler invocation, execution
│   ├── process.cpp       Win32 Job Objects / POSIX fork+exec+poll supervisor
│   ├── http.cpp          WinHTTP (Windows) / libcurl (POSIX)
│   ├── config.cpp        config.json + environment overrides
│   ├── util.cpp          UTF-8/wide conversion, file IO, time, truncation
│   ├── json.hpp          hand-written JSON parser/writer
│   └── prompts.hpp       system prompt and repair instruction templates
├── native-c/             C17 helper: sha256.c, native_tool.c
├── server-java/          Java 17 control server + web console
│   ├── src/codeforge/server/
│   └── web/console/
├── client/main.cpp       Win32 desktop shell
├── aux-python/           bootstrap.py, mock_llm.py
├── mobile/android/       Kotlin WebView thin client (Gradle)
├── mobile/ios/           SwiftUI/WKWebView thin client (XcodeGen)
├── docs/                 manuals, API, images
├── models/               registry + downloads (GGUF)
├── runtime/              llama.cpp runtime + runtime.json
├── workspace/            generated debug/run sessions
├── memory/               persistent brain (created at first use)
│   ├── memories.jsonl    chat lessons, facts, preferences, web notes
│   ├── project-index.jsonl  workspace file index for retrieval
├── logs/                 server, core, llama-server logs
├── config.json           user configuration
└── CMakeLists.txt        cross-platform build graph
```

The `memory/` directory is user data, not product data. It is excluded from
`.gitignore` and from release packaging: publishing a build must never ship
anyone's private lessons, indexed file names, or audit history.

---

## 4. Runtime topology

There are at most five processes in steady state:

| Process | Port | Lifetime | Owner |
| --- | --- | --- | --- |
| `CodeForge.exe` (Windows shell) | — | until tray exit | user |
| `java -jar CodeForgeServer.jar` | 8765 | until shutdown/exit | shell or user |
| `CodeForgeCore` | — | one per request, milliseconds to minutes | Java server |
| `llama-server` | 8080 | until stop/shutdown | Java RuntimeManager |
| compiler / user program | — | bounded by timeouts | C++ core |

The core is deliberately **not** a daemon. Each command is a fresh process with
a fresh view of `config.json`. This eliminates long-lived state corruption,
makes crashes self-contained, and means a core rebuild never requires a server
restart. The cost — process startup and config parsing per request — is on the
order of milliseconds.

Model and runtime downloads run on daemon threads inside the JVM, wrapped in a
`Job` object that is polled by the UI. Inference is a separate process so that a
model crash cannot take down the API server.

---

## 5. Request flows

### 5.1 Chat (dual brain)

```
console → POST /api/chat {messages}
        → ApiHandler reads JSON (8 MB cap)
        → MemoryStore.search(text, retrieval_limit)  → ranked past lessons (default 5)
        → ProjectIndex.search(text, 3)               → relevant workspace files
        → OnlineService.research(text)               → web findings when warranted
        → context = web findings + memory hits + workspace snippets
        → route = online if configured and reachable else local
        → CoreBridge spawns: CodeForgeCore chat --root <root>
             stdin: {"messages":[...],"route":"online|local","context":"..."}
             stdout: {"ok":true,"reply":"...","route":"online|local",
                      "route_error":"...","backend":"openai","model":"..."}
        → core prepends the CodeForge system prompt (keeps last 40 messages)
        → online: POST <llm.online.base_url>/v1/chat/completions
          local:  POST http://127.0.0.1:8080/v1/chat/completions
        → core parses the OpenAI response, prints one JSON line
        → Java stores the turn in memory.jsonl
             local turn  → kind "chat"
             online turn → kind "lesson" (weight 1.3)
        → Java returns reply + meta {brain, memory, workspace, online}
        → console renders Markdown plus provenance chips and sources
```

While the turn is in flight the console polls
`GET /api/chat/progress?request_id=…` every 700 ms. `ApiHandler` keeps a
`ChatProgress` record per request with a stage list
(`memory → workspace → research → context → generate → verify`), the
generation start time, predicted output tokens, and an adaptive estimate. While
the local runtime decodes, the progress handler reads
`GET http://127.0.0.1:8080/slots` at most once per second to obtain the real
generated-token count, token cap and context size; after two samples the
remaining time extrapolates from the measured tokens-per-second instead of a
fixed heuristic. The same record feeds the token counter and the remaining-time
label. Progress never moves backwards and stops at 96% until the answer lands.

`POST /api/chat/cancel {request_id}` sets the request's `AtomicBoolean` and
calls `CoreBridge.cancel`, which kills the core process for that id. The
handler checks the flag at every stage boundary and immediately after the core
returns; a cancelled turn skips memory writes and returns
`{ok:false, cancelled:true}`. This is cooperative cancellation before and after
generation, and process termination during generation. Killing the core closes
its HTTP connection to llama-server, which aborts decoding and frees the single
slot for the next request.

Timeout: 900 s at the HTTP layer; `config.json → llm.request_timeout_ms`
(600 000 ms default) at the client layer. Online failures are not fatal: the
core retries on the local backend and reports `route_error`, so the chat still
answers while the machine is offline.

The dual brain is two different jobs, not two copies of the same model:

| Brain | Job | Mechanism |
| --- | --- | --- |
| Online (DeepSeek-class) | Broadest world knowledge, fresh facts, hard reasoning | Large hosted model and web research |
| Offline (local GGUF) | Project-specific intelligence that survives disconnection | Persistent memory, online lesson distillation, workspace index, compile/run feedback |

“Offline is smarter” is deliberately defined as *better informed about this
machine and this user over time*, retrieved into a small model's context. It
does not mean a 4B local model matches a hosted trillion-parameter model on
open-domain reasoning; no amount of memory changes parameter count. What it
does mean: an online lesson written to `memory.jsonl` is still recalled with
the network unplugged, and the offline loop can actually compile and fix code
instead of only talking about it.


Backend detection order (`llm.cpp`):

1. Explicit `llm.backend` if not `auto`.
2. OpenAI-compatible probe: `/v1/models` on the configured `openai_url`
   (8080 by default) — covers llama.cpp, LM Studio, vLLM, and Ollama's OpenAI
   shim.
3. Ollama native API on `ollama_url` (11434).
4. `mock` only when explicitly configured (development/testing).

An unavailable backend produces `ok:false` with a human-readable error; the
core never fabricates a reply.

### 5.2 The debug loop

```
POST /api/debug {code, filename, language, iterations, model, route}
  → timeout = 240 + iterations×300 seconds
  → ApiHandler searches the workspace index for filename + language
  → CodeForgeCore debug (route is passed through like chat)
      1. language detect (extension hint → syntax heuristics → default cpp)
      2. session dir = workspace/debug-<stamp>-<token>/
         write <filename>.<ext>
      3. compile if compiled language (60 s cap)
      4. execute with stdin=/dev/null, stdout/stderr captured (20 s cap)
      5. if exit 0 → success, persist report.json, return
      6. if failure:
           build repair prompt = system + instructions + current code
                               + compiler/runtime diagnostics + prior rounds
           ask the LLM for a single fenced code block
           extract the best block (language match first, then largest)
           write <filename>.v<n>.<ext>
           go to step 3
      7. after the budget: success=false, final_code = last attempt
```

Every round is recorded in `iterations[]` with `index`, `phase`, `ok`,
`exit_code`, `duration_ms`, `command`, `source_file`, `output`. `report.json`
persists the same record so a UI or CI job can audit the loop after the fact.

The extractor is intentionally conservative: it rejects `diff`/`patch` blocks,
prefers blocks whose info string matches the target language, prefers blocks
containing a `main`/`class`/`def` shape when there is no info string, and falls
back to the largest block. This prevents a model from replacing working code
with a diff it cannot apply.

### 5.3 One-shot run

`POST /api/run` uses the same runner without the LLM. It is the primitive used
by the debug loop and by the self-test, and it is what you want for quick
syntax checks. Response includes the full command line, exit code, duration,
stdout, stderr, and the session directory.

### 5.4 Model download

```
POST /api/models/download {id}
  → ModelManager.startModelDownload
      create Job(status=queued), return immediately
      daemon thread:
        resolving   → GET hf-mirror.com/api/models/<repo>?blobs=true
                      (huggingface.co fallback) find exact file,
                      read size + lfs.sha256
        downloading → HTTPS GET with Range resume
                      write <id>/<file>.part
                      update Job bytes/percent/speed every chunk
                      on IOException: retry up to 12 times, resume at current size
        verifying   → SHA-256 compare against the upstream LFS hash
        installing  → atomic move .part → .gguf
                      update models/models.json
        complete
```

Downloads are anonymous and follow redirects (the mirror may hand off to a
CDN). The default source is selected by `llm.model_mirror` /
`CODEFORGE_MODEL_MIRROR`; `https://huggingface.co` is always the final fallback.
The hash comparison happens after the full byte count is confirmed, so a
truncated transfer can never be installed. `models/models.json` is the source of
truth for installed models; the registry itself is compiled into the server.

### 5.5 Runtime install and start

```
POST /api/runtime/install
  → resolve latest llama.cpp release for this OS/arch
  → download ZIP/tar.gz (progress in the same Job system)
  → extract only the runtime directory
  → verify llama-server binary SHA-256
  → write runtime/runtime.json {version, platform, server, size, sha256}

POST /api/runtime/start {model_id}
  → validate runtime + model exist
  → refuse if 8080 is already healthy (prevents double-start)
  → ProcessBuilder:
       llama-server -m <model> --host 127.0.0.1 --port 8080
                    -c <llm.context_size> --parallel <llm.parallel>
                    -t <max(2, cores/2)> --no-webui
  → redirect stdout+stderr to logs/llama-server.log
  → poll GET /v1/models until healthy (180 s cap)
  → on timeout: kill, report tail of the log
```

The managed process is stopped on `/api/runtime/stop` and on server shutdown.
An external server on 8080 is detected via the health probe and reported but
never killed.

### 5.6 Persistent brain and workspace index

Two background services turn a stateless chat endpoint into something that
accumulates context. Both keep their state under `memory/`, both are optional
in `config.json`, and both fail open (a broken store never blocks a reply).

```
MemoryStore.add(text, kind, source)
  → normalize kind (fact · preference · lesson · web · chat · note)
  → SHA-256 dedupe: identical normalized text replaces instead of appending
  → append JSONL line to memory/memories.jsonl

MemoryStore.search(query, k)
  → tokenize (CJK bigrams + lowercase Latin tokens)
  → score = token hits × IDF, × kind weight, + recency boost
              fact 1.3 · lesson 1.3 · preference 1.2 · web 1.1 · note 1.0 · chat 0.9
              recency = 0.4 × exp(-age_days / 90)
  → prune to memory.max_entries (4 000 by default) at load time

ProjectIndex.reindex()
  → walk the project root, skip secrets/config/build/models/memory/logs
  → store path, language, size, first 4 000 characters in project-index.jsonl
  → 130 files / ≈365 KB on the reference checkout
```

The index is rebuilt asynchronously at server start and on demand
(`POST /api/workspace`); it is a retrieval aid, not a second copy of the
repository. Model downloads never enter the index, so a 2.5 GB GGUF cannot
pollute search results.

### 5.7 Command agent loop

```
user turn
  → ApiHandler builds memory/workspace/research context
  → AgentShell.agentPrompt injects platform, shells, tools, applications,
    software control reference, web tools and publishing policy
  → model returns exactly one tool block
      shell  → AgentShell.run
                 → ToolProvisioner.ensureCommand when the command is missing
                 → package manager / portable toolchain, PATH refresh
                 → publish guard, bounded process tree, stdout/stderr logs
                 → retry once after provisioning
      search → OnlineService.research (all sources in parallel, ranked)
      fetch  → OnlineService.fetchPage (bounded readable text)
      learn  → research + official-doc fetch, returned as one lesson
  → real result is appended as a user message
  → compactMessages keeps system + newest results within max_feedback_chars
  → repeat until the model answers without a block or max_steps is reached
  → tool_calls, provisioning, learning evidence and blocked flags are returned
```

The agent is deliberately model-agnostic: an online or offline model only
emits the same tags. All discovery, installation, execution and protection
happen in Java, so a small local model cannot lose the execution capability.
The publish guard rejects remote/public actions unless `agent.allow_publish`
is true; it is a policy layer, not an OS sandbox, because the process runs
with the user's privileges.

---

## 6. Process supervision

This is the part that keeps an AI that executes code from hanging the machine.

### Windows

- `CreateJobObjectW` with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`.
- `CreateProcessW` suspended → assign to job → resume.
- stdout/stderr redirected to temporary files (avoids pipe deadlocks).
- stdin redirected to `NUL`.
- `WaitForSingleObject` with the configured timeout; on timeout
  `TerminateJobObject(124)` kills the entire tree, including grandchildren.
- Output read back with a hard byte cap and a truncation marker.

### POSIX (Linux, macOS, Android/Termux, iOS/iSH)

- `pipe2`/`pipe` for stdout and stderr, `/dev/null` for stdin.
- `fork` + `setpgid(0, 0)` creates a process group per run.
- Both pipes set non-blocking; a `poll` loop with a deadline drains them.
- On deadline: `kill(-pid, SIGKILL)` kills the group, then `waitpid`.
- Exit code is 124 on timeout, 128+signal otherwise.
- A byte cap is applied while draining; further output is discarded after the
  marker is appended.

### Timeouts

| Stage | Default | Config key |
| --- | --- | --- |
| Compile | 60 000 ms | `debug.compile_timeout_ms` |
| Run | 20 000 ms | `debug.run_timeout_ms` |
| Repair rounds | 3 | `debug.max_iterations` |
| LLM request | 600 000 ms | `llm.request_timeout_ms` |
| Runtime readiness probe | 180 000 ms | fixed in RuntimeManager |
| Download attempt | streaming, 12 retries | fixed in ModelManager |

---

## 7. Data on disk

| Path | Producer | Contents | Safe to delete? |
| --- | --- | --- | --- |
| `config.json` | user | Backend, timeouts (server port is a CLI flag) | No (regenerated with defaults) |
| `models/models.json` | ModelManager | Installed model index with hashes | Rebuilt from disk scan on next start |
| `models/downloads/<id>/*.gguf` | ModelManager | Model weights | Yes, re-downloadable |
| `models/downloads/<id>/*.part` | ModelManager | In-progress transfer | Yes, but loses resume progress |
| `runtime/runtime.json` | ModelManager | Runtime metadata | Reinstall runtime |
| `runtime/llama.cpp/` | ModelManager | Extracted binaries | Reinstall runtime |
| `workspace/debug-*/` | debugger | Every code version + `report.json` | Yes; useful for audits |
| `workspace/run-*/` | runner | Source, binary, build artifacts | Yes |
| `memory/memories.jsonl` | MemoryStore | Chat lessons, facts, preferences, web notes | Yes, but the offline brain forgets everything |
| `memory/project-index.jsonl` | ProjectIndex | File paths, language, size, first 4 000 chars | Yes; rebuilt on demand |
| `logs/llama-server.log` | RuntimeManager | Inference server output | Yes |
| `logs/server.log` | ServerMain | Java server log | Yes |
| `logs/core-*.out/.err` | CoreBridge | Last core stdout/stderr | Yes |

The core never writes outside the project root. Paths in API responses are
root-relative where possible; absolute paths are returned only for debugging
convenience (`server_absolute`, `model_path`). `memory/` is treated as private
user data by packaging: it is listed in `.gitignore` and excluded from every
release artifact.

---

## 8. Cross-platform strategy

### 8.1 Compile-time split

Platform differences are confined to four files, all guarded with `#ifdef
_WIN32` / `#else`:

| Concern | Windows | POSIX |
| --- | --- | --- |
| HTTP client | WinHTTP | libcurl |
| Process | `CreateProcessW` + Job Objects | `fork`/`exec` + process group + `poll` |
| Paths and UTF-8 | `MultiByteToWideChar`, `_wfopen` | UTF-8 natively; manual UTF-8↔`wchar_t` for macOS APIs |
| Sysinfo | `GetSystemInfo`, `GlobalMemoryStatusEx`, `GetDiskFreeSpaceEx` | `sysinfo` (Linux/Android), `sysctl` (macOS), `statvfs`, `uname` |
| Executable path | `GetModuleFileNameW` | `/proc/self/exe` (Linux), `_NSGetExecutablePath` (macOS) |
| Local time | `localtime_s` | `localtime_r` |

`runners.cpp` also switches tool names (`javac.exe` vs `javac`, `program.exe`
vs `program`) and PATH lookup (strip `.exe`, split on `;` vs `:`).

### 8.2 Build graph

`CMakeLists.txt` produces:

| Target | Source | Output |
| --- | --- | --- |
| `cforge_native` | `native-c/*.c` | `native/bin/cforge_native[.exe]` |
| `CodeForgeCore` | `core/*.cpp` | `native/bin/CodeForgeCore[.exe]` |
| `CodeForge` (Win32 only) | `client/main.cpp` + core http/util | `native/bin/CodeForge.exe` |

`aux-python/bootstrap.py` is the recommended entry point. It:

1. finds or downloads a portable Temurin JDK 17 (system JDK 17+ preferred);
2. locates a C and C++ compiler (MinGW on Windows, cc/c++ on POSIX);
3. builds the native helper, the core, and the Win32 shell;
4. compiles Java sources and packages `server-java/build/CodeForgeServer.jar`;
5. optionally runs the self-test and generates a `release/` bundle.

`build.sh` is the thin CMake wrapper for POSIX users who prefer native tooling.

### 8.3 Mobile targets

Mobile devices run the **same** server and core; only the client changes.

| Device | Route | Client | Server location |
| --- | --- | --- | --- |
| Android | Termux | browser at `127.0.0.1:8765` | on device (Termux aarch64 build) |
| Android | APK | Kotlin WebView thin client | on a PC/server, reached over LAN |
| iOS | iSH | browser/curl in Alpine | on device (limited by iSH emulation) |
| iOS | Xcode | SwiftUI WKWebView thin client | on a Mac/PC, reached over LAN |
| macOS | native | browser + core build | on device |

The thin clients are intentionally dumb: one WebView, one URL setting, no
embedded logic. This keeps Android/iOS code small and means the UI stays
identical to desktop.

---

## 9. Concurrency model

| Component | Model |
| --- | --- |
| Java HTTP server | One acceptor plus a cached thread pool (`HttpServer` default) |
| Model downloads | One daemon thread per job; `Job` guarded by `synchronized` |
| Runtime manager | `synchronized` start/stop; health probe on the request thread |
| Core status cache | 8-second cache, refreshed by a 20-second background thread |
| C++ core | Single-threaded per request; blocking HTTP and process wait |
| Chrome/Edge canvas | requestAnimationFrame particle field |

No shared mutable state crosses the core boundary: each core process is
single-use. Within Java, all mutable state (`installed` map, `jobs` map,
`process`, `cachedStatus`) is guarded by the object monitor, and long
operations (download, start, probe) never hold the monitor while doing network
IO except where explicitly bounded.

---

## 10. Failure containment

| Failure | Containment | User-visible result |
| --- | --- | --- |
| LLM server dies mid-chat | Core sees a transport error, exits 0 with `ok:false` | Error bubble with recovery hint |
| Model crashes while loading | RuntimeManager startup probe times out, kills process | `ok:false`, log tail |
| User code infinite-loops | Process supervisor kills the tree at the timeout | `timed_out:true`, `exit_code:124` |
| User code forks children | Job object / process group kills descendants | No orphan processes |
| Download connection drops | 12 retries with HTTP Range resume | Job message shows resume percentage |
| Download corrupted | SHA-256 mismatch → job error, `.part` retained for retry | No bad model installed |
| Core binary missing | CoreBridge reports unavailable; API returns error | Build hint in the UI |
| Config malformed | Defaults used, `config_error` recorded | Status shows defaults |
| Console asset missing | 404 JSON | Page still serves other assets |
| Server crash | llama-server may keep running; next start detects 8080 | External-server notice |

---

## 11. Security architecture

CodeForge AI executes arbitrary code by design. The architecture therefore
assumes a **local trust boundary** and does not pretend to be a sandbox.

- HTTP binds to `127.0.0.1` only.
- No authentication because no remote exposure is intended. Do not put the port
  on a public interface. If remote access is needed, tunnel it
  (`ssh -L 8765:127.0.0.1:8765`) and add an authenticating proxy.
- Compilation and execution run as the current user — the same privilege as a
  terminal. Process-tree kill is a resource control, not a security boundary.
- The command agent adds a publishing guard for `git push`, release creation,
  registry/container pushes, remote copies, cloud deploys and outbound write
  HTTP methods. It is a policy layer and is off by default; it does not stop a
  locally executed program from reaching the network on its own.
- Tool provisioning downloads binaries from the native package manager or a
  portable source, refreshes PATH and retries. Installed application names and
  command paths are only exposed on loopback, but the agent prompt can send
  that inventory to the configured online model when online routing is active.
- Auto-learning treats web pages as untrusted text; the agent is instructed
  not to follow page instructions, but no content filter makes third-party text
  trustworthy.
- No telemetry, no analytics, no auto-update pings. Outbound requests happen
  only for: model downloads (domestic mirror with Hugging Face fallback),
  runtime downloads (GitHub releases), the optional online LLM you configure,
  opt-in web research when `online.enabled` is true, and explicit tool
  provisioning or agent web tools.
- The online brain is opt-in: an empty `llm.online.api_key` keeps every prompt
  on the local model. When configured, chat text plus the injected memory and
  workspace context leave the machine for that provider — treat it exactly like
  pasting the same context into that vendor's web UI.
- The native helper's SHA-256 is used for integrity verification of downloads,
  not for signatures. Supply-chain trust comes from TLS plus the pinned
  repository file names in the registry.
- The owner/admin concept and credential rules of the broader TenSaint73OS
  project are **not** part of CodeForge AI; this project has no admin account.

---

## 12. Performance characteristics

Measured on the development machine (16-core x64 Windows, RTX 4070 12 GB,
Qwen3 4B Instruct 2507 Q4_K_M, 2.5 GB):

| Operation | Observed |
| --- | --- |
| Core `version` command | < 50 ms |
| Status (cached) | < 5 ms |
| Chat, short reply (CPU) | 3.2 s |
| Chat, ~1 000 token table answer (CPU) | ~1 min 56 s |
| Chat, short reply (CUDA) | 1.2 s |
| Decoding rate (CUDA, 4B Q4) | ~83.6 tok/s |
| Decoding rate (CPU, 4B Q4) | ~4.5 tok/s |
| Direct `add(a,b)` completion (CUDA) | code-only in 0.8 s |
| Calculator request, capped reasoning (CUDA) | code in 4.6 s |
| Debug loop, Python ZeroDivisionError, 2 rounds | 8.9 s |
| Debug loop, C++ out-of-range `at()`, 2 rounds | ~19 s including model thinking |
| Self-test (C, C++, Python, Java) | ~2 s after toolchain warm-up |
| Model download 2.5 GB | network-bound; resumed across one drop |

Inference speed dominates everything else. On CPU the control plane is not the
bottleneck: JSON, process spawn, and file IO are all sub-100 ms. On CUDA the
GPU build decodes roughly 18× faster for the same 4B model, so the runtime
backend — not the HTTP layer — determines perceived latency. The backend is
selected in the runtime panel (Auto/CPU/CUDA) and recorded in
`runtime/selection.json`; CUDA adds `-ngl 99`, while both backends cap
reasoning with `--reasoning-budget 256`.

Memory: server ~150–250 MB JVM, llama-server ~3–5 GB for a 4B Q4 model
depending on context, core ~10 MB per invocation.

---

## 13. Extensibility

### Add a model

1. Add an entry to the registry in `ModelManager.java` (id, repo, file, size,
   RAM, license, description).
2. Add a matching row to `docs/MODELS.md`.
3. `GET /api/models/resolve?id=<id>` to confirm upstream metadata.
4. Download through the UI or API; the size and hash are verified automatically.

### Add a language to the runner

1. Extend `detect_language` and `run_code` in `core/runners.cpp`.
2. Add a toolchain probe in `core/main.cpp` (`detect_toolchains`).
3. Add a self-test in `command_selftest`.
4. Document the compiler requirements in `docs/PLATFORMS.md`.

### Add an LLM backend

1. Add a probe and request builder in `core/llm.cpp`.
2. Return `BackendInfo{available, kind, base_url, model, error}`.
3. Map the response into `LlmReply{ok, content, backend, model, error}`.
4. Add a config key if the endpoint is customizable.

### Add a mobile client

The client only needs to load `http://<server>:8765/console/` in a WebView
with JavaScript and DOM storage enabled. See `mobile/android` and `mobile/ios`
for the two reference implementations.

---

## 14. Source map

| File | Lines | Role |
| --- | ---: | --- |
| `core/main.cpp` | 512 | CLI entry, command dispatch, status, self-test |
| `core/debugger.cpp` | 358 | Repair loop, block extraction, route, report |
| `core/runners.cpp` | 488 | Detection, compilers, execution, toolchains |
| `core/process.cpp` | 446 | Win32/POSIX process supervision |
| `core/llm.cpp` | 274 | Backend detection, HTTP clients, prompts |
| `core/http.cpp` | 273 | WinHTTP / libcurl |
| `core/util.cpp` | 292 | Encoding, file IO, formatting |
| `core/config.cpp` | 163 | Config load + defaults + env overrides |
| `core/json.hpp` | 443 | JSON parser/writer |
| `native-c/native_tool.c` | 300 | Hash/verify/sysinfo CLI |
| `native-c/sha256.c` | 241 | SHA-256 implementation |
| `server-java/.../ModelManager.java` | 1176 | Registry, downloads, extraction, runtime files |
| `server-java/.../ApiHandler.java` | 2438 | Routes, chat/agent/debug orchestration, progress/cancel, static serving |
| `server-java/.../AgentShell.java` | 1211 | Cross-platform command execution, app discovery, software reference, publish guard |
| `server-java/.../ToolProvisioner.java` | 1436 | Portable toolchains, package-manager mappings, PATH refresh, tool discovery |
| `server-java/.../KeyStore.java` | 740 | Provider vault, active key mirroring, agent settings, token limit |
| `server-java/.../OnlineService.java` | 1208 | Parallel search, page fetch, relevance filter, result persistence |
| `server-java/.../MemoryStore.java` | 496 | Tokenizer, scoring, dedupe, pruning |
| `server-java/.../ProjectIndex.java` | 325 | Workspace scan, search, context builder |
| `server-java/.../RuntimeManager.java` | 296 | llama-server lifecycle |
| `server-java/.../AppSettings.java` | 262 | Typed config + environment |
| `server-java/.../CoreBridge.java` | 194 | Core spawn + JSON pipe + per-request cancellation |
| `server-java/.../NativeTools.java` | 175 | Native helper bridge |
| `server-java/.../ServerMain.java` | 183 | Server bootstrap, shutdown |
| `server-java/.../Json.java` | 343 | Java JSON |
| `client/main.cpp` | 921 | Win32 shell |
| `server-java/web/console/app.js` | 1293 | UI logic, live trace, interjection, provenance chips |
| `server-java/web/console/styles.css` | 1043 | UI styling |
| `aux-python/bootstrap.py` | 383 | Build orchestration |
| `aux-python/mock_llm.py` | 282 | Development backend |

---

## 15. Known limitations

- CPU inference is slow for long answers; a 30B MoE needs ~24 GB RAM to be
  pleasant. Choose a model that fits the machine (`docs/MODELS.md`).
- The Java server is single-node. It is not a multi-user service and has no
  authentication.
- The Android APK and iOS app are not prebuilt in this repository. They are
  complete source projects that require Android Studio/SDK or Xcode on a
  suitable host. Termux and iSH routes work without those toolchains.
- Runtime asset resolution for Windows x64/ARM64, macOS arm64/x64, Linux
  x64/ARM64/s390x and Android ARM64 under Termux is covered by
  architecture-mapping tests against the upstream release asset list; the
  full download/verify/start path is exercised on Windows x64, and the POSIX
  build is compile-validated. Confirm the selected asset with
  `GET /api/status → runtime.platform`.
- There is no GPU configuration UI. `llama-server` runs with CPU defaults; edit
  the command in `RuntimeManager` or run your own server for GPU builds.
- Model downloads trust the configured mirror/Hugging Face TLS and pinned file
  names; there is no independent signature layer.
- Memory retrieval is lexical (CJK bigrams and Latin tokens), not embedding
  search. It is fast, dependency-free and predictable, but a paraphrase with no
  shared token will miss. `memory/project-index.jsonl` can be deleted to reset
  retrieval without touching the lessons.
- The “smart offline” claim means accumulated context, not extra parameters.
  A 4B local model is not DeepSeek-V3 on open-domain reasoning; it becomes
  substantially better at *your* project and *your* recurring fixes, and it
  keeps working with the network unplugged.
- Online research depends on public search engines; rate limits or an
  unreachable SearXNG instance degrade to DuckDuckGo/360/Wikipedia. An empty or
  irrelevant result set is rejected rather than injected into the prompt.
- There is no multi-process memory lock. Two server instances pointed at the
  same root would append to the same JSONL; run one server per checkout.

---

## 16. Related documents

- [API reference](API.md) — every endpoint and response shape
- [Model library](MODELS.md) — the 12-model registry and selection guide
- [Platform guide](PLATFORMS.md) — every command for every OS
- [Manual](MANUAL.md) — end-to-end operator handbook
- [Security](SECURITY.md) — threat model and hardening checklist
