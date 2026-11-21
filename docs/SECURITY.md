# CodeForge AI — Security

Version 1.2.1 · Last reviewed 2026-11-20

CodeForge AI is a local tool that **compiles and executes code on purpose**.
Any honest security document for such a tool starts with the same sentence:
the local API is a code-execution endpoint, and its security model is the
security of your user account. This document describes what is defended,
what is not, and how to deploy the tool without creating a hole.

---

## 1. Supported versions

| Version | Security fixes |
| --- | --- |
| 1.1.x | Current line — fixes land here |

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
| Command/tool inventory and installed application names | Medium | generated at runtime, loopback API and agent prompt |
| Tool installation logs | Medium | `logs/provision-*.log` |
| Model files | Medium (large, re-downloadable) | `models/downloads/` |
| Download and run logs | Medium | `logs/`, `workspace/*/report.json` |
| Local ports 8765 and 8080 | High (code execution, inference) | loopback |
| Files outside the project (whole-disk access) | Critical (read/write/delete when enabled) | whichever paths you permit; gated by `permissions.full_filesystem` |
| SMTP password and mail content | High | KeyStore secret + outbound SMTP |
| Downloaded software, games and documents | High (may be executable or malicious) | `downloads/` and chosen target folders |
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
- Upstream file size and LFS SHA-256 are resolved from the configured mirror
  metadata (ModelScope first, then `hf-mirror.com`, with `huggingface.co` as
  the final fallback) before download. A response without both values is
  rejected.
- The `.part` file is hashed after the byte count matches; on mismatch the job
  fails and nothing is installed.
- Only after verification is the file atomically moved to `.gguf` and recorded
  in `models/installed.json`; capability verdicts live in `models/capability.json`.
- Runtime binaries come from official llama.cpp GitHub releases and are hashed
  into `runtime/runtime.json`.
- Downloads are anonymous; no Hugging Face token is requested or stored.

Residual risk: the hash and the file come from the same upstream over the same
TLS connection. A compromised upstream account could serve a matching malicious
file. Keep llama.cpp current and prefer well-known repositories.

### 4.6 Data handling

- No analytics, crash reporting, telemetry, or fingerprinting.
- The only automatic request is the release-index version check that powers
  in-app update notices. It reads release metadata from GitCode
  (`api.gitcode.com`, preferred on Chinese systems) or GitHub
  (`api.github.com`, fallback), sends no prompt, file, key or usage data, and
  can be disabled with `"updates": {"enabled": false}` in `config.json`.
- No other outbound request except: model downloads (domestic mirror with
  Hugging Face fallback), runtime downloads (GitHub), the configured LLM
  backend (loopback by default), the optional online LLM you configure,
  opt-in web research, download-center URL/search requests, configured SMTP,
  and the release-index update check.
- Chat history and code are not uploaded by CodeForge itself. They are written
  to `workspace/` and — by design, so the offline brain remembers — to
  `memory/memories.jsonl`.
- Memory is plain JSONL, unencrypted, readable by anything running as you. It
  contains user prompts, model replies and source tags. Treat it like a diary
  of your work.
- Debug outcomes are stored as `note` entries; online answers as `lesson`
  entries. There is no automatic expiry beyond the `max_entries` prune, so
  delete `memory/memories.jsonl` to erase the accumulated brain.
- Logs contain command lines and paths, not model prompts or API keys. They may
  contain fragments of your code if a compiler echoes it — treat `logs/` as
  sensitive.

### 4.7 Persistent memory and online learning

| Mechanism | What leaves the machine | Default |
| --- | --- | --- |
| Local chat | nothing | on |
| Online brain | prompt + injected memory/workspace context + recent messages to your provider | off while `llm.online.api_key` is empty |
| Web research | the search query (never the full prompt) to the search engine | on when `online.enabled` |

Consequences worth stating plainly:

- When the online brain is active, the provider sees the memory hits and
  workspace snippets injected into the prompt. Do not configure a provider you
  would not paste those files into.
- Search results are untrusted text. They are truncated, relevance-filtered and
  inserted under a “web findings” heading with an explicit instruction to treat
  them as evidence rather than commands; prompt injection is reduced, not
  eliminated.

### 4.8 Desktop and mobile shells

- The Win32 shell does not request elevation. It writes only to the project
  directory and starts Edge in app mode pointed at loopback.
- The Android client enables JavaScript and DOM storage, loads one configured
  URL, and allows cleartext HTTP on purpose for LAN use. Use it on trusted
  networks or put TLS in front.
- The iOS client has the same posture (ATS exceptions for local networking).
  Both thin clients are display layers; they do not store model data.

### 4.9 Command agent and tool provisioning

- Every command runs as the current user through PowerShell, cmd, bash, sh or
  zsh. The process tree is bounded by the configured timeout and killed as a
  tree; that is resource control, not a privilege boundary.
- A missing command is downloaded from the native package manager or a
  portable toolchain, PATH is refreshed, and the command is retried once.
  Downloads use the same TLS and integrity expectations as model/runtime
  downloads; package managers verify their own signatures where available.
- The publish guard blocks remote/public actions by default: `git push`, release
  and PR/issue creation, registry and container pushes, `scp`/`rsync`/`sftp`,
  cloud deploys and outbound write HTTP methods. It is a policy layer that can
  be disabled with `agent.allow_publish`; it does not sandbox an already-running
  program, and a locally allowed script can still open network connections.
- The agent receives an inventory of detected commands and installed
  applications. That inventory stays on loopback unless online routing is
  active, in which case the prompt (including the inventory) goes to the
  configured provider.
- Web pages returned by search, fetch and learn are untrusted data. The prompt
  labels them as evidence and forbids following their instructions; prompt
  injection is reduced, not eliminated.

### 4.10 Static inspection and the secret vault

**Static inspection.** `/api/inspect` and `codeforge_inspect` never pass a
sample to a compiler, interpreter, loader, shell or debugger. The implementation
opens files for reading, hashes them, extracts text/binary facts and writes a
report. It does not create a process from the sample. Consequently a malicious
file cannot execute merely because it was inspected. The one destructive
operation available is shredding, and it is restricted to quarantine copies
created by that same request; the original path and bytes are not touched.
After analysis, each source file is re-hashed and compared with the scan-time
SHA-256. `integrity.content_verified` is true only when every scanned file was
checked and none changed or became unreadable; otherwise the response says so.
A 256 MB re-hash budget bounds the extra I/O.

**Quarantine.** Risky files (score 70+) are copied to
`workspace/quarantine/` with a read-only DOS attribute and a `.meta.json`
sidecar. The copy is data, not a runnable package; nothing auto-runs it. The
shred step overwrites the copy with random bytes, forces it to disk, truncates
and deletes it, then records the action in the sidecar. On SSDs, wear levelling
and the flash translation layer mean a software overwrite is not proof of
physical destruction; the report discloses this.

**Named secrets.** `POST /api/secrets` stores values in the local
`config.json` vault. `GET /api/secrets` returns names, masked hints and lengths
only. `POST /api/api-call` and `codeforge_api` can inject a named secret into a
header or query parameter; responses never contain the value, and
`Authorization`, `Cookie` and `Set-Cookie` response headers are stripped before
the result is returned or logged. Release packaging blanks the vault, including
`llm.keys`, in every shipped `config.json`.

**Publish tokens.** The publisher probes the vault, environment, GitHub CLI and
the local Git credential helper, in that order. `/api/publish/status` reports
only token presence and source, never a value. Publishing is an explicit
button/API action; the agent's general publish guard remains separate.

### 4.11 Game engine adapters

`/api/engines` and `codeforge_engine` do not ship or download an engine. They
locate an engine you installed, read the project's marker files and invoke the
engine's own command-line tools with a bounded timeout. This keeps the trust
boundary in the engine vendor's installer and licensing, and means the adapter
adds no new download channel.

Engine builds and exports execute code from the game project (editor scripts,
build hooks, plugins and asset importers) under your user account, exactly like
running a compiler on source you supplied. Treat an unknown game project the
same way as unknown source code: review it, or build it in a VM or sandbox.
Detection only reads executables, paths and version strings; it never runs a
project merely to inspect it. A missing engine returns an install hint and does
not attempt a silent install.

### 4.12 Whole-disk file access

- `permissions.full_filesystem` defaults to `false`. Every list, search and
  file operation returns `ok:false` with `permission_required:true` until the
  user enables it in Settings or through `POST /api/permissions`.
- With the permission enabled, operations run with the user's own OS rights.
  The service does not elevate, impersonate or bypass ACLs. Files the OS
  refuses are reported as errors.
- Deletes require `confirm:true` and move the entry to `workspace/.trash/fs`
  unless `permanent:true` is explicitly set. Rename/copy/move refuse an
  existing destination unless `overwrite:true`.
- A guarded-directory list blocks destructive operations against Windows
  system folders, program directories and the installation itself.
- Every operation, including permission toggles and denied attempts, is
  appended to `logs/file-access.jsonl` with time, action, target and result.
- The Office service can open and search documents by path without this
  permission, because opening a document is the core feature; the same OS ACLs
  still apply. Enable whole-disk access only when you actually need file
  management beyond opening documents.

### 4.13 Mail and SMTP credentials

- The SMTP password is stored in the local encrypted KeyStore under
  `config.json.mail.secret_name` (default `codeforge-smtp`). It is never
  written to `config.json` in clear, never returned by `GET /api/mail/config`
  and never logged.
- `POST /api/mail/test` sends a real short test message (to the default
  recipient, or the sender when no default is set). It is not a header-only
  probe.
- Sending happens only when a person or the agent explicitly calls the send
  endpoint. Attachments are read from paths the caller supplies, up to 25 MB
  total, and leave the machine through the configured SMTP server.
- SMTP traffic is only as private as the configured server and its TLS mode.
  `security:"none"` transmits credentials and content in clear; use SSL or
  STARTTLS unless the server is local.

### 4.14 Download center

- Direct URLs are fetched over HTTP/HTTPS as supplied. The service does not
  silently upgrade an HTTP URL to HTTPS, so verify the source before running an
  installer.
- Search mode queries external engines, probes candidate links and ranks them;
  ranking is a heuristic, not a malware verdict. An `expected_sha256` turns the
  download into a verified transfer; without it, the reported SHA-256 is only
  informative.
- Completed files are written to the chosen folder with the chosen or derived
  name. They are not executed, opened or registered by the download service.
- A stalled body is aborted after 25 seconds and retried on the same URL; a
  failure leaves the `.part` file for inspection or resume.
- Whole-disk access is not required to write into the project `downloads/`
  folder; writing to arbitrary target folders uses the normal OS permissions
  and should be treated with the same care as any other file write.

---

## 5. What is NOT defended

| Risk | Detail | Mitigation for you |
| --- | --- | --- |
| Arbitrary code execution | `/api/run`, `/api/debug`, `/api/agent/run` and the compiler run your code as you | Run untrusted code in a VM, Windows Sandbox, container, or dedicated low-privilege user |
| Guard bypass through an allowed program | The publish guard filters command text, but an already-running program can use the network directly | Keep `agent.allow_publish` off; review agent commands; use OS firewall rules for hard egress control |
| Unreviewed tool installation | Auto-provisioning downloads and executes package-manager or portable installers as you | Keep `agent.auto_install_tools` off for sensitive work, or review the install log first |
| Same-user malware | Any process under your account can call the API | Keep the machine clean; use a separate account for experiments |
| Exposed ports | Binding is loopback, but tunnels/reverse proxies can expose it | If exposing, add authentication and TLS; never port-forward 8765 directly |
| llama.cpp vulnerabilities | GGUF parsing is a native attack surface | Use official releases, keep the runtime updated |
| Supply chain | TLS + SHA-256 from the same origin | Pin versions, verify release assets, prefer official repos |
| Resource exhaustion | A 30B model can exhaust RAM/disk | Choose models that fit; watch free space; stop the runtime when idle |
| Denial of service on yourself | Huge prompts, long generations, runaway programs | Timeouts and caps exist, but agents can still consume CPU/RAM |
| Data at rest | Workspace, memory and models are unencrypted | Use full-disk encryption (BitLocker/FileVault/LUKS); delete `memory/` to reset the brain |
| Memory poisoning | A wrong or malicious lesson is recalled later with high weight | Review `memory/memories.jsonl`, delete bad lines, or set `memory.enabled` to false |
| Prompt injection via research | Untrusted snippets enter the prompt | Results are filtered and labelled as evidence; keep `online.research` off for sensitive work |
| Untrusted game project | Engine build/export executes project scripts and plugins as you | Build unknown projects in a VM or sandbox; review project files first |
| Whole-disk operations | With the permission on, the API can read, move or delete any file your account can reach | Keep the permission off when not needed; review `logs/file-access.jsonl`; keep backups |
| Malicious Office/PDF document | Parsing libraries handle untrusted files inside the JVM; a crafted document can exhaust CPU/memory or exploit a parser bug | Open documents you trust; keep the JAR/upstream libraries current; avoid untrusted files on a production machine |
| Malicious download | The download center transfers files but does not scan them; a wrong candidate can be malware | Prefer official sources, set `expected_sha256`, scan installers before running them |
| Mail misdelivery | Sending uses whatever recipients and attachments the caller supplies | Review the compose form or agent tool call; keep the SMTP account scoped and monitored |
| Online provider logging | Prompts, memory hits and snippets reach a third party | Leave `llm.online.api_key` empty for fully local operation |

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
- [ ] Leave **Full filesystem access** off unless you actively need it, and
      review `logs/file-access.jsonl` after enabling it.
- [ ] Use a dedicated SMTP app password (never your main account password) and
      prefer SSL or STARTTLS; clear the stored secret in the Mail view when you
      stop using it.
- [ ] Verify downloads by source and, where possible, by SHA-256 before running
      an installer.

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
| `config.json → secrets` | plain text vault | Named tokens for `codeforge_api` and publishing; the API returns masked hints only. Protect the file and blank the vault before sharing a copy |
| `GITHUB_TOKEN`, `GH_TOKEN` | environment / vault / GitHub CLI keyring | Used only by the publisher; never returned by `/api/publish/status` |
| `GITCODE_TOKEN`, `GITCODE_ACCESS_TOKEN` | environment / vault | Used only by the publisher; create a scoped personal access token |
| SMTP password / authorization code | local KeyStore (`config.json.mail.secret_name`) | Use an app password, prefer SSL/STARTTLS, clear it when unused; never returned by the API |
| Hugging Face token | not used | Do not add one to the repo |
| TLS keys | not applicable | Add at your proxy, never in this repo |

The server never logs API keys, vault values or publish tokens. Command lines
logged for debugging may include model paths and flags, not secrets. If you
switch to a paid gateway, keep usage caps on the gateway side. Release packaging
blanks `llm.api_key`, `llm.online.api_key`, `llm.keys` and the whole `secrets`
object in every shipped `config.json`.

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
| `modelscope.cn`, `hf-mirror.com` (fallback), `huggingface.co` (final fallback), `cdn-lfs*.hf.co`, `cas-bridge.xethub.hf.co` | model download/resolve | anonymous HTTPS GET |
| `api.github.com`, `github.com`, `objects.githubusercontent.com` | runtime install | anonymous HTTPS GET |
| Configured `openai_url` (default `127.0.0.1:8080`) | chat/debug | prompts and code |
| Configured `ollama_url` (default `127.0.0.1:11434`) | chat/debug | prompts and code |
| Configured `llm.online.base_url` (DeepSeek by default) | chat/debug when an online key is set | prompts, code, injected memory, workspace context and tool/application inventory |
| Google, Baidu, Baidu Zhidao, DuckDuckGo, Bing, 360, Wikipedia | web research, agent search/fetch/learn | the search query or requested URL only |
| Native package managers and their mirrors | automatic tool provisioning | package names and downloads |
| Any host passed to `/api/api-call` or `codeforge_api` | explicit API call | method, URL, headers, query and body supplied by the caller; the injected secret stays in the request only |
| `api.github.com`, `uploads.github.com`, `api.gitcode.com` | Publish tab or approved publish action | repository, release metadata and uploaded asset bytes |
| Any host the agent is explicitly asked to fetch or publish to | agent web tools or an approved publish action | the command or content the task requires |
| Download-center URL or its search engines/results hosts | explicit download or resolve | the URL or search query, then file bytes |
| Configured SMTP server | explicit mail send/test | message, recipients, attachments and SMTP login |
| `api.gitcode.com`, `api.github.com` (update check) | server start and every 6 hours when enabled | release metadata request only; no prompt, file or key |
| Game engine vendors | never by CodeForge | no traffic: engines are user-installed and never downloaded or bundled |

If you point `openai_url` or `llm.online.base_url` at a cloud provider, **your
code and prompts leave the machine**. That is a configuration decision, not a
default. Set `CODEFORGE_ONLINE=0` to disable all research while keeping the
local model working.

---

## 9. Audit and forensics

| Question | Where to look |
| --- | --- |
| What code ran? | `workspace/debug-*/`, `workspace/run-*/` |
| Did it succeed? | `report.json` per session |
| Which model answered? | `report.json`, `logs/llama-server.log`, `/api/status`, chat `meta.brain` |
| What does the offline brain remember? | `memory/memories.jsonl` |
| Which files are indexed? | `memory/project-index.jsonl`, `GET /api/workspace?stats=1` |
| Who called the API? | Server does not log clients by default; enable OS-level connection auditing if needed |
| Was a download verified? | `models/installed.json` `sha256`, `runtime/runtime.json` `sha256`, download job `sha256`/`verify_note` |
| Who touched files outside the project? | `logs/file-access.jsonl` |
| What was deleted? | `workspace/.trash/office/`, `workspace/.trash/fs/` |
| Was mail sent? | the Mail view result, server log line and the SMTP provider's sent folder |
| Is something listening? | `netstat -ano` / `ss -ltnp` |

The server keeps a small in-memory job list; no persistent event trail is kept
beyond workspaces, logs and memory. For real auditing, ship `workspace/`,
`logs/` and `memory/` to your own storage.

---

## 10. Incident response

1. Stop the runtime: `POST /api/runtime/stop` or kill `llama-server`.
2. Stop the server: `POST /api/shutdown` or close the shell/tray.
3. Kill any remaining compiler/user processes (`taskkill /T /F` on Windows,
   `pkill -P` / process group kill on POSIX).
4. Rotate any API key you put in `config.json` (`llm.api_key`,
   `llm.online.api_key`) or in the environment.
5. Review `workspace/`, `logs/` and `memory/` for unexpected commands, files,
   remembered lessons or audit lines. Check `logs/file-access.jsonl` for
   whole-disk activity.
6. Re-verify model and runtime hashes; delete and re-download anything modified.
7. If a malicious page is suspected, review browser downloads and recent
   console sessions, then check for new files in the project root.
8. If the port was exposed, review firewall and proxy logs, then close it.
9. Turn off **Full filesystem access**, cancel active downloads and clear the
   stored SMTP password; rotate the SMTP app password at the provider.

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
- **No telemetry** is a product promise and removes an entire class of data
  leakage.

---

## 12. Related documents

- [API reference](API.md) — including the write-request guard
- [Architecture](ARCHITECTURE.md) — process and trust boundaries
- [Platform guide](PLATFORMS.md) — firewall commands per OS
- [Manual](MANUAL.md) — safe operating workflow
