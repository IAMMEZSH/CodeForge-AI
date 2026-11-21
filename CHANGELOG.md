# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.2.1 — 2026-11-21

### Added

- **Thirteen-model offline catalog.** Qwen3.6 35B A3B and Qwen3.8 27B join the
  registry alongside Qwen3-Coder 30B-A3B, DeepSeek, Seed-OSS, GPT-OSS, Devstral,
  Gemma, Phi and Codestral. The catalog is now eight China-origin and five
  Global-origin models; Qwen3.6 and GPT-OSS are marked Recommended.
- **Capability verification.** Every installed model can be tested with one
  real `<codeforge_build>` request from the Models panel or
  `POST /api/models/probe`. The server validates the tool tag, JSON, language,
  code, placeholders, `print` call and `PROBE_OK` marker, then stores a
  `pass`, `weak` or `error` verdict in `models/capability.json`. Chat protocol
  failures that survive repair retries also mark the running model `weak`.
- **Automatic first-start probe.** When inference starts on a model whose
  verdict is `untested`, `error` or a stale `running`, the probe launches in the
  background and the card updates through polling.
- **ModelScope-first downloads.** Model resolution now asks ModelScope first,
  then the `hf-mirror.com` mirror, then `huggingface.co`. Size and SHA-256 are
  still resolved before transfer and verified after it.
- **Model badges in the console.** Model cards show capability state, reason
  and check time, with a **Test** button and a live pulse while a probe runs.

### Changed

- The download panel and documentation describe the three-stage resolution
  path and the separate `models/installed.json` and `models/capability.json`
  state files.
- Model diagrams, the README catalog, the manual and the platform guide now
  describe thirteen models and the probe workflow.

### Notes

- Local commits and builds remain unrestricted; remote publishing still needs
  **Allow publishing**.
- A `weak` model can still be used for conversation, but should not be trusted
  for unattended builds.
