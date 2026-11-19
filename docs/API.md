# CodeForge AI — API Reference

Version 1.0.8 · Java 17 HTTP server · C++ core bridge

CodeForge AI exposes a single local REST API on `http://127.0.0.1:8765`.
There is no account and no telemetry. Every endpoint is served by the Java
control server, which layers memory, workspace retrieval, optional online
research and dual-brain routing on top of the C++ main core.

```
Browser / curl / mobile thin client
        │  HTTP 1.1, JSON
        ▼
Java 17 server  ──  memory · workspace index · online research
 127.0.0.1:8765  ──  dual-brain routing
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
| GET | `/api/update` | Newer-release check; metadata only, `?refresh=1` bypasses the cache |
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
| POST | `/api/files/place` | Copy or move any file or folder to a chosen path or the Desktop |
| POST | `/api/desktop/install` | Create a launcher at any folder or file path |
| POST | `/api/project/place` | Copy the runnable project tree to any folder |
| GET | `/preview/<session>/<file>` | Sandboxed preview of generated HTML/CSS |
| POST | `/api/selftest` | Toolchain and smoke-test suite |
| GET | `/api/models` | Model registry, install state, runtime state, jobs |
| POST | `/api/models/download` | Start a background model download |
| GET | `/api/models/resolve` | Resolve mirror URL, size and SHA-256 |
| POST | `/api/runtime/install` | Download and extract llama.cpp runtime |
| POST | `/api/runtime/start` | Start llama-server with an installed model |
| POST | `/api/runtime/stop` | Stop the managed llama-server |
| POST | `/api/runtime/select` | Choose the runtime backend: `auto`, `cpu` or `cuda` |
| GET | `/api/runtime/logs` | Tail the inference server log |
| GET | `/api/downloads` | Download job list |
| GET/POST | `/api/memory` | Memory stats/search or add an entry |
| POST | `/api/memory/delete`, `/api/memory/clear` | Delete one entry or clear the store |
| GET/POST | `/api/workspace` | Workspace index stats/search or reindex |
| GET | `/api/online` | Network and research status |
| GET | `/api/online/research` | Web research persisted into memory |
| GET | `/api/online/fetch` | Fetch one page as readable text |
| POST | `/api/inspect` | Static scan of a file or folder; reports, quarantine, integrity, optional shred |
| GET | `/api/quarantine` | Quarantine vault entries with shredded/quarantined state |
| GET/POST | `/api/secrets` | List named secrets or store a value in the local vault |
| POST | `/api/secrets/delete` | Delete a named secret |
| POST | `/api/api-call` | Universal outbound HTTP/HTTPS call with optional secret injection |
| GET | `/api/publish/status` | Publish defaults and token source names (never values) |
| POST | `/api/publish` | Create/update a GitHub or GitCode release and upload assets |
| GET/POST | `/api/agent` | Read or update Command Agent settings and capabilities |
| GET | `/api/agent/software` | Discovered installed applications |
| POST | `/api/agent/run` | Execute one command with auto-provisioning and the publish guard |
| POST | `/api/agent/provision` | Install one missing tool |
| POST | `/api/agent/build` | Build and deliver a program for any supported language |
| GET | `/api/keys` | List saved online-provider keys (masked hints only) |
| POST | `/api/keys` | Add or update a provider key; mirrors the active one into `llm.online` |
| POST | `/api/keys/activate` | Switch the active online brain |
| POST | `/api/keys/delete` | Remove a saved key from `config.json` |
| POST | `/api/keys/test` | Send a minimal completion to verify endpoint, model and key |
| POST | `/api/brain/enabled` | Turn the online brain on or off; saved keys stay stored |
| GET/POST | `/api/settings/llm` | Read or set the shared reply-token limit (64–393,216) |
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
  "version": "1.0.8",
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
  "version": "1.0.8",
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
  "started_at": "2026-11-19T12:19:49.048Z",
  "log": "logs\\llama-server.log",
  "error": "",
  "max_tokens": 8192,
  "max_tokens_min": 64,
  "max_tokens_max": 393216,
  "backend": "cuda",
  "backend_selected": "auto"
}
```

| Field | Meaning |
| --- | --- |
| `running` | Managed process alive **or** a healthy server answers on 8080 |
| `managed` | The server was started by CodeForge AI |
| `healthy` | `GET /v1/models` on 8080 answered within the probe timeout |
| `pid` | OS process id, `0` when not managed |
| `model_id`, `model_path` | Active model when managed |
| `max_tokens` | Shared reply budget for every offline model |
| `max_tokens_min`, `max_tokens_max` | Accepted reply-limit range |
| `backend` | Resolved backend actually used by the running server: `cpu` or `cuda`; empty before first start |
| `backend_selected` | Saved choice: `auto`, `cpu` or `cuda` |
| `error` | Last startup/probe error, empty on success |

#### `runtime` object

`runtime/runtime.json` after a successful install:

```json
{
  "version": "b12532",
  "platform": "win-cpu-x64",
  "arch": "amd64",
  "server": "runtime\\llama.cpp\\llama-server.exe",
  "size": 25700468,
  "sha256": "beb8c051...9e473fa",
  "archive": "llama-b12532-bin-win-cpu-x64.zip",
  "installed_at": "2026-11-19T08:49:18.636Z",
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

### GET `/api/update`

Reads the public release index and compares the newest stable tag with the
running build. GitCode is preferred on Chinese systems and GitHub is the
fallback; results are cached for ten minutes. `?refresh=1` forces a fresh
check. Only release metadata is fetched — no prompt, file, key or usage data.

```json
{
  "ok": true,
  "enabled": true,
  "current": "1.0.8",
  "latest": "1.0.9",
  "update_available": true,
  "source": "gitcode",
  "name": "CodeForge AI v1.0.9",
  "published_at": "2026-12-01T10:00:00+08:00",
  "url": "https://gitcode.com/gcw_So3EkhfR/CodeForge-AI/releases/tag/v1.0.9",
  "checked_at": "2026-12-01T02:10:00Z"
}
```

With `"updates": {"enabled": false}` in `config.json` the response is
`{"ok": true, "enabled": false, "current": "1.0.8", "update_available": false}`.
If both release indexes are unreachable, `ok` is `false` with an `error`
string; the console shows no banner.

---

## 5. Chat

### POST `/api/chat`

Sends an OpenAI-style message array through the dual-brain router. The server
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
  "sources": [
    { "title": "Official source title", "url": "https://example.gov/..." }
  ],
  "meta": {
    "memory": { "enabled": true, "entries": 42, "hits": 3 },
    "workspace": { "enabled": true, "files": 130, "hits": 2 },
    "brain": {
      "requested": "online",
      "route": "online",
      "online_enabled": true,
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
| `sources` | Web sources used for the answer |
| `meta.brain` | Requested route, actual route, configuration and fallback reason |
| `meta.memory` | Store state and retrieval hit count |
| `meta.workspace` | Index size and hit count |
| `meta.online` | Whether research ran, the search engine and result count |
| `meta.estimated_ms` | Total-duration estimate when generation started |
| `request_id` | Id that ties the response to progress and cancel calls |

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
  "meta": { "elapsed_ms": 14438, "cancelled": true }
}
```

A cancelled turn is **not** written to persistent memory: no `chat` entry and
no online `lesson` are stored. Web results fetched before the interruption may
already have been captured as `web` memory by the research stage; those are
ordinary knowledge entries, not a record of the cancelled exchange.

Cancellation is cooperative at stage boundaries (memory, research, context
assembly) and immediate during model generation, where the core process is
terminated. If the id is unknown the endpoint still
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
  "version": "1.0.8",
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
| `runtime_variants` | backend selection and install state for both builds |

`runtime_variants`:

```json
{
  "selected": "auto",
  "resolved": "cuda",
  "cpu": {
    "installed": true,
    "version": "b12428",
    "platform": "win-cpu-x64",
    "server_absolute": "C:\\...\\runtime\\llama.cpp\\llama-server.exe"
  },
  "cuda": {
    "installed": true,
    "version": "b12428",
    "platform": "win-cuda-x64",
    "server_absolute": "C:\\...\\runtime\\llama.cpp-cuda\\llama-server.exe"
  }
}
```

`selected` is the saved choice from `runtime/selection.json`; `resolved` is
the backend Auto currently maps to. Each backend object is the corresponding
`runtime/*.json` plus `installed` and `server_absolute`.

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
    "downloaded_at": "2026-11-19T11:18:45.566Z"
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
through the configured Hugging Face-compatible mirror (`hf-mirror.com` by
default, `huggingface.co` fallback), validates the expected size and SHA-256,
resumes with HTTP `Range` after connection drops (up to 12 attempts), and only
then moves the `.part` file into `models/downloads/<id>/`.

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

Resolves the live upstream metadata without downloading. The default source is
the domestic `hf-mirror.com` mirror; `huggingface.co` is tried automatically if
the mirror fails or returns incomplete metadata. Override the order with
`llm.model_mirror` or `CODEFORGE_MODEL_MIRROR`.

```bash
curl -s "http://127.0.0.1:8765/api/models/resolve?id=deepseek-r1-0528-qwen3-8b"
```

```json
{
  "ok": true,
  "id": "deepseek-r1-0528-qwen3-8b",
  "repo": "unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF",
  "file": "DeepSeek-R1-0528-Qwen3-8B-Q4_K_M.gguf",
  "url": "https://hf-mirror.com/.../resolve/main/...gguf",
  "source": "https://hf-mirror.com",
  "size": 5012345678,
  "sha256": "…",
  "installed": false
}
```

The `source` field reports which mirror answered. SHA-256 resolution accepts
both the current `lfs.sha256` metadata field and the legacy `lfs.oid` pointer.
If resolution fails: `{"ok": false, "error": "..."}`.

---

## 10. Runner management

### POST `/api/runtime/install`

Downloads the matching CPU llama.cpp release for the host CPU and OS:
`win-cpu-x64`, `win-cpu-arm64`, `macos-arm64`, `macos-x64`, `ubuntu-x64`,
`ubuntu-arm64`, `ubuntu-s390x`, `android-arm64` or
`android-arm64-snapdragon` (Termux ARM64). It extracts `llama-server`, writes
`runtime/runtime.json`, and verifies the binary SHA-256. CUDA is not downloaded
by this endpoint: extract the official CUDA build into
`runtime/llama.cpp-cuda/`, add `runtime/runtime-cuda.json`, then select it with
`POST /api/runtime/select`.

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

An active install is rejected with `{"ok": false, "error": "..."}`. If the
runtime is already installed, the request is rejected unless the body includes
`{"force": true}`; the console asks for confirmation before sending `force`.
Progress appears in `GET /api/downloads` under the runtime job id.

The installer tries the GitHub asset first, then falls back to the mirrors in
`llm.runtime_mirror` (`config.json`) or `CODEFORGE_RUNTIME_MIRROR`, and finally
to the built-in `ghproxy.net` and `gh-proxy.com` prefixes. A mirror entry is
either a prefix such as `https://ghproxy.net/` or a template containing
`{url}`. The SHA-256 published by GitHub is verified after every source, so a
tampered mirror download is rejected.

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
--parallel <parallel> -t <half the cores> --no-webui --reasoning off
--reasoning-budget 256`. The CUDA backend adds `-ngl 99` and runs
`runtime/llama.cpp-cuda/llama-server.exe`; the CPU backend runs
`runtime/llama.cpp/llama-server.exe` (or the `llm.llama_server` override).
Defaults are 16,384 tokens and one slot; both values are clamped to
2,048–524,288 and 1–8. llama.cpp divides the total context across the slots.
The runtime panel sends the same fields; the model's native context remains the
effective ceiling. Logs are appended to `logs/llama-server.log`.

### POST `/api/runtime/stop`

Stops the managed process tree. External servers are not touched.

```bash
curl -s -X POST http://127.0.0.1:8765/api/runtime/stop \
  -H "Content-Type: application/json" -d "{}"
```

```json
{ "ok": true, "message": "Inference service stopped." }
```

### POST `/api/runtime/select`

Saves the preferred llama.cpp backend. The console calls this before restarting
inference; a running server keeps its current backend until stopped.

Request:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `backend` | string | yes | `auto`, `cpu` or `cuda` |

```bash
curl -s -X POST http://127.0.0.1:8765/api/runtime/select \
  -H "Content-Type: application/json" -d '{"backend":"cuda"}'
```

```json
{ "ok": true, "selected": "cuda", "resolved": "cuda" }
```

| Situation | Response |
| --- | --- |
| Invalid value | `{"ok": false, "error": "Backend must be auto, cpu or cuda."}` |
| `cuda` without a CUDA build | `{"ok": false, "error": "The CUDA llama.cpp runtime is not installed in runtime/llama.cpp-cuda."}` |

The selection is written atomically to `runtime/selection.json` as
`{"backend":"<value>"}`. With `auto`, the resolver picks CUDA when
`runtime/llama.cpp-cuda/llama-server.exe` exists and CPU otherwise. With
`cuda`, startup uses `-ngl 99`; the CPU path also passes `--reasoning off
--reasoning-budget 256` and the server environment sets
`LLAMA_ARG_CHAT_TEMPLATE_KWARGS={"enable_thinking":false}`.

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

## 12. Memory, workspace and research

### GET / POST `/api/memory`

```bash
curl -s "http://127.0.0.1:8765/api/memory?q=debugger&limit=5"
curl -s -X POST http://127.0.0.1:8765/api/memory \
  -H "Content-Type: application/json" \
  -d '{"text":"Prefer C++17 structured bindings.","kind":"preference","source":"manual"}'
```

`GET` without `q` returns `stats()` plus `recent`; with `q` it returns ranked
`hits` with `id`, `kind`, `source`, `ts`, `score` and `text`.
`POST` accepts `text`, optional `kind` (`fact`, `preference`, `web`, `chat`,
`lesson`, `note`) and `source`.

`POST /api/memory/delete` deletes one entry by `id`; `POST /api/memory/clear`
empties the store. Both are explicit user actions; there is no automatic
deletion of recent knowledge.

### GET / POST `/api/workspace`

```bash
curl -s "http://127.0.0.1:8765/api/workspace?q=server&limit=3"
curl -s -X POST http://127.0.0.1:8765/api/workspace \
  -H "Content-Type: application/json" -d "{}"
```

`GET` returns index stats when `q` is empty, otherwise `hits` with `path`,
`excerpt`, `updated_at` and `score`. `POST` rescans the project tree and
rewrites `memory/project-index.jsonl`. Secret files, `config.json`, builds,
models, runtime, logs, workspaces and the memory directory are skipped.

### GET `/api/online`

Returns network state, research settings, last research source and research
timestamps. `?probe=0` skips the live network probe.

### GET `/api/online/research?q=<query>`

Queries Google, Baidu, Baidu Zhidao, DuckDuckGo, Bing, 360 Search and
Wikipedia in parallel. Unreachable, blocked or captcha-walled sources are
skipped and retried after a ten-minute cooldown; results are deduplicated by
URL, filtered for token relevance, ranked by relevance, source trust,
cross-source agreement and executable snippets, and the strongest candidate is
marked. Each useful result is persisted as a `web` memory. The response
contains `results`, per-source diagnostics and a bounded `context` block. When
offline it returns `"online": false` with an error and does not overwrite
memory.

### GET `/api/online/fetch?url=<url>&max_chars=<n>`

Downloads one page, follows redirects, strips scripts and navigation, and
returns readable text plus title, final URL, content type, byte size and
`duration_ms`. `max_chars` defaults to 12,000. The fetch is bounded by timeout
and size limits; failed schemes, private addresses and non-text responses are
rejected. Web content is untrusted data and is never executed.

Memory, online research and online-brain routing are controlled by `memory`,
`online` and `llm.online` in `config.json`; environment overrides are listed in
[MANUAL.md](MANUAL.md#107-environment-variables).

### GET `/api/agent`

Returns the saved Command Agent settings and a live capability snapshot:
platform, default and available shells, workspace/root/home, detected command
names with versions, installed applications, deduplicated auto-provisionable
tools, publishing policy and detection timestamp. `?refresh=1` bypasses the
capability cache and rescans tools and applications.

```json
{
  "ok": true,
  "enabled": true,
  "max_steps": 128,
  "command_timeout_sec": 3600,
  "max_feedback_chars": 48000,
  "auto_install_tools": true,
  "install_timeout_sec": 1800,
  "auto_learn_software": true,
  "allow_publish": false,
  "capabilities": {
    "os": "Windows 11",
    "shell": "powershell",
    "tool_count": 33,
    "software_count": 162,
    "provisionable": [{"id":"qpdf","name":"QPDF","available":true,"path":"…","method":"package"}],
    "publishing": {"supported": true, "requires_permission": true, "allow_publish_setting": "agent.allow_publish"}
  }
}
```

### POST `/api/agent`

Updates one or more settings. Boolean fields must be booleans; numeric values
are clamped to the documented ranges (`max_steps` 1–512, default 128). The
response has the same shape as `GET /api/agent`, including the refreshed
capability snapshot.

```bash
curl -s -X POST http://127.0.0.1:8765/api/agent \
  -H "Content-Type: application/json" \
  -d '{"max_steps":96,"auto_install_tools":true,"auto_learn_software":true}'
```

`allow_publish` is the safety boundary: when false (default), remote/public
actions such as `git push`, release creation, registry/container pushes,
remote copies, cloud deploys and outbound write HTTP methods are blocked.
Local commits, builds, packages and exports remain available.

### GET `/api/agent/software?refresh=1`

Returns discovered installed applications (`name`, launch identifier or path,
version when available) from Start-menu entries and registry uninstall keys on
Windows, `.app` bundles on macOS, and `.desktop` entries on Linux. `refresh=1`
forces a rescan.

### POST `/api/agent/run`

Executes one command through the selected shell with the same provisioning and
publishing protections as the chat loop. It does not call a model.

```bash
curl -s -X POST http://127.0.0.1:8765/api/agent/run \
  -H "Content-Type: application/json" \
  -d '{"command":"git status --short","cwd":"D:\\work","shell":"powershell","timeout":120,"auto_install":true,"install_timeout":1800,"allow_publish":false}'
```

Fields: `command` (required), optional `cwd`, `shell`
(`powershell|cmd|bash|sh|zsh`), `timeout` in seconds (default
`agent.command_timeout_sec`), `auto_install`, `install_timeout` and
`allow_publish`. The response includes `ok`, `exit_code`, `duration_ms`,
`timed_out`, `cancelled`, `shell`, `cwd`, `stdout_tail`, `stderr_tail`,
`stdout_log`, `stderr_log`, `provisioning` (tool, availability, method, path,
message, error, duration) and, for blocked commands, `blocked`,
`permission_required` and `publish_guard`.

A missing command is provisioned as follows: portable toolchain first, then
the native package manager (winget, apt, dnf, yum, pacman, zypper, apk or
brew), then pip or npm when mapped; PATH is refreshed and the command is
retried once. yt-dlp prefers pip on Windows and falls back to winget with
`--skip-dependencies`, so its optional FFmpeg bundle is not downloaded.

### POST `/api/agent/provision`

Installs one tool without executing a command.

```bash
curl -s -X POST http://127.0.0.1:8765/api/agent/provision \
  -H "Content-Type: application/json" -d '{"tool":"qpdf","timeout":1800}'
```

Returns the same provisioning object as `/api/agent/run`, plus
`install_log` and `install_output`. A tool that is already available returns
`method: "already-present"` without reinstalling.

### GET `/api/keys`

Returns the saved provider profiles. Secrets are never returned: each entry has
`key_hint` (for example `••••••••a764`) and `has_key` instead of `api_key`.
`active_id` names the profile mirrored into `llm.online`.

```json
{
  "ok": true,
  "config": "…/config.json",
  "active_id": "deepseek-1a2b3c",
  "online_enabled": true,
  "providers": [
    {
      "id": "deepseek-1a2b3c",
      "name": "DeepSeek",
      "base_url": "https://api.deepseek.com",
      "model": "deepseek-chat",
      "key_hint": "••••••••a764",
      "has_key": true,
      "active": true
    }
  ]
}
```

If `llm.keys` is absent but `llm.online.api_key` exists (an older hand-edited
config), the existing key is shown as an active `default` entry.

### POST `/api/keys`

Add a profile, or update one when `id` is supplied. `api_key` may be omitted on
update to keep the stored secret. Set `activate` to true to make it the online
brain immediately; the first saved key is always activated.

```bash
curl -s -X POST http://127.0.0.1:8765/api/keys \
  -H "Content-Type: application/json" \
  -d '{"name":"DeepSeek","base_url":"https://api.deepseek.com","model":"deepseek-chat","api_key":"sk-…","activate":true}'
```

Profiles live in `llm.keys`; the active one is copied into
`llm.online.base_url`, `llm.online.model` and `llm.online.api_key` so the C++
core picks it up on its next call. Writes are atomic and preserve every other
setting in `config.json`.

### POST `/api/keys/activate`

```bash
curl -s -X POST http://127.0.0.1:8765/api/keys/activate \
  -H "Content-Type: application/json" -d '{"id":"deepseek-1a2b3c"}'
```

### POST `/api/keys/delete`

Removes the profile. Deleting the active profile promotes the next saved key;
deleting the last one clears `llm.online.api_key`, so chat falls back to the
offline GGUF model.

```bash
curl -s -X POST http://127.0.0.1:8765/api/keys/delete \
  -H "Content-Type: application/json" -d '{"id":"deepseek-1a2b3c"}'
```

### POST `/api/keys/test`

Sends a minimal `chat/completions` request using the saved profile (or the
supplied `base_url`, `model`, `api_key` for an unsaved form) and returns
`ok`, `status`, `latency_ms`, `model` and `reply`, or the provider error text.
Test requests never modify `config.json`.

### POST `/api/brain/enabled`

Sets the online-brain master switch by writing `llm.online.enabled`; the body
must contain a boolean.

```bash
curl -s -X POST http://127.0.0.1:8765/api/brain/enabled \
  -H "Content-Type: application/json" -d '{"enabled":false}'
```

When `false`, `/api/chat` stays on the offline GGUF model even if a valid key is
saved and active. The key profiles and the mirror in `llm.online` are preserved,
so switching back to `true` restores online routing without re-entering the key.
The response is the same snapshot returned by `GET /api/keys`, including the new
`online_enabled` field. `/api/status` and chat/debug `meta.brain` expose the same
flag.

### GET / POST `/api/settings/llm`

Reads or updates the reply-token limit shared by every offline model. The value
is stored as `llm.max_tokens` in `config.json`; it applies to the next request
and does not require restarting llama-server.

```bash
curl -s http://127.0.0.1:8765/api/settings/llm
curl -s -X POST http://127.0.0.1:8765/api/settings/llm \
  -H "Content-Type: application/json" -d '{"max_tokens":8192}'
```

Accepted range is `64`–`393216`. The response includes `ok`, `max_tokens`,
`min` and `max`. Values outside the range are rejected. 393,216 is the largest
value accepted by the active DeepSeek endpoint; the context window still limits
the actual reply, and local generation time grows with the budget; cancel with
`/api/chat/cancel` if needed. The C++ core extends the local HTTP timeout for
large reply budgets (up to a two-day safety cap).

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
{ "ok": true, "component": "main-core-cpp", "version": "1.0.8", "root": "C:\\...\\CodeForge-AI" }
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
| `blocked: true` with `publish_guard: true` | Command agent | The command is a public/remote action; enable `agent.allow_publish` or run that one command through the API with `allow_publish=true` |
| `No automatic installer is mapped for '<tool>'` | Tool provisioning | Install the tool manually or extend `ToolProvisioner`; the command itself is valid |
| Tool install timeout | Tool provisioning | Raise `agent.install_timeout_sec`; check the install log under `logs/provision-*.log` |
| `No usable AI backend` after many tool steps | Chat agent | Older builds exhausted the context; current builds compact tool output, keep `tool_calls`, and return them even on model failure. Check `/api/chat/progress` for the failed step |

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

These endpoints are model-independent: the console and the chat **local
command layer** call them directly, so they work the same with an online model,
an offline GGUF model, or no model at all.

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

### POST `/api/agent/build`

Builds and delivers a finished program for any supported language. The server
writes or reuses the source, compiles or packages it, copies the runnable
result into a per-app folder, and creates a real shortcut on the Desktop.

```bash
curl -s -X POST http://127.0.0.1:8765/api/agent/build \
  -H "Content-Type: application/json" \
  -d '{"language":"python","source":"C:\\work\\snake.py","name":"Snake","gui":false}'
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `language` | string | yes | `c`, `cpp`, `go`, `rust`, `csharp`, `java`, `python`, `javascript`, `typescript`, `php`, `html` or `css` |
| `source` | string | one of `source`/`code` | Absolute path to the source file; for HTML/CSS also a folder or `.html` file |
| `code` | string | one of `source`/`code` | Inline source for small prototypes, written into the build workspace |
| `name` | string | no | App and folder name (defaults to the source filename) |
| `gui` | boolean | no | Windowed mode: `.pyw` for Python, `WinExe` for C# |
| `destination` | string | no | Target folder; empty means the current user's Desktop |

Response: `ok`, `success`, `language`, `language_id`, `name`, `phase` (`ready`
on success), `runtime`, `compiler`, `folder`, `artifact`, `artifact_kind`,
`launcher`, `shortcut`, `command`, `stdout`, `stderr`, `exit_code`,
`duration_ms`, `message`. C, C++, Go, Rust and C# produce a native executable;
Java produces a runnable jar with a launcher; Python, JavaScript, TypeScript and
PHP produce script plus interpreter-pinned launcher; HTML/CSS produces an
`index.html` page and a shortcut. On a compiler error `ok` is false and
`stderr`/`stdout` carry the diagnostics so the model can fix the source and
retry. In chat the same tool is emitted as
`<codeforge_build>{...}</codeforge_build>`; legacy XML and DSML envelopes with
a bare JSON body are accepted too.

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
| `path` | string | Empty means `Desktop/CodeForge-AI`; `D`, `D盘` or `D盘根目录` mean that drive root (C works too); a missing drive returns an explicit error |
| `include_jdk` | boolean | Copy `native/jdk` (about 305 MB) |
| `include_toolchains` | boolean | Copy `native/toolchains` |
| `shortcut` | boolean | Create a launcher inside the destination (default `true`) |

Response: `ok`, `path`, `files`, `bytes`, `skipped`, `unchanged`, `duration_ms`,
`include_jdk`, `include_toolchains`, `shortcut`, `message`. Files already present
with the same size and modification time are counted in `unchanged` and are not
rewritten, so repeating a placement is fast and does not touch files locked by a
running copy. The exporter refuses to copy the project onto itself or into a
folder inside the project, and never overwrites the source tree.

### POST `/api/files/place`

Copies or moves one file or an entire folder tree to an exact path the user
chooses, including the Desktop. Use `/api/project/place` for the running
project tree; this endpoint refuses the project root so models, runtime and
logs are never copied by accident.

```bash
curl -s -X POST http://127.0.0.1:8765/api/files/place \
  -H "Content-Type: application/json" \
  -d '{"source":"C:\\Users\\you\\Downloads\\report.pdf","destination":"D:\\archive","mode":"copy"}'
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `source` | string | yes | File or folder to place; `desktop`/`desktop\...` aliases are also accepted |
| `destination` | string | no | Target folder or exact file path; empty means the current user's Desktop; `D`, `D盘` and `D盘根目录` mean the drive root (C works too) |
| `name` | string | no | New name inside the destination folder |
| `mode` | string | no | `copy` (default) or `move`; move deletes the source only after a successful copy |
| `overwrite` | boolean | no | Replace existing files; without it a conflict returns `ok:false` and `can_overwrite:true` |
| `create_dirs` | boolean | no | Create missing destination folders (default `true`) |

Destination rules: an existing folder receives the source inside it (or under
`name`); an existing file is an exact target and must match the source type; a
missing destination is the target folder itself for a folder source (a
trailing slash means "inside this new folder") and is treated as an exact
file when the path looks like a file name, otherwise as a folder for a file
source. On Windows a bare drive target may be written as `D`, `D盘`,
`D盘根目录` or `D:` (any letter, including C); writing to `C:\` itself usually
requires administrator rights. Repeating a copy skips files with the same size
and modification time, so an unchanged tree returns quickly and does not touch
files locked by a running process. Locked files are retried and reported with a
clear hint; the source and destination cannot be the same path, a folder cannot
be placed inside itself, and the project root is rejected.

Response: `ok`, `source`, `path`, `kind` (`file`/`folder`), `mode`, `moved`,
`files`, `bytes`, `unchanged`, `overwritten`, `created_directory`,
`duration_ms`, `directory`, `message`. A conflict returns `ok:false`,
`error`, `path` and `can_overwrite:true`.

### GET `/preview/<session>/<file>`

Serves a generated HTML/CSS preview from `workspace/run-<stamp>-<token>/`.
The session name must match `(?i)(?:run|debug)-[a-z0-9._-]{3,80}`; `..`,
backslashes and leading slashes are rejected. Files are confined to that
session folder. Headers: correct `Content-Type`, CSP `sandbox allow-scripts
allow-forms allow-modals allow-popups allow-pointer-lock`, `nosniff`,
`no-store`.

---

## 20. Security model of the API

- Loopback binding only; local console and API traffic stays on the host and
  needs no TLS. Requests to a configured online model provider leave the host
  over HTTPS.
- No authentication: treat the port as a local trust boundary. Do not expose
  it through `ngrok`, port forwarding, or a public reverse proxy without
  adding an authenticating proxy and TLS.
- `/api/run` and `/api/debug` execute arbitrary code as your user. That is the
  product's purpose, but it means any local process can do the same through
  the port.
- `/api/agent/run` executes arbitrary shell commands with the same privileges
  and can download tools. The chat agent adds a publishing guard for remote
  and public actions, but the guard is a safety default, not an OS sandbox:
  code that is allowed to run can still perform network and file operations
  directly. Review commands before approving elevated or destructive work.
- `/api/agent` exposes installed application names and tool paths. They stay
  on the loopback interface and are not uploaded; the agent prompt may send
  the list to the configured online model when online routing is active.
- Auto-learning retrieves web pages as untrusted text. The agent is instructed
  not to execute page instructions, but no content filter can make third-party
  text trustworthy.
- The server never reads environment secrets or sends telemetry. Downloads are
  anonymous HTTPS requests to the configured model mirror (`hf-mirror.com` by
  default, `huggingface.co` fallback) and GitHub.
- All model files stay under `models/downloads/`; all generated code stays
  under `workspace/`; logs stay under `logs/`.
- Static inspection reads bytes only and never executes the inspected file.
  The request returns an `integrity` object produced by re-hashing every source
  file after analysis, so callers can verify that the scan did not modify it.
  Shredding applies only to quarantine copies created by that same request.

---

## 21. Static inspection, secrets, outbound API and publishing

### POST `/api/inspect`

Runs the static code inspector on one file or a directory. Nothing is executed:
there is no interpreter, compiler, loader or shell involved. The server reads
bytes, scores risk, writes JSON and Markdown reports, optionally copies risky
files into the read-only quarantine vault, re-hashes every source file to prove
integrity, and optionally shreds only the copies created by this request.

```bash
curl -s -X POST http://127.0.0.1:8765/api/inspect \
  -H "Content-Type: application/json" \
  -d '{"path":"C:\\samples","depth":2,"max_files":20,"quarantine":true,"shred_quarantine":true}'
```

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `path` | string | required | File or directory; relative paths resolve under the project root |
| `depth` | integer | `3` | 0–6 |
| `max_files` | integer | `60` | 1–500 |
| `quarantine` | boolean | `true` | Copy files scoring 70+ to `workspace/quarantine/` as read-only copies |
| `shred_quarantine` | boolean | `false` | After the report is written, shred quarantine copies created by this run; alias `shred` |
| `max_file_mb` | integer | `64` | Read-only in the response; larger files are skipped |

Response fields:

| Field | Meaning |
| --- | --- |
| `files_scanned`, `manifests_scanned` | Counts of analysed code files and dependency/config manifests |
| `aggregate` | Total bytes/lines, per-language breakdown, risk counts and high-risk file list |
| `files[]` | One detailed report per file: hashes, language, MIME, encoding, entropy, binary facts, metrics, structure, dependencies, network clues, secrets (masked), risk, quarantine |
| `manifests[]` | Dependency and config summaries for `.env`, package manifests, lockfiles and build files |
| `quarantine[]` | Copies created in this run, with `quarantine_path`, `meta_path`, `sha256`, `shredded`, `shredded_at`, `shred_method`, `mode` and `executed:false` |
| `integrity` | `unchanged`, `content_verified`, `files_checked`, `files_skipped`, `files_changed`, `files_unreadable`, `budget_bytes`, `budget_used_bytes`, `digest` and per-file change lists |
| `shred` | `requested`, `performed`, `files_shredded`, `files_failed`, `entries`, `notice` |
| `report` | Absolute paths, byte sizes, and any rewrite error for the JSON and Markdown reports |
| `execution` | Always `{"mode":"static-only","executed":false}` |
| `notice` | Human-readable summary including whether originals were untouched |

`integrity.content_verified` is true only when at least one file was checked,
every scanned file was checked, and none changed or became unreadable. Files
larger than the remaining 256 MB re-hash budget are counted in
`files_skipped`, so verification is reported as incomplete rather than faked.

`shred.performed` is true when a shred request was carried out; inspect
`files_shredded` for the number of copies destroyed. A successful shred keeps
the sidecar and records `shredded:true`, `shredded_at`, `shred_method` and
`original_untouched:true`. Originals are never moved, modified or deleted.

### GET `/api/quarantine`

Lists the quarantine vault. Each entry includes `name`, `original_path`,
`quarantine_path`, `meta_path`, `score`, `reason`, hashes, `shredded`,
`shredded_at`, `quarantine_size` and whether the copy currently exists.
Shredded entries retain their metadata so the audit trail survives.

### GET / POST `/api/secrets`

`GET` returns secret **names**, a masked hint and length; values are never
returned. `POST` stores or replaces one secret:

```bash
curl -s -X POST http://127.0.0.1:8765/api/secrets \
  -H "Content-Type: application/json" \
  -d '{"name":"EXAMPLE_TOKEN","value":"…"}'

curl -s -X POST http://127.0.0.1:8765/api/secrets/delete \
  -H "Content-Type: application/json" -d '{"name":"EXAMPLE_TOKEN"}'
```

Names accept letters, digits, `_` and `-`; values are trimmed and capped at
20,000 characters. Secrets live in the local `config.json` vault. Public release
packaging blanks the vault.

### POST `/api/api-call`

Universal outbound HTTP client used by the agent tool `codeforge_api`.

| Field | Type | Notes |
| --- | --- | --- |
| `method` | string | GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS (default GET) |
| `url` | string | `http://` or `https://` only |
| `headers` | object or JSON string | Caller headers; `Authorization`, `Cookie` and `Set-Cookie` are not echoed back |
| `query` | object | URL-encoded query parameters |
| `body` | object, array or string | Objects/arrays are sent as JSON; a content type is added when absent |
| `secret_name` | string | Name in the vault; alias `secret` |
| `auth` | string | `bearer` (default), `token`, `header` or `query` |
| `header`, `param`, `prefix` | string | Header/query name and optional value prefix for the injected secret |
| `timeout` | integer | 5–120 seconds, default 30 |
| `max_chars` | integer | Response truncation limit, 1,000–400,000, default 60,000 |

The response contains `ok`, `status`, `method`, `url`, `content_type`, filtered
`headers`, `body`, `truncated`, `bytes`, parsed `json` when applicable,
`duration_ms`, and either `secret_used` or `secret_missing`. Secret values are
never included.

### GET `/api/publish/status` and POST `/api/publish`

`status` returns `github_token`/`gitcode_token` booleans, `github_source`
(`vault`, `environment`, `github-cli` or `missing`), `release_folder`,
`default_tag`, and default repository names. Token values are never returned.

`publish` creates or updates a release on GitHub or GitCode and uploads files
from the release folder:

```bash
curl -s -X POST http://127.0.0.1:8765/api/publish \
  -H "Content-Type: application/json" \
  -d '{"platform":"github","owner":"IAMMEZSH/CodeForge-AI","tag":"v1.0.8","name":"CodeForge AI v1.0.8","body":"…","folder":"release","overwrite":true,"delete_older":true}'
```

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `platform` | string | `github` | `github` or `gitcode` |
| `owner` / `repo` | string | status defaults | `owner/repo` accepted in either field |
| `tag`, `name`, `body` | string | version tag | Release identity and notes |
| `target` | string | default branch | Commit/branch target |
| `folder` | string | `release` | Files to upload (recursive; regular files only) |
| `create_repo` | boolean | `false` | Create the repository if missing |
| `create_release` | boolean | `true` | Create the release when absent |
| `overwrite` | boolean | `true` | Replace assets with the same name |
| `delete_older` | boolean | `false` | Delete older releases after publishing where supported |
| `draft`, `prerelease`, `private` | boolean | `false` | Platform flags |

Response: `ok`, `platform`, `release_url`, `uploaded[]`, `skipped[]`,
`deleted[]`, `steps[]` and `duration_ms`. Every step records action, success
and detail. Tokens are read from the vault, environment, GitHub CLI or Git
credential helper and are never logged.
