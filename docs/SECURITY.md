# CodeForge AI — Security

Version 1.0.0 · Last reviewed 2026-11-15

CodeForge AI is a local tool that **compiles and executes code on purpose**.
Any honest security document for such a tool starts with the same sentence:
the local API is a code-execution endpoint, and its security model is the
security of your user account. This document describes what is defended,
what is not, and how to deploy the tool without creating a hole.

---

## 1. Supported versions

| Version | Security fixes |
| --- | --- |
| 1.0.x | Current line — fixes land here |

Only the latest release receives fixes. There is no LTS branch.

## 2. Reporting a vulnerability

- Use the repository's **private security advisory** feature (GitHub:
  *Security → Advisories → Report a vulnerability*). On GitCode, use a private
  issue if advisories are unavailable.
- Do **not** include working exploits in public issues.
- Include: version, OS, exact request or code snippet, observed behavior,
  expected behavior, and whether the server was reachable from another host.
- Please allow time for a fix before disclosure. There is no bug bounty.

---

## 3. Threat model

### 3.1 Assets

| Asset | Sensitivity | Where it lives |
| --- | --- | --- |
| Source code you paste into the tool | High | `workspace/`, browser memory, model context |
| API keys for external OpenAI-compatible gateways | High | `config.json` or environment |
| Persistent memory (chat text, lessons, preferences) | High | `memory/memories.jsonl` |
| Workspace index (your file names and opening lines) | High | `memory/project-index.jsonl` |
| Compliance audit trail | Medium | `memory/compliance.log` |
| Model files | Medium (large, re-downloadable) | `models/downloads/` |
| Download and run logs | Medium | `logs/`, `workspace/*/report.json` |
| Local ports 8765 and 8080 | High (code execution, inference) | loopback |
| Host integrity | Critical | the whole process tree |

### 3.2 Adversaries considered

| Adversary | Capability | In scope? |
| --- | --- | --- |
| Malicious website open in your browser | Can send cross-site requests to `127.0.0.1`, cannot read responses without CORS | **Yes** — mitigated |
| Another unprivileged local process/user | Can connect to loopback ports | **Partially** — same-user processes are trusted |
| Someone on the LAN | Can scan and connect if ports are exposed | **Out of default scope** — loopback binding |
| Malicious or corrupted model file | Crafted GGUF exploiting llama.cpp | **Out of scope** — trust boundary is llama.cpp |
| Compromised upstream download | Serves a different file | **Partially** — TLS + pinned repo paths + SHA-256 from the same origin |
| Code you deliberately run | Full user privileges | **Out of scope** — this is the feature |
| Remote attacker via tunnel/reverse proxy | Full API access if unauthenticated | **Out of scope by default** — do not expose without auth |

### 3.3 Trust boundaries

```
TRUSTED     your user account, the project directory, the JVM, the C++ core
BOUNDARY    loopback HTTP (127.0.0.1:8765, 127.0.0.1:8080)
UNTRUSTED   web pages, LAN hosts, model files, downloaded binaries, code you run
```

---

## 4. Defenses implemented

### 4.1 Network exposure

- `HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64)` — the Java
  server binds loopback only, not `0.0.0.0`.
- `llama-server` is started with `--host 127.0.0.1`.
- No UPnP, no port forwarding, no cloud relay, no telemetry.

### 4.2 Cross-site request protection

A malicious page can POST to `http://127.0.0.1:8765` even without reading the
response. Because `/api/run` and `/api/debug` execute code, that would be
enough to do damage. The server therefore enforces, for every method other than
`GET`:

1. `Content-Type` must start with `application/json` — otherwise `415`.
   `application/json` is not a CORS-safelisted content type, so a cross-site
   request triggers a preflight that this server never approves.
2. If an `Origin` header exists, it must equal `http://` + the request `Host`,
   otherwise `403`. The console, mobile thin clients and same-origin tools pass;
   arbitrary websites fail.

Limitations: this is CSRF hardening, not authentication. A same-user process
can still call the API directly, and a sufficiently exotic client that omits
both headers (`curl`) is allowed by design. Anyone who exposes the port to a
network must add real authentication in front of it.

### 4.3 Request and path handling

- Request bodies are capped at 8 MB; `Content-Length` is parsed and enforced
  while streaming.
- Static file serving rejects any path containing `..` and resolves only inside
  `server-java/web/`.
- Generated previews are served only from `workspace/run-*` or
  `workspace/debug-*` session folders. The route regex rejects `..`,
  backslashes and leading slashes; responses carry a CSP `sandbox` directive
  and `no-store`.
- `/api/files/write`, `/api/files/write-batch`, `/api/desktop/install` and
  `/api/project/place` intentionally write to user-provided paths. That is the
  feature, not a bug: any local process that can reach the API can write files
  as your user, so the loopback port must never be exposed to a network you do
  not control. Missing folders are reported and require explicit confirmation;
  Windows reserved names, invalid characters and nonexistent drives are
  rejected. The project exporter refuses to copy onto itself or into a folder
  inside the project and excludes models, runtime, workspace, logs, memory and
  build caches.
- The core rejects code larger than 200 KB and enforces JSON object shapes.
- Output from user programs is truncated at the configured cap; the truncation
  marker is appended rather than silently losing data.

### 4.4 Process containment

The runner kills the whole process tree, not just the direct child:

| OS | Mechanism |
| --- | --- |
| Windows | Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, `TerminateJobObject` on timeout |
| Linux/macOS/Android/iOS | `setpgid(0,0)` process group, `kill(-pid, SIGKILL)` on timeout |

This prevents an infinite loop, a fork bomb's direct children, or a hung
compiler from outliving the request. **It is a resource control, not a
sandbox.** Code still runs with your UID and can read your files, use the
network, and persist changes.

### 4.5 Download integrity

- HTTPS only, certificate validation by the JVM/OS trust store.
- Upstream file size and LFS SHA-256 are resolved from Hugging Face metadata
  before download.
- The `.part` file is hashed after the byte count matches; on mismatch the job
  fails and nothing is installed.
- Only after verification is the file atomically moved to `.gguf` and recorded
  in `models/models.json`.
- Runtime binaries come from official llama.cpp GitHub releases and are hashed
  into `runtime/runtime.json`.
- Downloads are anonymous; no Hugging Face token is requested or stored.

Residual risk: the hash and the file come from the same upstream over the same
TLS connection. A compromised upstream account could serve a matching malicious
file. Keep llama.cpp current and prefer well-known repositories.

### 4.6 Data handling

- No analytics, crash reporting, telemetry, update checks, or fingerprinting.
- No outbound request except: model downloads (Hugging Face), runtime downloads
  (GitHub), the configured LLM backend (loopback by default), the optional
  online LLM you configure, and law/geolocation research when enabled.
- Chat history and code are not uploaded by CodeForge itself. They are written
  to `workspace/` and — by design, so the offline brain remembers — to
  `memory/memories.jsonl`.
- Memory is plain JSONL, unencrypted, readable by anything running as you. It
  contains user prompts, model replies, source tags and optional jurisdiction
  codes. Treat it like a diary of your work.
- Debug outcomes are stored as `note` entries; online answers as `lesson`
  entries. There is no automatic expiry beyond the `max_entries` prune, so
  delete `memory/memories.jsonl` to erase the accumulated brain.
- Logs contain command lines and paths, not model prompts or API keys. They may
  contain fragments of your code if a compiler echoes it — treat `logs/` as
  sensitive.

### 4.7 Persistent memory, online learning and compliance

| Mechanism | What leaves the machine | Default |
| --- | --- | --- |
| Local chat | nothing | on |
| Online brain | prompt + injected memory/workspace context + recent messages to your provider | off while `llm.online.api_key` is empty |
| Web research | the search query (never the full prompt) to the search engine | on when `online.enabled` |
| Geolocation | the public IP is seen by `ipwho.is` / `ipapi.co` | on; can be pinned with `compliance.jurisdiction` |
| Law refresh | a country/year law query to the search chain | on when `online.refresh` |
| Compliance audit | local only: timestamp, phase, verdict, flags, jurisdiction, text length, 8-byte SHA-256 prefix | on |

Consequences worth stating plainly:

- When the online brain is active, the provider sees the memory hits and
  workspace snippets injected into the prompt. Do not configure a provider you
  would not paste those files into.
- Search results are untrusted text. They are truncated, relevance-filtered and
  inserted under a “web findings” heading with an explicit instruction to treat
  them as evidence rather than commands; prompt injection is reduced, not
  eliminated.
- Compliance screening runs before the model and again on its output. It is a
  guardrail against clearly unlawful requests, not a guarantee: the verdict is
  lexical and jurisdictional, and a lawful-looking request can still be harmful
  in context. The user remains responsible.
- The audit log stores a hash prefix of the screened text, not the text, so it
  cannot be used to reconstruct prompts. It also rotates at 512 KB.

### 4.8 Desktop and mobile shells

- The Win32 shell does not request elevation. It writes only to the project
  directory and starts Edge in app mode pointed at loopback.
- The Android client enables JavaScript and DOM storage, loads one configured
  URL, and allows cleartext HTTP on purpose for LAN use. Use it on trusted
  networks or put TLS in front.
- The iOS client has the same posture (ATS exceptions for local networking).
  Both thin clients are display layers; they do not store model data.

---

## 5. What is NOT defended

| Risk | Detail | Mitigation for you |
| --- | --- | --- |
| Arbitrary code execution | `/api/run`, `/api/debug` and the compiler run your code as you | Run untrusted code in a VM, Windows Sandbox, container, or dedicated low-privilege user |
| Same-user malware | Any process under your account can call the API | Keep the machine clean; use a separate account for experiments |
| Exposed ports | Binding is loopback, but tunnels/reverse proxies can expose it | If exposing, add authentication and TLS; never port-forward 8765 directly |
| llama.cpp vulnerabilities | GGUF parsing is a native attack surface | Use official releases, keep the runtime updated |
| Supply chain | TLS + SHA-256 from the same origin | Pin versions, verify release assets, prefer official repos |
| Resource exhaustion | A 30B model can exhaust RAM/disk | Choose models that fit; watch free space; stop the runtime when idle |
| Denial of service on yourself | Huge prompts, long generations, runaway programs | Timeouts and caps exist, but agents can still consume CPU/RAM |
| Data at rest | Workspace, memory and models are unencrypted | Use full-disk encryption (BitLocker/FileVault/LUKS); delete `memory/` to reset the brain |
| Memory poisoning | A wrong or malicious lesson is recalled later with high weight | Review `memory/memories.jsonl`, delete bad lines, or set `memory.enabled` to false |
| Prompt injection via research | Untrusted snippets enter the prompt | Results are filtered and labelled as evidence; keep `online.research` off for sensitive work |
| Online provider logging | Prompts, memory hits and snippets reach a third party | Leave `llm.online.api_key` empty for fully local operation |
| Stale law pack | A jurisdiction summary can lag behind new legislation | Set `compliance.refresh_hours` low, or pin `compliance.jurisdiction` and verify with official sources |

---

## 6. Hardening checklist

### Personal workstation

- [ ] Keep the project under a user profile with disk encryption enabled.
- [ ] Do not expose 8765 or 8080 in Windows Firewall / router rules.
- [ ] Run `netstat -ano | findstr :8765` (Windows) or
      `ss -ltnp | grep 8765` (Linux) if you suspect unexpected listeners.
- [ ] Review `workspace/` periodically and delete old sessions containing
      proprietary code.
- [ ] Review `memory/memories.jsonl` periodically; delete it to start with a
      clean brain, or set `memory.enabled` to false for sensitive work.
- [ ] Store API keys in environment variables, not `config.json`.
- [ ] Leave `llm.online.api_key` empty unless you accept that prompts, memory
      hits and workspace snippets go to that provider.

### Running untrusted code

- [ ] Use Windows Sandbox, a VM, or a container with no host mounts.
- [ ] Run CodeForge as a dedicated user whose home does not contain other data.
- [ ] Disable network access for the sandbox if the code does not need it.
- [ ] Set low `run_timeout_ms` and `compile_timeout_ms` values.
- [ ] Keep the model runtime on the host but the runner in the sandbox where
      possible.

### Remote access

Never expose the raw API. If you must reach it remotely, use an SSH tunnel:

```bash
ssh -L 8765:127.0.0.1:8765 user@workstation
# then open http://127.0.0.1:8765/console/ locally
```

For a team gateway, put an authenticating reverse proxy in front, require TLS,
and accept that anyone with access can execute code as the server user. That is
equivalent to giving them a shell.

### Android/iOS thin clients

- [ ] Use them only on trusted Wi-Fi.
- [ ] Prefer the Termux/iSH route (server on-device) when you do not control the
      network.
- [ ] Remember that the URL is plain HTTP; an on-path attacker could inject
      content into the console.

---

## 7. Secrets

| Secret | Where | Recommendation |
| --- | --- | --- |
| `CODEFORGE_LLM_API_KEY` | environment | Preferred; not written to disk |
| `config.json → llm.api_key` | plain text | Only for a local mock; protect file ACLs |
| `CODEFORGE_ONLINE_LLM_KEY` | environment | Preferred over the next row |
| `config.json → llm.online.api_key` | plain text | Grants paid-cloud access; restrict ACLs, cap spend on the provider side |
| Hugging Face token | not used | Do not add one to the repo |
| TLS keys | not applicable | Add at your proxy, never in this repo |

The server never logs either API key. Command lines logged for debugging may
include model paths and flags, not keys. If you switch to a paid gateway, keep
usage caps on the gateway side.

Linux file permissions:

```bash
chmod 600 config.json
chmod 700 workspace logs models
```

Windows ACL (PowerShell, replace `<user>`):

```powershell
icacls config.json /inheritance:r /grant:r "<user>:(R,W)"
icacls workspace /inheritance:r /grant:r "<user>:(OI)(CI)(F)"
```

---

## 8. Network egress summary

| Destination | When | Data |
| --- | --- | --- |
| `huggingface.co`, `cdn-lfs*.hf.co` | model download/resolve | anonymous HTTPS GET |
| `api.github.com`, `github.com`, `objects.githubusercontent.com` | runtime install | anonymous HTTPS GET |
| Configured `openai_url` (default `127.0.0.1:8080`) | chat/debug | prompts and code |
| Configured `ollama_url` (default `127.0.0.1:11434`) | chat/debug | prompts and code |
| Configured `llm.online.base_url` (DeepSeek by default) | chat/debug when an online key is set | prompts, code, injected memory and workspace context |
| `ipwho.is`, `ipapi.co` | jurisdiction detection | public IP as seen by the service |
| Configured SearXNG endpoint, `duckduckgo.com`, `so.com`, `wikipedia.org` | research / law refresh | the search query only |

If you point `openai_url` or `llm.online.base_url` at a cloud provider, **your
code and prompts leave the machine**. That is a configuration decision, not a
default. Set `CODEFORGE_ONLINE=0` to disable all research, geo lookup and law
refresh while keeping the local model working.

---

## 9. Audit and forensics

| Question | Where to look |
| --- | --- |
| What code ran? | `workspace/debug-*/`, `workspace/run-*/` |
| Did it succeed? | `report.json` per session |
| Which model answered? | `report.json`, `logs/llama-server.log`, `/api/status`, chat `meta.brain` |
| What does the offline brain remember? | `memory/memories.jsonl` |
| Which files are indexed? | `memory/project-index.jsonl`, `GET /api/workspace?stats=1` |
| Was a request refused? | `memory/compliance.log` (verdict, flags, jurisdiction, hash prefix) |
| Who called the API? | Server does not log clients by default; enable OS-level connection auditing if needed |
| Was a download verified? | `models/models.json` `sha256`, `runtime/runtime.json` `sha256` |
| Is something listening? | `netstat -ano` / `ss -ltnp` |

The server keeps a small in-memory job list. The compliance audit log is the
only persistent event trail; it records decisions, not prompts or replies. For
real auditing, ship `workspace/`, `logs/` and `memory/` to your own storage.

---

## 10. Incident response

1. Stop the runtime: `POST /api/runtime/stop` or kill `llama-server`.
2. Stop the server: `POST /api/shutdown` or close the shell/tray.
3. Kill any remaining compiler/user processes (`taskkill /T /F` on Windows,
   `pkill -P` / process group kill on POSIX).
4. Rotate any API key you put in `config.json` (`llm.api_key`,
   `llm.online.api_key`) or in the environment.
5. Review `workspace/`, `logs/` and `memory/` for unexpected commands, files,
   remembered lessons or audit lines.
6. Re-verify model and runtime hashes; delete and re-download anything modified.
7. If a malicious page is suspected, review browser downloads and recent
   console sessions, then check for new files in the project root.
8. If the port was exposed, review firewall and proxy logs, then close it.

---

## 11. Design rationale

- **No authentication by default** is a deliberate choice for a single-user
  localhost tool. Passwords stored in a local app add complexity without
  protecting against the actual threat (same-user processes). The correct
  control is network isolation, which is why binding is hard-coded to loopback.
- **Content-Type and Origin checks** exist because browsers are the one
  adversary that routinely reaches loopback without the user's intent.
- **Process groups and Job Objects** protect availability, not confidentiality.
  The tool is honest about that distinction.
- **SHA-256 verification** protects against truncated or corrupted transfers,
  the most common real-world failure, not against a compromised upstream.
- **Memory is local, plain and deletable** because a “brain” you cannot audit or
  erase is a liability. It is never uploaded by CodeForge, and packaging excludes
  it so a build never carries someone else's prompts.
- **Compliance refuses rather than argues.** The baseline cannot be disabled by
  configuration; jurisdiction packs add detail. The audit log stores hashes, not
  text, so enforcement does not become a second copy of sensitive prompts.
- **No telemetry** is a product promise and removes an entire class of data
  leakage.

---

## 12. Related documents

- [API reference](API.md) — including the write-request guard
- [Architecture](ARCHITECTURE.md) — process and trust boundaries
- [Platform guide](PLATFORMS.md) — firewall commands per OS
- [Manual](MANUAL.md) — safe operating workflow
