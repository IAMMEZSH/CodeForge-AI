# Model Library

CodeForge ships a curated registry of **13 offline GGUF models** — a deliberate mix of Chinese and international releases chosen for coding quality, reasoning depth, and practical CPU performance. Nothing is downloaded until you ask for it.

All models are served locally by `llama.cpp`'s `llama-server` on `127.0.0.1:8080` through an OpenAI-compatible `/v1` API. Ollama and any external OpenAI-compatible endpoint can be used instead (see [Configuration](#configuration)).

---

## Registry at a glance

| # | Model | Vendor | Origin | Category | Params | Context | File size | Min RAM | License |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Qwen3.6 35B A3B | Alibaba Qwen | China | Agentic coding MoE | 35B MoE · 3B active | 262K | ≈ 22.1 GB | 28 GB | Apache-2.0 |
| 2 | Qwen3.8 27B | Alibaba Qwen | China | Dense coding flagship | 27B | 262K | ≈ 16.6 GB | 22 GB | Apache-2.0 |
| 3 | Qwen3-Coder 30B-A3B | Alibaba Qwen | China | Agentic flagship | 30B MoE · 3B active | 256K | ≈ 18.6 GB | 24 GB | Apache-2.0 |
| 4 | DeepSeek-Coder V2 Lite | DeepSeek | China | MoE efficiency | 16B MoE · 2.4B active | 128K | ≈ 10.4 GB | 14 GB | DeepSeek License |
| 5 | DeepSeek R1 0528 Qwen3 8B | DeepSeek | China | Reasoning | 8B | 128K | ≈ 5.0 GB | 8 GB | MIT |
| 6 | Qwen3 4B Instruct 2507 | Alibaba Qwen | China | Lightweight | 4B | 256K | ≈ 2.5 GB | 6 GB | Apache-2.0 |
| 7 | CodeGeeX4 ALL 9B | Zhipu AI | China | Code specialist | 9B | 128K | ≈ 6.3 GB | 9 GB | CodeGeeX4 License |
| 8 | Seed-OSS 36B Instruct | ByteDance Seed | China | Large context | 36B | 512K | ≈ 21.8 GB | 27 GB | Apache-2.0 |
| 9 | GPT-OSS 20B | OpenAI | Global | Reasoning MoE | 20B MoE · 3.6B active | 128K | ≈ 12.1 GB | 16 GB | Apache-2.0 |
| 10 | Devstral Small 2507 | Mistral AI | Global | Agentic SWE | 24B | 128K | ≈ 14.3 GB | 20 GB | Apache-2.0 |
| 11 | Phi-4 | Microsoft | Global | Reasoning | 14B | 16K | ≈ 9.1 GB | 12 GB | MIT |
| 12 | Gemma 3 27B IT | Google | Global | Generalist | 27B | 128K | ≈ 16.6 GB | 22 GB | Gemma License |
| 13 | Codestral 22B | Mistral AI | Global | Code completion | 22B | 32K | ≈ 13.3 GB | 18 GB | Mistral Non-Production |

Sizes are Q4-class quantizations resolved through ModelScope or the enabled mirror at download time and rounded; the exact byte count is shown in the console before and during download. RAM figures are practical guidance for CPU inference (model + KV cache + runtime overhead), not hard limits. With the CUDA backend most weights and the KV cache live in VRAM instead: a 4B Q4 model plus 16K context fits comfortably in 12 GB, while the larger 20–30B models need 24 GB or more of VRAM. Choose **CPU** in the runtime panel when the GPU is smaller than the model's working set.

---

## Detailed entries

### 1. Qwen3.6 35B A3B — `qwen3.6-35b-a3b`

- **Repository:** `unsloth/Qwen3.6-35B-A3B-GGUF`
- **File:** `Qwen3.6-35B-A3B-UD-Q4_K_M.gguf`
- **Why it is here:** the newest agentic-coding MoE with native tool calling. Reported SWE-bench Verified 73.4 and Terminal-Bench 2.0 51.5; only 3B parameters are active per token, so a 262K context remains practical on CPU.
- **Best for:** repository-scale agent work, tool protocols, hard multi-file fixes.
- **Trade-off:** 22.1 GB on disk and 28 GB of RAM recommended; the largest working set in the registry beside Seed-OSS 36B.
- **Badge:** marked **Recommended** in the console.

### 2. Qwen3.8 27B — `qwen3.8-27b`

- **Repository:** `unsloth/Qwen3.8-27B-GGUF`
- **File:** `Qwen3.8-27B-UD-Q4_K_M.gguf`
- **Why it is here:** the 2026 dense coding flagship, with a reported 61.7 on SWE-bench Pro. It provides the highest single-model quality in the catalog when memory allows.
- **Best for:** quality-first local coding on 24 GB+ workstations.
- **Trade-off:** a dense 27B model decodes slower on CPU than a 3B-active MoE; give it a fast CPU or a CUDA GPU.

### 3. Qwen3-Coder 30B-A3B — `qwen3-coder-30b-a3b`

- **Repository:** `unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF`
- **File:** `Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf`
- **Why it is here:** the flagship open agentic coder. Excels at repository-scale refactors, tool use, and multi-file fixes. Sparse MoE (3B active) keeps CPU decoding usable.
- **Best for:** hard refactors, architectural changes, long agentic sessions.
- **Trade-off:** 18.6 GB on disk and 24 GB of RAM recommended; slow on low-end CPUs.
- **Badge:** marked **Recommended** in the console.

### 4. DeepSeek-Coder V2 Lite — `deepseek-coder-v2-lite`

- **Repository:** `bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF`
- **File:** `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf`
- **Why it is here:** sparse MoE coder with 128K context. Only 2.4B parameters are active per token, so it stays quick on CPU while handling large files.
- **Best for:** everyday code generation and review with large context on modest hardware.
- **License note:** released under the DeepSeek License, not Apache/MIT. Review the model card before commercial use.

### 5. DeepSeek R1 0528 Qwen3 8B — `deepseek-r1-0528-qwen3-8b`

- **Repository:** `unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF`
- **File:** `DeepSeek-R1-0528-Qwen3-8B-Q4_K_M.gguf`
- **Why it is here:** distilled DeepSeek reasoning model. Exposes a chain of thought and is unusually strong at diagnosing logic and concurrency bugs for an 8B model.
- **Best for:** root-cause analysis, tricky logic bugs, explaining why code fails.
- **Trade-off:** reasoning traces increase token usage and latency.

### 6. Qwen3 4B Instruct 2507 — `qwen3-4b-instruct-2507`

- **Repository:** `unsloth/Qwen3-4B-Instruct-2507-GGUF`
- **File:** `Qwen3-4B-Instruct-2507-Q4_K_M.gguf`
- **Why it is here:** fast lightweight generalist with strong instruction following. Ideal for autocomplete, explanations, and machines with 8 GB of RAM.
- **Best for:** first download, laptops, quick Q&A, smoke tests.
- **Verified in this project:** this is the model used for the real end-to-end inference and debug-loop verification on Windows.

### 7. CodeGeeX4 ALL 9B — `codegeex4-all-9b`

- **Repository:** `bartowski/CodeGeeX4-ALL-9B-GGUF`
- **File:** `codegeex4-all-9b-Q4_K_M.gguf`
- **Why it is here:** Zhipu AI code specialist covering code generation, completion, and repository-level Q&A.
- **Best for:** completion-style work and Chinese/English mixed codebases.
- **License note:** CodeGeeX4 License. Review the model card before commercial use.

### 8. Seed-OSS 36B Instruct — `seed-oss-36b-instruct`

- **Repository:** `unsloth/Seed-OSS-36B-Instruct-GGUF`
- **File:** `Seed-OSS-36B-Instruct-Q4_K_M.gguf`
- **Why it is here:** ByteDance Seed model with a 512K-token context — the largest context in the registry.
- **Best for:** very large files, monorepo analysis, long documentation dumps.
- **Trade-off:** 21.8 GB and 27 GB RAM recommended; heavy for laptops.

### 9. GPT-OSS 20B — `gpt-oss-20b`

- **Repository:** `ggml-org/gpt-oss-20b-GGUF`
- **File:** `gpt-oss-20b-MXFP4.gguf`
- **Why it is here:** OpenAI's open-weight reasoning MoE (3.6B active), strong at structured reasoning and tool-style responses. Uses MXFP4 quantization.
- **Best for:** reasoning-heavy debugging, planning, and explanations on a 16 GB machine.
- **Notes:** marked **Recommended** in the console; the MXFP4 file is shown as `MXFP4` in the model card.

### 10. Devstral Small 2507 — `devstral-small-2507`

- **Repository:** `unsloth/Devstral-Small-2507-GGUF`
- **File:** `Devstral-Small-2507-Q4_K_M.gguf`
- **Why it is here:** Mistral's agentic software-engineering model, tuned for codebase navigation and multi-step edits.
- **Best for:** agent-style fix loops, repository tasks, patch generation.

### 11. Phi-4 — `phi-4`

- **Repository:** `bartowski/phi-4-GGUF`
- **File:** `phi-4-Q4_K_M.gguf`
- **Why it is here:** Microsoft's compact reasoning model with strong math and logic performance for its size.
- **Best for:** algorithmic problems, math-heavy code, concise explanations.
- **Trade-off:** 16K context — the smallest in the registry; avoid very large files.

### 12. Gemma 3 27B IT — `gemma-3-27b-it`

- **Repository:** `ggml-org/gemma-3-27b-it-GGUF`
- **File:** `gemma-3-27b-it-Q4_K_M.gguf`
- **Why it is here:** Google generalist with excellent instruction following and multilingual ability; a strong all-rounder beyond pure coding.
- **Best for:** mixed technical/general work, documentation, explanations.
- **License note:** Gemma License with use restrictions. Review the model card.

### 13. Codestral 22B — `codestral-22b`

- **Repository:** `bartowski/Codestral-22B-v0.1-GGUF`
- **File:** `Codestral-22B-v0.1-Q4_K_M.gguf`
- **Why it is here:** Mistral code model tuned for fill-in-the-middle and completion, with broad language coverage.
- **Best for:** code completion, boilerplate, focused edits.
- **License note:** Mistral AI Non-Production License (v0.1 weights). **Not licensed for production use** — check before any commercial deployment.

---

## How downloads work

1. You select a model and press **Download**.
2. The Java server resolves the exact file, size and SHA-256 through **ModelScope first**. If ModelScope does not carry the file or fails, it tries the domestic `hf-mirror.com` source, then `huggingface.co`.
3. Disk space is checked (file size + 512 MB headroom).
4. The file is streamed to `models/downloads/<model-id>/<file>.part` with HTTP Range resume.
5. If the connection drops, up to **12 automatic attempts** resume from the bytes already on disk.
6. When the size matches, the C helper computes SHA-256 and compares it with the upstream hash.
7. Only a verified file is moved to its final name and registered in `models/installed.json`.

The Hugging Face-compatible fallback order is configurable with `llm.model_mirror` in `config.json` or the `CODEFORGE_MODEL_MIRROR` environment variable. A comma-separated list is tried in order, and `https://huggingface.co` is appended automatically as the final fallback. ModelScope is always attempted first; its repositories, filenames and SHA-256 hashes match the Hugging Face originals.

You can close the browser — the download runs in a daemon thread inside the Java server. Progress is visible in **Models → Download queue** and via `GET /api/downloads`.

### CLI equivalent

```bash
curl -X POST http://127.0.0.1:8765/api/models/download \
  -H "Content-Type: application/json" \
  -d '{"id":"qwen3-4b-instruct-2507"}'

curl http://127.0.0.1:8765/api/downloads
```

### Directory layout

```text
models/
├── downloads/
│   └── qwen3-4b-instruct-2507/
│       └── Qwen3-4B-Instruct-2507-Q4_K_M.gguf
├── installed.json
└── capability.json
```

`installed.json` records id, name, repo, file, path, byte size, SHA-256, and download timestamp. Deleting a GGUF makes the model appear uninstalled; delete its entry from `installed.json` if you also remove the file manually.

---

## Capability verification

A downloaded file proves the bytes are intact, not that the model can follow CodeForge's tool protocol. Every model card therefore carries a capability badge, stored per model in `models/capability.json`.

Statuses:

| Status | Meaning |
|---|---|
| `untested` | No probe has run for this model yet |
| `running` | A probe is in flight |
| `pass` | The reply contained a complete `<codeforge_build>` JSON block with runnable Python that prints `PROBE_OK` |
| `weak` | The model replied, but the tool call, JSON, language, source, placeholder or `PROBE_OK` check failed |
| `error` | The inference endpoint was unreachable or returned an error |

How the probe works:

1. Start inference on the model you want to verify.
2. Press **Test** on its card, or call `POST /api/models/probe` with `{"id":"<model-id>"}`.
3. The server sends one deterministic build request asking for `capability_probe`, a Python script that prints exactly `PROBE_OK`, inside one `<codeforge_build>` tool call.
4. The reply is validated for: a closed tag, valid JSON, `language: "python"`, non-empty `code`/`source`, no placeholders (`TODO`, `your code here`, and similar), a `print` call, and the literal `PROBE_OK`.
5. The result is written immediately. The console polls while a probe is running and updates the badge without a reload.

The running model is probed automatically the first time inference starts, and only when its stored status is `untested`, `error`, or a stale `running` older than ten minutes. A model that later produces a malformed tool call that cannot be repaired after the protocol retries is marked `weak` with source `chat`, so repeated failures are visible instead of silent.

```bash
curl -s -X POST http://127.0.0.1:8765/api/models/probe \
  -H "Content-Type: application/json" -d '{"id":"qwen3-4b-instruct-2507"}'
```

Response while starting:

```json
{ "ok": true, "status": "running", "model_id": "qwen3-4b-instruct-2507" }
```

Stored result after completion:

```json
{
  "status": "pass",
  "score": 100,
  "reason": "Valid <codeforge_build> JSON with runnable Python source",
  "language": "python",
  "name": "capability_probe",
  "code_chars": 42,
  "reply_chars": 180,
  "source": "probe",
  "latency_ms": 1840,
  "id": "qwen3-4b-instruct-2507",
  "checked_at": "2026-11-22T08:00:00Z"
}
```

A `weak` verdict stores `score: 0`, a concrete `reason`, and a short reply `excerpt` so you can tell a model that cannot follow the protocol from one that fails on code quality.

---

## Running a model

1. **Models → Install runtime** downloads the matching `llama.cpp` CPU release (see below). Once installed, the button reads **Reinstall runtime**. If the GitHub asset host is unreachable, the installer falls back to `ghproxy.net`, `gh-proxy.com`, or the prefixes in `llm.runtime_mirror` / `CODEFORGE_RUNTIME_MIRROR`; the GitHub SHA-256 is always verified.
2. **Models → Start inference** launches:

```text
llama-server -m <model.gguf> --host 127.0.0.1 --port 8080 \
  -c <llm.context_size, default 16384> --parallel <llm.parallel, default 1> \
  -t <max(2, cores/2)> --no-webui
```

3. The server waits up to 180 seconds for `/health`.
4. The C++ core auto-detects `http://127.0.0.1:8080/v1` and reports `llm.available: true`.
5. The capability probe starts automatically when the model has no fresh verdict.
6. **Stop** terminates the managed process tree and frees the port.

Logs are written to `logs/llama-server.log` and can be viewed in the console (**View log**) or via `GET /api/runtime/logs?lines=200`.

### Runtime assets

| Platform | Asset pattern | Archive |
|---|---|---|
| Windows x64 | `llama-b<tag>-bin-win-cpu-x64.zip` | ZIP |
| Windows ARM64 | `llama-b<tag>-bin-win-cpu-arm64.zip` (x64 fallback) | ZIP |
| Linux x64 | `llama-b<tag>-bin-ubuntu-x64.tar.gz` | tar.gz |
| Linux ARM64 | `llama-b<tag>-bin-ubuntu-arm64.tar.gz` | tar.gz |
| Linux s390x | `llama-b<tag>-bin-ubuntu-s390x.tar.gz` | tar.gz |
| macOS Apple Silicon | `llama-b<tag>-bin-macos-arm64.tar.gz` | tar.gz |
| macOS Intel | `llama-b<tag>-bin-macos-x64.tar.gz` | tar.gz |
| Android ARM64 (Termux) | `llama-b<tag>-bin-android-arm64.tar.gz`, Snapdragon fallback | tar.gz |

The runtime is extracted to `runtime/llama.cpp/`, verified, and recorded in
`runtime/runtime.json`. If your ABI has no upstream asset (32-bit ARM/x86,
Alpine/musl, iSH), build `llama.cpp` from source and set `llm.llama_server` or
`CODEFORGE_LLAMA_SERVER` (commands in [Platform guide](PLATFORMS.md)).

---

## Choosing a model

| Your machine | Recommended starting point |
|---|---|
| 8 GB RAM laptop | Qwen3 4B Instruct 2507 |
| 16 GB RAM | GPT-OSS 20B, DeepSeek-Coder V2 Lite, Phi-4 |
| 24 GB+ RAM | Qwen3-Coder 30B-A3B, Qwen3.8 27B, Devstral Small 2507 |
| 28–32 GB RAM | Qwen3.6 35B A3B, Seed-OSS 36B (large context), Gemma 3 27B |
| Apple Silicon 16 GB+ | Devstral Small 2507, Qwen3-Coder 30B-A3B (Metal acceleration via llama.cpp) |

Rules of thumb:

- **MoE models** (Qwen3.6 35B A3B, Qwen3-Coder 30B-A3B, DeepSeek-Coder V2 Lite, GPT-OSS 20B) activate only a fraction of parameters per token, so they decode faster on CPU than their parameter count suggests — but they still need the full weights in RAM.
- **Dense models** (Qwen3.8 27B, Devstral 24B, Gemma 3 27B) give strong quality but use every parameter on every token; expect slower CPU decoding.
- **Q4_K_M** is the default quantization: a good quality/size trade-off. MXFP4 is used for GPT-OSS because that is the published format.
- **Context** affects KV-cache memory. The runtime defaults to `-c 16384 --parallel 1`; choose 2K–512K in the runtime panel or set `llm.context_size` only if you have RAM headroom and the model supports it. Qwen3.6 and Qwen3.8 support 262K; Seed-OSS 36B supports 512K.
- First tokens are slower while the model is paged in. Let the model warm up with a short prompt.
- Run **Test** after the first start. A `pass` badge means the model emits the tool protocol CodeForge depends on; a `weak` badge means it can chat but should not be trusted for automated builds.

---

## Configuration

`config.json` controls probing and generation:

```json
{
  "llm": {
    "backend": "auto",
    "ollama_url": "http://127.0.0.1:11434",
    "openai_url": "http://127.0.0.1:8080",
    "api_key": "",
    "model": "",
    "temperature": 0.2,
    "max_tokens": 2048,
    "request_timeout_ms": 600000
  },
  "debug": {
    "max_iterations": 3,
    "run_timeout_ms": 20000,
    "compile_timeout_ms": 60000
  },
  "server": { "port": 8765 }
}
```

- `backend`: `auto` probes Ollama first, then an OpenAI-compatible endpoint. Set `ollama` or `openai` to force one.
- `model`: empty selects the first model the backend reports (llama-server reports the loaded GGUF path).
- `api_key`: sent as `Authorization: Bearer <key>` when non-empty. Not needed for local llama-server.
- `temperature` and `max_tokens` apply to every chat and repair request.
- `debug.max_iterations` caps the autonomous repair loop; the API can lower it per request.

### Ollama alternative

```bash
ollama serve
ollama pull deepseek-coder-v2:16b  # any locally available model works
```

With `backend: "auto"`, CodeForge prefers Ollama when it is reachable at `127.0.0.1:11434`. No model from the registry is required in this mode.

### Any OpenAI-compatible server

Point `llm.openai_url` at LM Studio, vLLM, text-generation-webui, or a remote gateway and set `api_key` if required. Keep `temperature` low (0.1–0.3) for deterministic repairs.

---

## Licenses and redistribution

CodeForge's own code is MIT-licensed. **Model weights are not covered by that license.** In particular:

- **Apache-2.0:** Qwen3.6, Qwen3.8, Qwen3-Coder, Qwen3 4B, Seed-OSS, GPT-OSS, Devstral.
- **MIT:** DeepSeek R1 0528 Qwen3 8B, Phi-4.
- **Custom / restricted:** DeepSeek-Coder V2 Lite (DeepSeek License), CodeGeeX4 (CodeGeeX4 License), Gemma 3 (Gemma License), Codestral 22B (Mistral Non-Production License).

Do not bundle downloaded weights in a public release unless the model license allows redistribution. CodeForge never uploads weights; they remain on your disk.
