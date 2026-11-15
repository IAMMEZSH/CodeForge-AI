# CodeForge AI — API Reference

Version 1.0.2 · Java 17 HTTP server · C++ core bridge

CodeForge AI exposes a single local REST API on `http://127.0.0.1:8765`.
There is no account and no telemetry. Every endpoint is served by the Java
control server, which layers memory, workspace retrieval, optional online
research, jurisdiction compliance and dual-brain routing on top of the C++
main core.

```
Browser / curl / mobile thin client
        │  HTTP 1.1, JSON
        ▼
Java 17 server  ──  memory · workspace index · online research
 127.0.0.1:8765  ──  compliance · dual-brain routing
        │  CodeForgeCore <command> --root <root>  (stdin JSON → stdout JSON)
        ▼
C++17 main core  ──  runner, debugger, LLM client, process supervisor
        │  OpenAI-compatible HTTP
        ├── llama-server 127.0.0.1:8080  (local GGUF brain)
        └── DeepSeek-compatible API      (optional online brain, auto-fallback)
```

---

## 1. Conventions

| Item | Value |
| --- | --- |
| Base URL | `http://127.0.0.1:8765` |
| Transport | HTTP/1.1, loopback only |
| Content type | `application/json; charset=utf-8` |
| Request body limit | 8 MB (`413` style rejection with `ok:false`) |
| Success marker | Every JSON response contains a boolean `ok` |
| Error shape | `{"ok": false, "error": "<message>"}` |
| Static console | `GET /console/` (also `/` and `/console`) |
| Timeouts | chat 900 s · debug `240 + iterations×300` s · run 180 s · selftest 300 s · status 60 s |
| Bind address | `127.0.0.1` only — the server is not reachable from other machines unless a tunnel/proxy is added deliberately |

HTTP status codes: `200` for all handled API responses (including logical
errors — always check `ok`), `403` cross-origin write, `404` unknown route,
`405` wrong method on static files, `415` write without
`Content-Type: application/json`, `500` uncaught server exception. `POST`-only
endpoints answer a logical error when called with `GET`.

Every write request must send `Content-Type: application/json` and, when an
`Origin` header is present, it must match the request `Host`. This blocks
malicious web pages from driving the local code-execution API with simple
cross-site form posts.

### PowerShell note

PowerShell aliases `curl` to `Invoke-WebRequest`. Use `curl.exe` or
`Invoke-RestMethod` in the examples below.

```powershell
curl.exe -s http://127.0.0.1:8765/api/health
Invoke-RestMethod http://127.0.0.1:8765/api/health
```

### Pretty-print helper (optional)

```bash
python -m json.tool           # Linux / macOS / Termux / iSH
python -c "import sys,json;print(json.dumps(json.load(sys.stdin),indent=2))"
```

---

## 2. Endpoint map

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Liveness, version, uptime |
| GET | `/api/status` | Full server + core + toolchain + model snapshot |
| POST | `/api/chat` | Chat completion through the local model |
| GET | `/api/chat/progress` | Live stage trace and remaining-time estimate |
| POST | `/api/chat/cancel` | Interrupt an in-flight chat and free the model |
| POST | `/api/debug` | Multi-round compile → run → repair loop |
| POST | `/api/run` | Compile and run one snippet once |
| GET | `/api/languages` | Runtime and language readiness, active install jobs |
| POST | `/api/languages/install` | Download and provision a language runtime |
| POST | `/api/files/write` | Write one file to an exact user-provided path |
| POST | `/api/files/write-batch` | Write many files, each to its own path |
| POST | `/api/files/reveal` | Open the containing folder in the OS file manager |
| POST | `/api/desktop/install` | Create a launcher at any folder or file path |
| POST | `/api/project/place` | Copy the runnable project tree to any folder |
| GET | `/preview/<session>/<file>` | Sandboxed preview of generated HTML/CSS |
| POST | `/api/selftest` | Toolchain and smoke-test suite |
| GET | `/api/models` | Model registry, install state, runtime state, jobs |
| POST | `/api/models/download` | Start a background model download |
| GET | `/api/models/resolve` | Resolve upstream file URL and SHA-256 |
| POST | `/api/runtime/install` | Download and extract llama.cpp runtime |
| POST | `/api/runtime/start` | Start llama-server with an installed model |
| POST | `/api/runtime/stop` | Stop the managed llama-server |
| GET | `/api/runtime/logs` | Tail the inference server log |
| GET | `/api/downloads` | Download job list |
| GET/POST | `/api/memory` | Memory stats/search or add an entry |
| POST | `/api/memory/delete`, `/api/memory/clear` | Delete one entry or clear the store |
| GET/POST | `/api/workspace` | Workspace index stats/search or reindex |
| GET | `/api/online` | Network, geolocation, research and legal-brief status |
| GET | `/api/online/research` | Web research persisted into memory |
| GET | `/api/compliance` | Jurisdiction pack, baseline rules, sources |
| POST | `/api/compliance/refresh` | Refresh the current legal brief |
| POST | `/api/shutdown` | Graceful server shutdown |
| GET | `/console/` | Web console (static assets) |

---

## 3. Health

### GET `/api/health`

Fast liveness probe. Does not touch the C++ core, the model, or the disk.

```bash
curl -s http://127.0.0.1:8765/api/health
```

```json
{
  "ok": true,
  "service": "codeforge-java-server",
  "version": "1.0.2",
  "uptime_seconds": 872,
  "platform": "Windows 11",
  "java": "17.0.21"
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `service` | string | Always `codeforge-java-server` |
| `uptime_seconds` | number | Seconds since server start |
| `platform` | string | `os.name` of the JVM |
| `java` | string | JVM version |

Use this for container/port checks; use `/api/status` for real capability data.

---

## 4. Status

### GET `/api/status`

The single source of truth. Returns the server identity, the C++ core status
(cached for 8 seconds), native tool output, llama-server runtime state, model
registry summary, all download jobs, and runtime file metadata.

```bash
curl -s http://127.0.0.1:8765/api/status
```

Top-level keys:

| Key | Shape | Notes |
| --- | --- | --- |
| `ok` | bool | Always `true` when the server answers |
| `server` | object | `version`, `root`, `platform`, `arch`, `java`, `uptime_seconds` |
| `core` | object | Full C++ `status` response (see below) |
| `native` | object | SHA-256/sysinfo output from the C native helper |
| `llm_runtime` | object | `RuntimeManager.status()` |
| `models` | object | `{ok, registry[], installed_count}` |
| `downloads` | array | Download job snapshots sorted by `started_at` |
| `runtime` | object | `runtime/runtime.json` plus `ok`, `installed`, `available`, `arch`, `server_absolute`, and `configured_server` when an override is configured |

#### `core` object

```json
{
  "ok": true,
  "component": "main-core-cpp",
  "version": "1.0.2",
  "root": "C:\\Users\\you\\Desktop\\CodeForge-AI",
  "llm": {
    "available": true,
    "kind": "openai",
    "base_url": "http://127.0.0.1:8080/v1",
    "model": "C:\\...\\Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
    "models": ["C:\\...\\Qwen3-4B-Instruct-2507-Q4_K_M.gguf"],
    "error": ""
  },
  "debug": { "compile_timeout_ms": 60000, "max_iterations": 3, "run_timeout_ms": 20000 },
  "toolchains": {
    "gcc":  { "available": true, "name": "C compiler",   "path": "...", "version": "..." },
    "gxx":  { "available": true, "name": "C++ compiler", "path": "...", "version": "..." },
    "javac":{ "available": true, "name": "javac",        "path": "...", "version": "..." },
    "java": { "available": true, "name": "java",         "path": "...", "version": "..." },
    "python":{ "available": true, "name": "Python",      "path": "...", "version": "..." },
    "jdk_source": "C:\\...\\native\\jdk\\jdk-17.0.21+9"
  }
}
```

`llm.kind` is `openai` when any OpenAI-compatible endpoint is reachable
(managed llama-server, external llama.cpp, LM Studio, Ollama with an OpenAI
shim), `mock` only in explicit development mode, and `none` when nothing is
available. `llm.error` is empty on success.

#### `native` object

```json
{
  "ok": true,
  "cpu_logical": 32,
  "cpu_arch": "x64",
  "page_size": 4096,
  "mem_total_mb": 65363,
  "mem_avail_mb": 46102,
  "disk_free_mb": 743512,
  "disk_total_mb": 952704
}
```

The native helper reports the same numbers as `cforge_native sysinfo`.
`mem_avail_mb` and the disk values are snapshots taken at request time.

#### `llm_runtime` object

```json
{
  "ok": true,
  "running": true,
  "managed": true,
  "healthy": true,
  "pid": 23124,
  "port": 8080,
  "base_url": "http://127.0.0.1:8080",
  "model_id": "qwen3-4b-instruct-2507",
  "model_path": "C:\\...\\Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
  "started_at": "2026-11-15T11:19:49.048Z",
  "log": "logs\\llama-server.log",
  "error": ""
}
```

| Field | Meaning |
| --- | --- |
| `running` | Managed process alive **or** a healthy server answers on 8080 |
| `managed` | The server was started by CodeForge AI |
| `healthy` | `GET /v1/models` on 8080 answered within the probe timeout |
| `pid` | OS process id, `0` when not managed |
| `model_id`, `model_path` | Active model when managed |
| `error` | Last startup/probe error, empty on success |

#### `runtime` object

`runtime/runtime.json` after a successful install:

```json
{
  "version": "b12412",
  "platform": "win-cpu-x64",
  "arch": "amd64",
  "server": "runtime\\llama.cpp\\llama-server.exe",
  "size": 25139524,
  "sha256": "6781ee6a...824755a",
  "archive": "llama-b12412-bin-win-cpu-x64.zip",
  "installed_at": "2026-11-15T12:46:03.270Z",
  "ok": true,
  "installed": true,
  "available": true,
  "server_absolute": "C:\\...\\runtime\\llama.cpp\\llama-server.exe"
}
```

`available` is `true` when a managed runtime or a valid
`llm.llama_server`/`CODEFORGE_LLAMA_SERVER` override exists. When no managed
runtime is installed: `{"ok": false, "installed": false, "platform": "...",
"arch": "..."}`; with an override it also carries
`"available": true` and `"configured_server": "<path>"`.

---

## 5. Chat

### POST `/api/chat`

Sends an OpenAI-style message array through the dual-brain router. The server
screens the request against the compliance baseline, detects the jurisdiction,
searches persistent memory and the workspace index, optionally researches the
web, composes a bounded context block, and calls the C++ core. The core asks the
online brain first when the server requested `online`, then falls back to the
local backend automatically.

Request:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `messages` | array | yes | Each item `{role, content}`; roles `system`, `user`, `assistant` |
| `model` | string | no | Override the configured model name |
| `temperature` | number | no | Backend default when omitted |
| `max_tokens` | number | no | Backend default when omitted |
| `jurisdiction` | string | no | Force a pack (`CN`, `US`, `EU`, …) for this request |
| `request_id` | string | no | Caller-chosen id (≤ 80 chars) used by the progress and cancel endpoints; generated when omitted |

```bash
curl.exe -s -X POST http://127.0.0.1:8765/api/chat ^
  -H "Content-Type: application/json" ^
  -d "{\"messages\":[{\"role\":\"user\",\"content\":\"Explain the difference between a process and a thread with one C++ example.\"}]}"
```

PowerShell with a here-string:

```powershell
$body = @{
  messages = @(
    @{ role = "user"; content = "Explain the difference between a process and a thread." }
  )
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8765/api/chat `
  -ContentType "application/json" -Body $body
```

Bash / Termux / iSH:

```bash
curl -s -X POST http://127.0.0.1:8765/api/chat \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Write a C++20 thread pool skeleton."}]}'
```

Response:

```json
{
  "ok": true,
  "reply": "A process owns an isolated address space...",
  "backend": "openai",
  "model": "deepseek-chat",
  "route": "online",
  "jurisdiction": "CN",
  "sources": [
    { "title": "Official source title", "url": "https://example.gov/..." }
  ],
  "meta": {
    "jurisdiction": "CN",
    "jurisdiction_source": "geo:ipwho.is",
    "verdict": "allow",
    "flags": [],
    "memory": { "enabled": true, "entries": 42, "hits": 3 },
    "workspace": { "enabled": true, "files": 130, "hits": 2 },
    "brain": {
      "requested": "online",
      "route": "online",
      "online_configured": true,
      "online_model": "deepseek-chat",
      "online_url": "https://api.deepseek.com",
      "route_error": ""
    },
    "online": { "used": true, "online": true, "source": "360-search", "results": 3, "error": "" },
    "context_chars": 5432,
    "elapsed_ms": 2210
  }
}
```

| Field | Meaning |
| --- | --- |
| `reply` | Assistant message text (Markdown allowed) |
| `backend` | Backend family actually used (`openai`, `ollama`, ...) |
| `model` | Model actually used |
| `route` | `online` or `local` after fallback |
| `jurisdiction` | Detected or forced pack code |
| `sources` | Web sources used for the answer |
| `meta.brain` | Requested route, actual route, configuration and fallback reason |
| `meta.memory` | Store state and retrieval hit count |
| `meta.workspace` | Index size and hit count |
| `meta.online` | Whether research ran, the search engine and result count |
| `meta.verdict` | `allow`, `caution` or `block` |
| `meta.estimated_ms` | Total-duration estimate when generation started |
| `request_id` | Id that ties the response to progress and cancel calls |

A blocked request returns HTTP 200 with:

```json
{
  "ok": false,
  "blocked": true,
  "reply": "This request cannot be carried out...",
  "error": "Blocked by the universal compliance rule",
  "jurisdiction": "CN",
  "sources": [],
  "meta": { "verdict": "block", "flags": ["malware"] }
}
```

The console renders the refusal as a guardrail message rather than a network
error. The same screening runs on model output with a narrower instructional
pattern, so a model cannot be used to launder a prohibited request.

Error example (no model loaded):

```json
{
  "ok": false,
  "error": "No local model backend is available. Download a model and start the runtime, or install Ollama and configure config.json."
}
```

Common causes: `POST /api/runtime/start` not called, port 8080 occupied by an
unhealthy process, model file deleted after installation. Check
`GET /api/status` → `llm.available` and `llm.error`.

Timeout: 900 seconds (long generations on CPU are allowed).

### GET `/api/chat/progress`

Returns the live stage trace and remaining-time estimate for an in-flight chat.

```bash
curl -s "http://127.0.0.1:8765/api/chat/progress?request_id=my-request-1"
```

```json
{
  "ok": true,
  "active": true,
  "request_id": "my-request-1",
  "phase": "generate",
  "route": "local",
  "elapsed_ms": 30200,
  "estimated_ms": 46200,
  "remaining_ms": 16000,
  "percent": 65,
  "generation": {
    "tokens": 173,
    "predicted": 420,
    "max_tokens": 2048,
    "tokens_per_second": 8.6,
    "context_tokens": 2214,
    "measured": true
  },
  "steps": [
    { "id": "screen", "label": "Compliance screen", "detail": "allow · CN", "status": "done", "at_ms": 8 },
    { "id": "memory", "label": "Recall persistent memory", "detail": "4 hits / 11 stored", "status": "done", "at_ms": 41 },
    { "id": "workspace", "label": "Scan workspace index", "detail": "3 matches / 638 files", "status": "done", "at_ms": 58 },
    { "id": "research", "label": "Research current sources", "detail": "360-search · 3 results", "status": "done", "at_ms": 7030 },
    { "id": "context", "label": "Assemble context", "detail": "7061 chars", "status": "done", "at_ms": 7040 },
    { "id": "generate", "label": "Generate answer", "detail": "173 tokens · 8.6 tok/s · on-device model", "status": "active", "at_ms": 7050 }
  ]
}
```

While the local runtime is decoding, the server reads llama-server's `/slots`
telemetry (at most about once per second) and reports real generated tokens,
the configured token cap, the measured decoding rate and the processed context
size. `generation.measured` is `false` before the first two samples, when only
the heuristic estimate is available; `max_tokens` is `0` when unknown. For the
online route, `generation` stays zeroed because the hosted API does not expose
slot telemetry.

`remaining_ms` is an **estimate**. Before tokens are measurable it combines an
answer-shape heuristic (short answer, code snippet, or full project) with the
cost of reading the assembled context and a rolling average of this machine's
decoding rate. Once at least two token samples exist, it extrapolates from the
measured tokens-per-second and keeps a minimum runway so the bar cannot reach
100% before the model stops. `percent` never moves backwards and is capped at
96 until the response actually completes. When an estimate runs out the console
shows `over estimate · still running` rather than a false countdown. When no
chat with that id is active the endpoint returns
`{"ok": true, "active": false}`. Polling every 500–1000 ms is expected; each
call is cheap and only queries the local runtime for slot counters.

### POST `/api/chat/cancel`

Interrupts an in-flight chat and frees the local backend immediately.

```bash
curl -s -X POST http://127.0.0.1:8765/api/chat/cancel \
  -H "Content-Type: application/json" \
  -d '{"request_id":"my-request-1"}'
```

```json
{ "ok": true, "request_id": "my-request-1", "pending": true, "core_stopped": true }
```

The server sets the request's cancel flag, kills the C++ core process that is
streaming from the model, and returns `pending: true` while the handler unwinds.
The original `/api/chat` call then returns promptly with:

```json
{
  "ok": false,
  "cancelled": true,
  "reply": "",
  "error": "Interrupted by user",
  "request_id": "my-request-1",
  "jurisdiction": "CN",
  "meta": { "verdict": "allow", "elapsed_ms": 14438, "cancelled": true }
}
```

A cancelled turn is **not** written to persistent memory: no `chat` entry and
no online `lesson` are stored. Web results fetched before the interruption may
already have been captured as `web` memory by the research stage; those are
ordinary knowledge entries, not a record of the cancelled exchange.

Cancellation is cooperative at stage boundaries (compliance, memory, research,
context assembly, output screening) and immediate during model generation,
where the core process is terminated. If the id is unknown the endpoint still
returns `ok: true` so clients can fire it defensively.

---

## 6. Debug

### POST `/api/debug`

Runs the signature CodeForge loop:

```
1. detect language (or accept explicit language)
2. write source into workspace/debug-<stamp>-<token>/
3. compile → run
4. if it fails, send code + diagnostics + history to the LLM
5. extract the repaired code block, write .v2, .v3, ...
6. repeat until success or the iteration budget is exhausted
7. persist report.json and final source
```

Request:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `code` | string | yes | Source text, max 200 KB |
| `filename` | string | no | Base name without extension, default `main` |
| `language` | string | no | `auto`, `c`, `cpp`, `python`, `java`; default `auto` |
| `iterations` | number | no | Repair rounds, default from `config.json`, clamped 1–8 |
| `model` | string | no | Override model |

```bash
curl.exe -s -X POST http://127.0.0.1:8765/api/debug ^
  -H "Content-Type: application/json" ^
  -d "{\"code\":\"def average(values):\\n    total = 0\\n    for v in values:\\n        total += v\\n    return total / len(values)\\n\\nprint(average([]))\\n\",\"filename\":\"average\",\"language\":\"python\",\"iterations\":2}"
```

```bash
curl -s -X POST http://127.0.0.1:8765/api/debug \
  -H "Content-Type: application/json" \
  -d '{"code":"#include <iostream>\nint main(){int* p=nullptr;*p=1;}\n","filename":"crash","language":"cpp","iterations":2}'
```

Response:

```json
{
  "ok": true,
  "success": true,
  "error": "",
  "final_code": "def average(values):\n    if not values:\n        return 0\n    return sum(values) / len(values)\n\nprint(average([]))\n",
  "session_dir": "workspace/debug-20261115-192027-73257df6",
  "backend": "openai",
  "model": "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
  "iterations": [
    {
      "index": 1,
      "phase": "run",
      "ok": false,
      "timed_out": false,
      "exit_code": 1,
      "duration_ms": 78,
      "command": "python.exe -X utf8 -u ...\\average.py",
      "source_file": "average.py",
      "output": "stderr:\nTraceback ... ZeroDivisionError: division by zero\n"
    },
    {
      "index": 2,
      "phase": "run",
      "ok": true,
      "timed_out": false,
      "exit_code": 0,
      "duration_ms": 71,
      "command": "python.exe -X utf8 -u ...\\average.v2.py",
      "source_file": "average.v2.py",
      "output": "0\n"
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `ok` | `true` when the final round reproduced successfully |
| `success` | Same as `ok` for debug runs |
| `final_code` | Last code version written (fixed code when successful) |
| `session_dir` | Workspace directory containing sources and `report.json` |
| `backend`, `model` | LLM used for repair |
| `iterations[]` | One record per round; `index` starts at 1 |
| `iterations[].phase` | `compile` or `run` |
| `iterations[].output` | Combined stdout/stderr, truncated at the configured cap |
| `error` | Populated when the loop could not run (no toolchain, no model) |

`report.json` inside `session_dir` persists the same data minus `ok` and
`session_dir`.

Server timeout: `240 + iterations × 300` seconds. With `iterations = 2` that is
840 seconds. Increase the client timeout accordingly (curl `--max-time`).

---

## 7. Run

### POST `/api/run`

Compile (if needed) and execute one snippet once. No repair loop, no LLM.

Request:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `code` | string | yes | Source text |
| `filename` | string | no | Base name, default `main` |
| `language` | string | no | `auto`, `c`, `cpp`, `python`, `java`, `javascript`, `typescript`, `go`, `rust`, `php`, `csharp`, `html`, `css` |
| `css` | string | no | Stylesheet paired with an HTML block for previews |

Requests for `javascript`, `typescript`, `go`, `rust`, `php`, `csharp`, `html`
and `css` are handled by the Java runner with the same response contract; the
C/C++/Python/Java path stays in the C++ core. If the required runtime is not
installed, the Java runner starts the matching toolchain download in the
background and returns `ok:false` with a `toolchain` object describing the
provisioning state. A later run succeeds once it is ready.

HTML and CSS requests are rendered in a session folder and additionally return
`preview_url` (for example `/preview/run-20261115-223046-f091cedf/index.html`).
The preview route serves only that session folder with
`Content-Security-Policy: sandbox allow-scripts allow-forms allow-modals
allow-popups allow-pointer-lock` and `X-Content-Type-Options: nosniff`.

```bash
curl -s -X POST http://127.0.0.1:8765/api/run \
  -H "Content-Type: application/json" \
  -d '{"code":"import java.util.*;\npublic class Main{public static void main(String[] a){System.out.println(new Date(0));}}\n","filename":"Main","language":"java"}'
```

Response:

```json
{
  "ok": true,
  "success": true,
  "phase": "run",
  "language": "python",
  "source_file": "hello.py",
  "command": "python.exe -X utf8 -u workspace\\run-...\\hello.py",
  "exit_code": 0,
  "timed_out": false,
  "duration_ms": 42,
  "stdout": "hello\n",
  "stderr": "",
  "error": "",
  "jurisdiction": "CN",
  "session_dir": "workspace\\run-20261115-193000-1a2b3c4d"
}
```

Behavior:

- `ok`/`success` are true when the process exits `0` within the run timeout
  (20 s default from `config.json`).
- `phase` is `compile` when compilation failed (then `stderr` holds compiler
  diagnostics), `run` when execution happened.
- `timed_out: true` means the process tree was killed; `exit_code` is `124` on
  timeout on all platforms.
- `session_dir` keeps the source and build artifacts for inspection.
- The source is screened by the compliance layer before execution. A refusal
  returns `200` with `ok:false`, `blocked:true`, `notice` and `jurisdiction`,
  and no process is started.

---

## 8. Self-test

### POST `/api/selftest`

Verifies the toolchains and the whole compile/run path. Body is ignored; send
`{}`.

```bash
curl -s -X POST http://127.0.0.1:8765/api/selftest \
  -H "Content-Type: application/json" -d "{}"
```

```json
{
  "ok": true,
  "component": "main-core-cpp",
  "version": "1.0.2",
  "checks": [
    { "name": "c", "ok": true, "detail": "c-ok", "duration_ms": 320 },
    { "name": "cpp", "ok": true, "detail": "cpp-ok", "duration_ms": 410 },
    { "name": "python", "ok": true, "detail": "python-ok", "duration_ms": 55 },
    { "name": "java", "ok": true, "detail": "java-ok", "duration_ms": 780 }
  ],
  "toolchains": { "gcc": { "...": "..." } }
}
```

`checks[].ok` is false for missing toolchains with a diagnostic in `detail`.
Server timeout: 300 seconds.

---

## 9. Models

### GET `/api/models`

Merged view of the registry (12 curated GGUF models), install state, job
list, and runtime information.

| Key | Shape |
| --- | --- |
| `ok` | `true` |
| `registry` | array of model entries |
| `installed_count` | number |
| `runtime` | same object as `/api/status` → `runtime` |
| `downloads` | same array as `/api/downloads` → `jobs` |
| `llm_runtime` | same object as `/api/status` → `llm_runtime` |

Model entry:

```json
{
  "id": "qwen3-4b-instruct-2507",
  "name": "Qwen3 4B Instruct 2507",
  "vendor": "Alibaba Qwen",
  "origin": "China",
  "category": "Lightweight",
  "params": "4B",
  "context": "256K",
  "license": "Apache-2.0",
  "repo": "unsloth/Qwen3-4B-Instruct-2507-GGUF",
  "file": "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
  "size_display": "≈ 2.5 GB",
  "min_ram_gb": 6,
  "recommended": false,
  "description": "Fast lightweight generalist ...",
  "installed": true,
  "installed_info": {
    "id": "qwen3-4b-instruct-2507",
    "name": "Qwen3 4B Instruct 2507",
    "repo": "unsloth/Qwen3-4B-Instruct-2507-GGUF",
    "file": "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
    "path": "models\\downloads\\qwen3-4b-instruct-2507\\Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
    "bytes": 2497281120,
    "sha256": "3605803b...c67e597",
    "downloaded_at": "2026-11-15T11:18:45.566Z"
  }
}
```

`installed_info` appears only when `installed` is true. `installed` also
requires the file to still exist on disk.

```bash
curl -s http://127.0.0.1:8765/api/models | python -m json.tool
curl -s http://127.0.0.1:8765/api/models | jq '.registry[] | {id, size_display, installed}'
```

### POST `/api/models/download`

Starts a background download job. The downloader resolves the upstream file
from Hugging Face metadata, validates the expected size and SHA-256, resumes
with HTTP `Range` after connection drops (up to 12 attempts), and only then
moves the `.part` file into `models/downloads/<id>/`.

Request:

| Field | Type | Required |
| --- | --- | --- |
| `id` | string | yes, registry id |

```bash
curl -s -X POST http://127.0.0.1:8765/api/models/download \
  -H "Content-Type: application/json" -d '{"id":"qwen3-4b-instruct-2507"}'
```

Accepted:

```json
{ "ok": true, "job": { "id": "qwen3-4b-instruct-2507", "kind": "model", "status": "queued", "...": "..." } }
```

Rejected:

```json
{ "ok": false, "error": "This model is already downloaded and installed." }
{ "ok": false, "error": "This model is already downloading or verifying." }
{ "ok": false, "error": "Unknown model: <id>" }
```

The endpoint returns immediately; poll `GET /api/downloads` for progress.
Typical transfer is 2–20 GB — plan disk space accordingly.

### GET `/api/models/resolve?id=<id>`

Resolves the live upstream metadata without downloading.

```bash
curl -s "http://127.0.0.1:8765/api/models/resolve?id=deepseek-r1-0528-qwen3-8b"
```

```json
{
  "ok": true,
  "id": "deepseek-r1-0528-qwen3-8b",
  "repo": "unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF",
  "file": "DeepSeek-R1-0528-Qwen3-8B-Q4_K_M.gguf",
  "url": "https://huggingface.co/.../resolve/main/...gguf",
  "size": 5012345678,
  "sha256": "…",
  "installed": false
}
```

SHA-256 resolution may query the Hugging Face LFS pointer when the tree API
does not expose it. If resolution fails: `{"ok": false, "error": "..."}`.

---

## 10. Runner management

### POST `/api/runtime/install`

Downloads the matching llama.cpp release for the host CPU and OS:
`win-cpu-x64`, `win-cpu-arm64`, `macos-arm64`, `macos-x64`, `ubuntu-x64`,
`ubuntu-arm64`, `ubuntu-s390x`, `android-arm64` or
`android-arm64-snapdragon` (Termux ARM64). It extracts `llama-server`, writes
`runtime/runtime.json`, and verifies the binary SHA-256.

When no asset exists for the ABI (32-bit ARM/x86, Alpine/musl, iSH), build
llama.cpp from source and set `llm.llama_server` in `config.json` or export
`CODEFORGE_LLAMA_SERVER` before starting the server. The override takes
priority over the managed runtime.

```bash
curl -s -X POST http://127.0.0.1:8765/api/runtime/install \
  -H "Content-Type: application/json" -d "{}"
```

```json
{ "ok": true, "job": { "id": "llama-runtime", "kind": "runtime", "status": "queued" } }
```

An active install is rejected with `{"ok": false, "error": "..."}`.
Progress appears in `GET /api/downloads` under the runtime job id.
Installation requires network access to GitHub releases (office/home firewall
rules apply).

### POST `/api/runtime/start`

Starts the managed inference server on `127.0.0.1:8080`.

Request:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `model_id` | string | no | Installed model id; when empty, the first installed model is used |
| `context_size` | integer | no | Context window in tokens; default `llm.context_size`, clamped to 2,048–524,288 |
| `parallel` | integer | no | Concurrent slots; default `llm.parallel`, clamped to 1–8. The total context is divided across slots |

```bash
curl -s -X POST http://127.0.0.1:8765/api/runtime/start \
  -H "Content-Type: application/json" \
  -d '{"model_id":"qwen3-4b-instruct-2507","context_size":32768,"parallel":1}'
```

```json
{
  "ok": true,
  "message": "Inference service is ready.",
  "model_id": "qwen3-4b-instruct-2507",
  "status": { "running": true, "healthy": true, "pid": 23124, "...": "..." }
}
```

Failure cases (all `200` with `ok:false`):

| Situation | `error` |
| --- | --- |
| Already running (managed) | `Inference service is already running.` plus `status` |
| Port 8080 used by an external process | `Another inference service is already listening on port 8080.` |
| Runtime not installed | `llama.cpp runtime is not installed. Install it from the Models tab, or set llm.llama_server in config.json to an existing llama-server binary.` |
| No model installed | `No GGUF model is installed. Download a model first.` |
| Model missing on disk | `Model file does not exist: <id>` |
| Startup timeout (180 s) | `... did not become ready within 180 seconds...` plus `log` and `tail` |

The server is started with `--host 127.0.0.1 --port 8080 -c <context_size>
--parallel <parallel> -t <half the cores> --no-webui`. Defaults are 16,384
tokens and one slot; both values are clamped to 2,048–524,288 and 1–8.
llama.cpp divides the total context across the slots. The runtime panel sends
the same fields; the model's native context remains the effective ceiling.
Logs are appended to `logs/llama-server.log`.

### POST `/api/runtime/stop`

Stops the managed process tree. External servers are not touched.

```bash
curl -s -X POST http://127.0.0.1:8765/api/runtime/stop \
  -H "Content-Type: application/json" -d "{}"
```

```json
{ "ok": true, "message": "Inference service stopped." }
```

When nothing is managed but an external server answers on 8080:

```json
{ "ok": true, "message": "The external inference service is not managed by CodeForge and cannot be stopped.", "external": true }
```

### GET `/api/runtime/logs?lines=120`

Return the tail of `logs/llama-server.log` as one text block.

| Query | Default | Range |
| --- | --- | --- |
| `lines` | 120 | clamped 10–400 |

```bash
curl -s "http://127.0.0.1:8765/api/runtime/logs?lines=40"
```

```json
{
  "ok": true,
  "path": "logs/llama-server.log",
  "text": "main: server is listening on http://127.0.0.1:8080 ..."
}
```

When no log exists: `{"ok": false, "error": "No inference server log yet."}`.

---

## 11. Downloads

### GET `/api/downloads`

```bash
curl -s http://127.0.0.1:8765/api/downloads
```

```json
{
  "ok": true,
  "jobs": [
    {
      "id": "qwen3-4b-instruct-2507",
      "kind": "model",
      "model_id": "qwen3-4b-instruct-2507",
      "model_name": "Qwen3 4B Instruct 2507",
      "status": "complete",
      "message": "Download complete, SHA-256 verified.",
      "downloaded": 2497281120,
      "total": 2497281120,
      "percent": 100.0,
      "speed": 0,
      "started_at": 1794740913675,
      "updated_at": 1794741525569
    }
  ]
}
```

Job fields:

| Field | Meaning |
| --- | --- |
| `id` | Model id or `llama-runtime` |
| `kind` | `model` or `runtime` |
| `status` | `queued`, `resolving`, `downloading`, `verifying`, `extracting`, `complete`, `error` |
| `message` | Human-readable progress, includes resume attempts |
| `downloaded`, `total` | Bytes |
| `percent` | `0.0`–`100.0` |
| `speed` | Bytes per second (0 for non-download states) |
| `started_at`, `updated_at` | Epoch milliseconds |

Jobs live in memory; completed jobs are retained until the server restarts.

---

## 12. Memory, workspace, research and compliance

### GET / POST `/api/memory`

```bash
curl -s "http://127.0.0.1:8765/api/memory?q=debugger&limit=5"
curl -s -X POST http://127.0.0.1:8765/api/memory \
  -H "Content-Type: application/json" \
  -d '{"text":"Prefer C++17 structured bindings.","kind":"preference","source":"manual"}'
```

`GET` without `q` returns `stats()` plus `recent`; with `q` it returns ranked
`hits` with `id`, `kind`, `source`, `jurisdiction`, `ts`, `score` and `text`.
`POST` accepts `text`, optional `kind` (`fact`, `preference`, `web`, `chat`,
`lesson`, `note`), `source` and `jurisdiction`.

`POST /api/memory/delete` deletes one entry by `id`; `POST /api/memory/clear`
empties the store. Both are explicit user actions; there is no automatic
deletion of recent knowledge.

### GET / POST `/api/workspace`

```bash
curl -s "http://127.0.0.1:8765/api/workspace?q=compliance&limit=3"
curl -s -X POST http://127.0.0.1:8765/api/workspace \
  -H "Content-Type: application/json" -d "{}"
```

`GET` returns index stats when `q` is empty, otherwise `hits` with `path`,
`excerpt`, `updated_at` and `score`. `POST` rescans the project tree and
rewrites `memory/project-index.jsonl`. Secret files, `config.json`, builds,
models, runtime, logs, workspaces and the memory directory are skipped.

### GET `/api/online`

Returns network state, geolocation, research settings, last research source
and cached legal briefs. `?probe=0` skips the live network/geo probe.

### GET `/api/online/research?q=<query>`

Runs the search chain (optional SearXNG → DuckDuckGo → 360 Search → Wikipedia),
filters results for token relevance, persists each result as a `web` memory and
returns `results` plus a bounded `context` block. When offline it returns
`"online": false` with an error and does not overwrite memory.

### GET `/api/compliance?jurisdiction=<code>`

Returns the resolved pack (`jurisdiction`, `name`, `country`, `legal_system`,
`summary`), the 13 baseline categories, jurisdiction topics, official source
URLs, refresh policy and the “guardrail, not legal advice” notice. The
`jurisdiction_source` field is `request`, `config`, `geo:<provider>`, `locale`
or `default`.

### POST `/api/compliance/refresh`

Forces a synchronous refresh of the current legal brief from the official
portals in the pack. Returns the same shape as the refresh pipeline:
`jurisdiction`, `source`, `fetched_at`, `context`, `ok` and `error`.

All four subsystems are controlled by `memory`, `online`, `compliance` and
`llm.online` in `config.json`; environment overrides are listed in
[MANUAL.md](MANUAL.md#108-environment-variables).

---

## 13. Shutdown

### POST `/api/shutdown`

Graceful stop: replies first, then stops llama-server and exits the JVM.

```bash
curl -s -X POST http://127.0.0.1:8765/api/shutdown \
  -H "Content-Type: application/json" -d "{}"
```

```json
{ "ok": true, "message": "Server is shutting down." }
```

The desktop control center uses this to close everything. Give the process a
few seconds; the port is released after the JVM exits.

---

## 14. Static console

| Path | Result |
| --- | --- |
| `/` | `console/index.html` |
| `/console/` | `console/index.html` |
| `/console/<asset>` | File under the project `console/` directory |
| Paths containing `..` | `400 {"ok": false, "error": "Illegal path"}` |
| Missing file | `404` JSON |

Only `GET` is accepted for static assets.

---

## 15. C++ core CLI

The Java server spawns the core per request:

```bash
native/bin/CodeForgeCore <status|chat|debug|run|selftest|version> --root <project_root>
```

- Request JSON is written to `stdin` (UTF-8, one object).
- Response JSON is written to `stdout` (UTF-8, one object, single line).
- Exit code `0` for logical success and for handled logical errors; `1` for
  internal exceptions.
- On Windows the executable is `CodeForgeCore.exe`.

### `version`

```bash
native/bin/CodeForgeCore version --root .
```

```json
{ "ok": true, "component": "main-core-cpp", "version": "1.0.2", "root": "C:\\...\\CodeForge-AI" }
```

### `status`

```bash
echo "{}" | native/bin/CodeForgeCore status --root .
```

Same object as `/api/status` → `core`.

### `chat`

```bash
echo '{"messages":[{"role":"user","content":"hi"}]}' | native/bin/CodeForgeCore chat --root .
```

```json
{ "ok": true, "reply": "...", "backend": "openai", "model": "..." }
```

### `debug` / `run` / `selftest`

Same request shapes and response objects as the HTTP endpoints. `selftest`
ignores stdin.

Useful for isolating whether a failure sits in the Java layer or the core
layer:

```bash
echo '{"code":"print(1/0)","language":"python","iterations":1}' \
  | native/bin/CodeForgeCore debug --root . | python -m json.tool
```

---

## 16. Configuration touchpoints

| File | Effect on API |
| --- | --- |
| `config.json` → `llm` | Backend selection (`auto`, `openai`, `ollama`, `mock`), base URL, model name, timeouts |
| `config.json` → `debug` | `compile_timeout_ms`, `run_timeout_ms`, `max_iterations`, output caps |
| `--port` CLI flag | Port for the Java server (default 8765, passed by bootstrap) |
| `models/models.json` | Installed-model index read by `ModelManager` |
| `runtime/runtime.json` | Installed runtime metadata |

Secrets: none required. CodeForge AI never asks for a Hugging Face token;
public repositories are used. If a private mirror is needed, point
`config.json` at an internal OpenAI-compatible gateway.

---

## 17. Error catalogue

| Symptom | Layer | Fix |
| --- | --- | --- |
| `core: connection refused` / `core exit 1` | Java → core | Rebuild: `python aux-python/bootstrap.py --core` |
| `Main core is not built` | Java | `native/bin/CodeForgeCore` missing; run bootstrap |
| `No local model backend is available` | Core → LLM | Install runtime + model, `POST /api/runtime/start` |
| `Another inference service is listening on port 8080` | Runtime | Stop the other server or change the inference port |
| `Code exceeds the 200 KB limit` | Core debug/run | Reduce input; split the file |
| `Request body exceeds the 8 MB limit` | Java | Stream or split the payload |
| `Response body too large` | Core HTTP client | Upstream returned > 64 MB; check the endpoint |
| Download stalls then resumes | ModelManager | Automatic; up to 12 attempts with `Range` resume |
| `SHA-256 mismatch` | ModelManager | Disk corruption; retry the download |
| `llama.cpp runtime is not installed` | RuntimeManager | `POST /api/runtime/install` |
| Compile timeout | Core runner | Raise `config.json → debug.compile_timeout_ms` |
| Run timeout (`exit_code: 124`) | Core runner | Raise `debug.run_timeout_ms`; look for infinite loops |

---

## 18. Client recipes

### Python (`requests`)

```python
import requests

BASE = "http://127.0.0.1:8765"

def chat(prompt: str) -> str:
    r = requests.post(f"{BASE}/api/chat", json={
        "messages": [{"role": "user", "content": prompt}]
    }, timeout=900)
    data = r.json()
    if not data.get("ok"):
        raise RuntimeError(data.get("error"))
    return data["reply"]

def debug(code: str, language: str = "python", iterations: int = 2) -> dict:
    r = requests.post(f"{BASE}/api/debug", json={
        "code": code, "language": language, "iterations": iterations
    }, timeout=240 + iterations * 300)
    return r.json()

print(chat("Show a minimal C++ RAII wrapper for a file handle."))
print(debug("print(1/0)\n", iterations=1)["success"])
```

### Node.js (18+)

```js
const BASE = "http://127.0.0.1:8765";

async function api(path, body) {
  const res = await fetch(BASE + path, body ? {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  } : undefined);
  return res.json();
}

const status = await api("/api/status");
console.log(status.llm_runtime.healthy, status.models.installed_count);
```

### Bash health gate

```bash
until curl -sf http://127.0.0.1:8765/api/health >/dev/null; do sleep 1; done
echo "CodeForge AI server is up"
```

### Android emulator mapping

The Android thin client defaults to `http://10.0.2.2:8765/console/` when
running in an emulator. On a physical device use the PC's LAN address and keep
the server bound to loopback behind an SSH tunnel or a reverse proxy — exposing
port 8765 publicly is equivalent to exposing a code execution endpoint.

### iOS simulator / iSH

- Simulator: `http://127.0.0.1:8765` reaches the Mac host directly.
- iSH: iSH runs Alpine in emulation on the same device; to reach a PC, use
  `ssh -L 8765:127.0.0.1:8765 user@pc` from iSH and then `curl` loopback.

---

## 19. Language runtimes, files, placement and preview

These endpoints are model-independent: the console and the chat **LOCAL OPS**
layer call them directly, so they work the same with an online model, an
offline GGUF model, or no model at all.

### GET `/api/languages`

Returns every managed runtime, the languages the server can execute or
preview, and live install jobs.

```bash
curl -s http://127.0.0.1:8765/api/languages
```

```json
{
  "ok": true,
  "platform": "windows",
  "arch": "x64",
  "toolchain_dir": "native\\toolchains",
  "runtimes": [
    {
      "id": "php",
      "name": "PHP",
      "available": false,
      "state": "downloading",
      "version": "",
      "path": "",
      "managed": false,
      "installable": true,
      "size_mb": 35.0,
      "hint": "Portable PHP CLI (Windows zip build).",
      "languages": ["php"],
      "message": "downloading php-8.4-nts-Win32-vs17-x64-latest.zip",
      "progress": 9,
      "bytes_received": 3300000,
      "bytes_total": 33700000
    }
  ],
  "languages": [
    { "id": "python", "name": "Python", "fence": "python", "extension": "py",
      "runtime": "python", "core": true, "preview": false,
      "available": true, "version": "Python 3.13.14" },
    { "id": "html", "name": "HTML", "fence": "html", "extension": "html",
      "runtime": "browser", "core": false, "preview": true, "available": true, "version": "" }
  ],
  "jobs": [
    { "runtime": "php", "state": "downloading", "percent": 9,
      "bytes_received": 3300000, "bytes_total": 33700000,
      "message": "downloading …", "error": "", "finished": false }
  ]
}
```

`available` for a runtime means a usable compiler/interpreter was found either
on `PATH`, in the portable JDK, or in `native/toolchains/<runtime>`.
`path` is empty for command-only runtimes detected on `PATH` (Windows Store
aliases). Languages running through the C++ core have `core: true`; the rest
are executed by the Java runner.

### POST `/api/languages/install`

Starts a background download when the runtime is missing. Accepts a language
id (`go`, `php`, `csharp`, `html`, …) or a runtime id (`gcc`, `node`, `dotnet`,
…). Repeated calls while a job is active return the same job.

```bash
curl -s -X POST http://127.0.0.1:8765/api/languages/install \
  -H "Content-Type: application/json" \
  -d '{"language":"php"}'
```

The response is the runtime snapshot with `language`, `language_name` and
`auto_install` added. Poll `GET /api/languages` for `progress`,
`bytes_received`, `bytes_total` and the final `state` (`ready` or `failed`).
Downloads are extracted into `native/toolchains/<runtime>`; nothing is written
outside the project.

### POST `/api/files/write`

Writes one text or base64 file to the exact path the user provides. A missing
folder is **not** created silently: the response asks for confirmation.

Request:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `path` | string | yes | Absolute or `~` path, or a name relative to the project root; quotes are stripped |
| `content` | string | yes | UTF-8 text, or base64 when `encoding` is `base64` |
| `filename` | string | no | Appended to `path` when `path` is a folder |
| `encoding` | string | no | `utf8` (default) or `base64` |
| `create_dirs` | boolean | no | When `true`, missing parent folders are created |

```bash
curl -s -X POST http://127.0.0.1:8765/api/files/write \
  -H "Content-Type: application/json" \
  -d '{"path":"D:\\projects\\notes\\readme.txt","content":"hello"}'
```

Missing folder:

```json
{
  "ok": false,
  "error": "The folder does not exist: D:\\projects\\notes. Confirm creating it, then save again.",
  "path": "D:\\projects\\notes",
  "missing_directory": "D:\\projects\\notes",
  "can_create": true
}
```

Success:

```json
{
  "ok": true,
  "path": "D:\\projects\\notes\\readme.txt",
  "directory": "D:\\projects\\notes",
  "filename": "readme.txt",
  "bytes": 5,
  "encoding": "utf8",
  "created_directory": false,
  "message": "Saved 5 bytes to D:\\projects\\notes\\readme.txt"
}
```

Invalid Windows names (`CON`, `NUL`, `COM1`…, trailing dot/space, reserved
characters), a folder at the file path, or a parent that is a file all return
`ok:false` with a specific `error`. Nothing is overwritten silently by a
different name.

### POST `/api/files/write-batch`

Writes several files in one request. Every file may carry its own absolute
`path`, so multiple files can go to completely different destinations.

```json
{
  "path": "D:\\projects\\demo",
  "create_dirs": true,
  "files": [
    { "name": "main.py", "content": "print(1)" },
    { "name": "index.html", "path": "E:\\site\\index.html", "content": "<h1>hi</h1>" },
    { "name": "main.go", "path": "D:\\go-demo\\main.go", "content": "package main\nfunc main(){}" }
  ]
}
```

`path` is the default folder; a per-file `path` overrides it (relative values
are resolved under the default folder, absolute values are used as-is).
Response fields: `ok`, `count`, `bytes`, `directories`, `created_directory`,
`files` (each `path`, `bytes`, `filename`) and `failures` (each `name`/`path`,
`error`). A `can_create` response is returned when the default folder is
missing and `create_dirs` is false. Individual failures never abort the other
files.

### POST `/api/files/reveal`

Opens the system file manager at a file (selects it) or folder.

```bash
curl -s -X POST http://127.0.0.1:8765/api/files/reveal \
  -H "Content-Type: application/json" -d '{"path":"D:\\projects\\notes"}'
```

A nonexistent path returns `ok:false` with `"That path does not exist: …"`.

### POST `/api/desktop/install`

Creates a real launcher (Windows `.lnk` through WScript.Shell, Linux
`.desktop`, macOS executable `.command`) at any folder or file path.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `path` | string | no | Folder or launcher file path; empty means the current user's Desktop |
| `create_dirs` | boolean | no | Create a missing folder instead of returning `can_create` |

The launcher points at `native/bin/CodeForge.exe` when that binary exists,
otherwise it starts the project through `aux-python/bootstrap.py --run`.
Response: `ok`, `platform`, `desktop`, `path`, `target`, `message`. A missing
folder returns `ok:false`, `can_create:true` and `missing_directory`; a path
that names an existing non-launcher file returns an explicit error. Passing a
folder creates a launcher inside it; passing a `.lnk`/`.desktop`/`.command`
file path creates exactly that file.

### POST `/api/project/place`

Copies the runnable project tree to any folder the user chooses. Heavy local
state is never copied: `models/`, `runtime/`, `workspace/`, `logs/`, `memory/`,
`build/`, `release/`, `.git/`, caches and editor folders are excluded. The
portable JDK and downloaded toolchains are optional.

```bash
curl -s -X POST http://127.0.0.1:8765/api/project/place \
  -H "Content-Type: application/json" \
  -d '{"path":"E:\\apps\\CodeForge-AI","include_jdk":false,"include_toolchains":false,"shortcut":true}'
```

| Field | Type | Notes |
| --- | --- | --- |
| `path` | string | Empty means `Desktop/CodeForge-AI`; a missing drive returns an explicit error |
| `include_jdk` | boolean | Copy `native/jdk` (about 305 MB) |
| `include_toolchains` | boolean | Copy `native/toolchains` |
| `shortcut` | boolean | Create a launcher inside the destination (default `true`) |

Response: `ok`, `path`, `files`, `bytes`, `skipped`, `duration_ms`,
`include_jdk`, `include_toolchains`, `shortcut`, `message`. The exporter
refuses to copy the project onto itself or into a folder inside the project,
and never overwrites the source tree.

### GET `/preview/<session>/<file>`

Serves a generated HTML/CSS preview from `workspace/run-<stamp>-<token>/`.
The session name must match `(?i)(?:run|debug)-[a-z0-9._-]{3,80}`; `..`,
backslashes and leading slashes are rejected. Files are confined to that
session folder. Headers: correct `Content-Type`, CSP `sandbox allow-scripts
allow-forms allow-modals allow-popups allow-pointer-lock`, `nosniff`,
`no-store`.

---

## 20. Security model of the API

- Loopback binding only; no TLS because traffic never leaves the host.
- No authentication: treat the port as a local trust boundary. Do not expose
  it through `ngrok`, port forwarding, or a public reverse proxy without
  adding an authenticating proxy and TLS.
- `/api/run` and `/api/debug` execute arbitrary code as your user. That is the
  product's purpose, but it means any local process can do the same through
  the port.
- The server never reads environment secrets or sends telemetry. Downloads are
  anonymous HTTPS requests to Hugging Face and GitHub.
- All model files stay under `models/downloads/`; all generated code stays
  under `workspace/`; logs stay under `logs/`.
