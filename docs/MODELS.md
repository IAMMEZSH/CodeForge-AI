# Model Library

CodeForge ships a curated registry of **12 offline GGUF models** — a deliberate mix of Chinese and international releases chosen for coding quality, reasoning depth, and practical CPU performance. Nothing is downloaded until you ask for it.

All models are served locally by `llama.cpp`'s `llama-server` on `127.0.0.1:8080` through an OpenAI-compatible `/v1` API. Ollama and any external OpenAI-compatible endpoint can be used instead (see [Configuration](#configuration)).

---

## Registry at a glance

| # | Model | Vendor | Origin | Category | Params | Context | File size | Min RAM | License |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Qwen3-Coder 30B-A3B | Alibaba Qwen | China | Agentic flagship | 30B MoE · 3B active | 256K | ≈ 18.6 GB | 24 GB | Apache-2.0 |
| 2 | DeepSeek-Coder V2 Lite | DeepSeek | China | MoE efficiency | 16B MoE · 2.4B active | 128K | ≈ 10.4 GB | 14 GB | DeepSeek License |
| 3 | DeepSeek R1 0528 Qwen3 8B | DeepSeek | China | Reasoning | 8B | 128K | ≈ 5.0 GB | 8 GB | MIT |
| 4 | Qwen3 4B Instruct 2507 | Alibaba Qwen | China | Lightweight | 4B | 256K | ≈ 2.5 GB | 6 GB | Apache-2.0 |
| 5 | Yi-Coder 9B Chat | 01.AI | China | Code specialist | 9B | 128K | ≈ 5.3 GB | 8 GB | Apache-2.0 |
| 6 | CodeGeeX4 ALL 9B | Zhipu AI | China | Code specialist | 9B | 128K | ≈ 6.3 GB | 9 GB | CodeGeeX4 License |
| 7 | Seed-OSS 36B Instruct | ByteDance Seed | China | Large context | 36B | 512K | ≈ 21.8 GB | 27 GB | Apache-2.0 |
| 8 | GPT-OSS 20B | OpenAI | Global | Reasoning MoE | 20B MoE · 3.6B active | 128K | ≈ 12.1 GB | 16 GB | Apache-2.0 |
| 9 | Devstral Small 2507 | Mistral AI | Global | Agentic SWE | 24B | 128K | ≈ 14.3 GB | 20 GB | Apache-2.0 |
| 10 | Phi-4 | Microsoft | Global | Reasoning | 14B | 16K | ≈ 9.1 GB | 12 GB | MIT |
| 11 | Gemma 3 27B IT | Google | Global | Generalist | 27B | 128K | ≈ 16.6 GB | 22 GB | Gemma License |
| 12 | Codestral 22B | Mistral AI | Global | Code completion | 22B | 32K | ≈ 13.3 GB | 18 GB | Mistral Non-Production |

Sizes are Q4-class quantizations resolved from Hugging Face at download time and rounded; the exact byte count is shown in the console before and during download. RAM figures are practical guidance for CPU inference (model + KV cache + runtime overhead), not hard limits.

---

## Detailed entries

### 1. Qwen3-Coder 30B-A3B — `qwen3-coder-30b-a3b`

- **Repository:** `unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF`
- **File:** `Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf`
- **Why it is here:** the flagship open agentic coder. Excels at repository-scale refactors, tool use, and multi-file fixes. Sparse MoE (3B active) keeps CPU decoding usable.
- **Best for:** hard refactors, architectural changes, long agentic sessions.
- **Trade-off:** 18.6 GB on disk and 24 GB of RAM recommended; slow on low-end CPUs.

### 2. DeepSeek-Coder V2 Lite — `deepseek-coder-v2-lite`

- **Repository:** `bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF`
- **File:** `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf`
- **Why it is here:** sparse MoE coder with 128K context. Only 2.4B parameters are active per token, so it stays quick on CPU while handling large files.
- **Best for:** everyday code generation and review with large context on modest hardware.
- **License note:** released under the DeepSeek License, not Apache/MIT. Review the model card before commercial use.

### 3. DeepSeek R1 0528 Qwen3 8B — `deepseek-r1-0528-qwen3-8b`

- **Repository:** `unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF`
- **File:** `DeepSeek-R1-0528-Qwen3-8B-Q4_K_M.gguf`
- **Why it is here:** distilled DeepSeek reasoning model. Exposes a chain of thought and is unusually strong at diagnosing logic and concurrency bugs for an 8B model.
- **Best for:** root-cause analysis, tricky logic bugs, explaining why code fails.
- **Trade-off:** reasoning traces increase token usage and latency.

### 4. Qwen3 4B Instruct 2507 — `qwen3-4b-instruct-2507`

- **Repository:** `unsloth/Qwen3-4B-Instruct-2507-GGUF`
- **File:** `Qwen3-4B-Instruct-2507-Q4_K_M.gguf`
- **Why it is here:** fast lightweight generalist with strong instruction following. Ideal for autocomplete, explanations, and machines with 8 GB of RAM.
- **Best for:** first download, laptops, quick Q&A, smoke tests.
- **Verified in this project:** this is the model used for the real end-to-end inference and debug-loop verification on Windows.

### 5. Yi-Coder 9B Chat — `yi-coder-9b-chat`

- **Repository:** `bartowski/Yi-Coder-9B-Chat-GGUF`
- **File:** `Yi-Coder-9B-Chat-Q4_K_M.gguf`
- **Why it is here:** code specialist supporting 52 programming languages, with a strong balance of size and code accuracy.
- **Best for:** polyglot codebases, translation between languages, focused code edits.

### 6. CodeGeeX4 ALL 9B — `codegeex4-all-9b`

- **Repository:** `bartowski/CodeGeeX4-ALL-9B-GGUF`
- **File:** `codegeex4-all-9b-Q4_K_M.gguf`
- **Why it is here:** Zhipu AI code specialist covering code generation, completion, and repository-level Q&A.
- **Best for:** completion-style work and Chinese/English mixed codebases.
- **License note:** CodeGeeX4 License. Review the model card before commercial use.

### 7. Seed-OSS 36B Instruct — `seed-oss-36b-instruct`

- **Repository:** `unsloth/Seed-OSS-36B-Instruct-GGUF`
- **File:** `Seed-OSS-36B-Instruct-Q4_K_M.gguf`
- **Why it is here:** ByteDance Seed model with a 512K-token context — the largest context in the registry.
- **Best for:** very large files, monorepo analysis, long documentation dumps.
- **Trade-off:** 21.8 GB and 27 GB RAM recommended; heavy for laptops.

### 8. GPT-OSS 20B — `gpt-oss-20b`

- **Repository:** `ggml-org/gpt-oss-20b-GGUF`
- **File:** `gpt-oss-20b-MXFP4.gguf`
- **Why it is here:** OpenAI's open-weight reasoning MoE (3.6B active), strong at structured reasoning and tool-style responses. Uses MXFP4 quantization.
- **Best for:** reasoning-heavy debugging, planning, and explanations on a 16 GB machine.
- **Notes:** marked **Recommended** in the console; the MXFP4 file is shown as `MXFP4` in the model card.

### 9. Devstral Small 2507 — `devstral-small-2507`

- **Repository:** `unsloth/Devstral-Small-2507-GGUF`
- **File:** `Devstral-Small-2507-Q4_K_M.gguf`
- **Why it is here:** Mistral's agentic software-engineering model, tuned for codebase navigation and multi-step edits.
- **Best for:** agent-style fix loops, repository tasks, patch generation.

### 10. Phi-4 — `phi-4`

- **Repository:** `bartowski/phi-4-GGUF`
- **File:** `phi-4-Q4_K_M.gguf`
- **Why it is here:** Microsoft's compact reasoning model with strong math and logic performance for its size.
- **Best for:** algorithmic problems, math-heavy code, concise explanations.
- **Trade-off:** 16K context — the smallest in the registry; avoid very large files.

### 11. Gemma 3 27B IT — `gemma-3-27b-it`

- **Repository:** `ggml-org/gemma-3-27b-it-GGUF`
- **File:** `gemma-3-27b-it-Q4_K_M.gguf`
- **Why it is here:** Google generalist with excellent instruction following and multilingual ability; a strong all-rounder beyond pure coding.
- **Best for:** mixed technical/general work, documentation, explanations.
- **License note:** Gemma License with use restrictions. Review the model card.

### 12. Codestral 22B — `codestral-22b`

- **Repository:** `bartowski/Codestral-22B-v0.1-GGUF`
- **File:** `Codestral-22B-v0.1-Q4_K_M.gguf`
- **Why it is here:** Mistral code model tuned for fill-in-the-middle and completion, with broad language coverage.
- **Best for:** code completion, boilerplate, focused edits.
- **License note:** Mistral AI Non-Production License (v0.1 weights). **Not licensed for production use** — check before any commercial deployment.

---

## How downloads work

1. You select a model and press **Download**.
2. The Java server resolves the exact file and SHA-256 from the Hugging Face API.
3. Disk space is checked (file size + 512 MB headroom).
4. The file is streamed to `models/downloads/<model-id>/<file>.part` with HTTP Range resume.
5. If the connection drops, up to **12 automatic attempts** resume from the bytes already on disk.
6. When the size matches, the C helper computes SHA-256 and compares it with the Hugging Face LFS hash.
7. Only a verified file is moved to its final name and registered in `models/installed.json`.

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
└── installed.json
```

`installed.json` records id, name, repo, file, path, byte size, SHA-256, and download timestamp. Deleting a GGUF makes the model appear uninstalled; delete its entry from `installed.json` if you also remove the file manually.

---

## Running a model

1. **Models → Install runtime** downloads the matching `llama.cpp` CPU release (see below).
2. **Models → Start inference** launches:

```text
llama-server -m <model.gguf> --host 127.0.0.1 --port 8080 \
  -c <llm.context_size, default 16384> --parallel <llm.parallel, default 1> \
  -t <max(2, cores/2)> --no-webui
```

3. The server waits up to 180 seconds for `/health`.
4. The C++ core auto-detects `http://127.0.0.1:8080/v1` and reports `llm.available: true`.
5. **Stop** terminates the managed process tree and frees the port.

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
| 24 GB+ RAM | Qwen3-Coder 30B-A3B, Devstral Small 2507 |
| 32 GB+ RAM | Seed-OSS 36B (large context), Gemma 3 27B |
| Apple Silicon 16 GB+ | Devstral Small 2507, Qwen3-Coder 30B-A3B (Metal acceleration via llama.cpp) |

Rules of thumb:

- **MoE models** (Qwen3-Coder 30B-A3B, DeepSeek-Coder V2 Lite, GPT-OSS 20B) activate only a fraction of parameters per token, so they decode faster on CPU than their parameter count suggests — but they still need the full weights in RAM.
- **Q4_K_M** is the default quantization: a good quality/size trade-off. MXFP4 is used for GPT-OSS because that is the published format.
- **Context** affects KV-cache memory. The runtime defaults to `-c 16384 --parallel 1`; raise `llm.context_size` up to 262144 only if you have RAM headroom.
- First tokens are slower while the model is paged in. Let the model warm up with a short prompt.

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

- **Apache-2.0:** Qwen3-Coder, Qwen3 4B, Yi-Coder, Seed-OSS, GPT-OSS, Devstral, DeepSeek R1 0528 Qwen3 8B (MIT), Phi-4 (MIT).
- **Custom / restricted:** DeepSeek-Coder V2 Lite (DeepSeek License), CodeGeeX4 (CodeGeeX4 License), Gemma 3 (Gemma License), Codestral 22B (Mistral Non-Production License).

Do not bundle downloaded weights in a public release unless the model license allows redistribution. CodeForge never uploads weights; they remain on your disk.
