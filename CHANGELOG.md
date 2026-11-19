# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.0.8 — 2026-11-20

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
  disables the check. Builds older than 1.0.8 have no checker, so their first
  upgrade is manual; the release banner and README say so explicitly.

### Changed

- README, user manual, API reference, security notes, architecture notes,
  quick starts, diagrams, banner and console screenshots were refreshed for
  1.0.8 and now document the static inspector, the secret vault, the publish
  workflow and the file/research tools.
- The server jar, native binaries, web console, mobile projects and release
  manifest all report 1.0.8.

### Security

- Static inspection never executes a sample and reports a hash-based integrity
  result for the inspected tree.
- Secret values, publish tokens and `Authorization`/`Cookie` response headers
  are never written to API responses or logs.
- Shipped `config.json` files blank `llm.api_key`, `llm.online.api_key`,
  `llm.keys` and the whole `secrets` object in every archive.