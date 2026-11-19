# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.0.9 — 2026-11-20

### Added

- **Static code inspection, quarantine and integrity proof.** The new
  **Inspect** tab scans any file or project without executing it. Each file
  receives language, encoding, MIME, entropy, hashes, structure, imports,
  network clues, masked secrets, concrete risk indicators and a 0–100 score.
  JSON and Markdown reports are written under
  `workspace/reports/code-inspection/`. Files scoring 70+ are copied into a
  read-only quarantine vault with a sidecar audit record.
- **Optional shredding of quarantine copies.** After the report is written,
  the server can overwrite each copy created by that scan, truncate it and
  delete it, while retaining the sidecar with `shredded`, `shredded_at`,
  `shred_method` and `original_untouched`. Originals are never modified,
  moved or deleted.
- **Before/after SHA-256 integrity verification.** Every scanned file is
  re-hashed after analysis (256 MB budget) and compared with the scan-time
  hash. The response exposes `unchanged`, `content_verified`, checked,
  skipped, changed and unreadable counts plus a sorted path+hash digest, so
  “the scan did not modify the files” is a measured result.
- **Universal API tool and named-secret vault.** `codeforge_api` /
  `POST /api/api-call` call any HTTP or HTTPS endpoint with method, headers,
  query, body and timeout. Named secrets stored through `/api/secrets` are
  injected in memory and never returned. The agent can now use arbitrary
  authenticated REST APIs instead of only built-in search/fetch tools.
- **Publish tab.** The console can create or update GitHub and GitCode
  releases from a local `release/` folder, upload every asset, replace assets
  with the same name and optionally delete older GitHub releases. Tokens are
  discovered from the vault, environment, GitHub CLI or Git credential helper
  and are never displayed.
- **Agent file, test and research tools.** `codeforge_read`, `codeforge_list`,
  `codeforge_write`, `codeforge_edit`, `codeforge_test`, `codeforge_crawl`,
  `codeforge_deep_search` and `codeforge_corpus` let the model read and edit
  real files, run the project's test command, crawl pages, run multi-query
  research and query the local research corpus.
- **Adjustable agent step limit from 1 to 512.** The Command Agent panel and
  `/api/agent` accept any value in the range; the default is 128.
- **In-app update notices.** `GET /api/update` reads the public release index
  (GitCode first, GitHub fallback; ten-minute cache) and compares the newest
  stable tag with the running build. The console checks on load and every six
  hours, then shows a dismissible banner linking to the release. Only release
  metadata is fetched. `"updates": {"enabled": false}` in `config.json`
  disables the check. A build that predates the checker must upgrade manually
  once; the release banner and README say so explicitly.
- **Game engine adapters.** A new **Engines** tab and `codeforge_engine` agent
  tool drive Godot 4, Unity, Unreal Engine 5, Cocos Creator and LÖVE without
  bundling any of them. The adapter detects user-installed engines from
  environment variables, PATH, Unity Hub, Epic Games and the standard install
  folders; finds projects by their real markers (`project.godot`,
  `ProjectSettings/ProjectVersion.txt` plus `Assets/`, `*.uproject`, Cocos
  `package.json` plus `assets/`, `main.lua`); parses Godot export presets and
  per-engine targets; then builds, exports and runs with the engine's own CLI
  (Godot headless import/export, Unity batchmode player builds, Unreal
  BuildCookRun, Cocos Creator CLI, LÖVE runner). Every export is verified
  against the artifact on disk, and a missing engine returns an exact install
  hint instead of a false success. LÖVE `.love` packaging works with no engine
  installed, and a fused `.exe` is produced when `love.exe` exists.
  `GET /api/engines` reports detection; `POST /api/engines` runs
  detect/build/export/run/status.

### Changed

- README, user manual, API reference, security notes, architecture notes,
  quick starts, diagrams, banner and console screenshots were refreshed for
  1.0.9 and now document the static inspector, the secret vault, the publish
  workflow, the file/research tools and the engine adapters.
- The server jar, native binaries, web console, mobile projects and release
  manifest all report 1.0.9. The console rail now has eight views:
  Chat, Debug, Models, Keys, Publish, Inspect, Engines and Env.

### Security

- Static inspection never executes a sample and reports a hash-based integrity
  result for the inspected tree.
- Secret values, publish tokens and `Authorization`/`Cookie` response headers
  are never written to API responses or logs.
- Shipped `config.json` files blank `llm.api_key`, `llm.online.api_key`,
  `llm.keys` and the whole `secrets` object in every archive.