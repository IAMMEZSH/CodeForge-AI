# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.2.0 — 2026-11-21

### Added

- **Auto-compile switch, end to end.** The chat header **AUTO COMPILE** toggle
  and Environment → Command Agent now share one persisted setting,
  `agent.auto_compile`. When it is on, the agent turns every build request into
  a delivered artifact, places it in the destination you asked for, launches it,
  and verifies that a real GUI window appeared before reporting success. The
  same switch is honored by explicit `<codeforge_build>` calls and by the
  automatic delivery fallback, so the model cannot skip compilation, delivery,
  opening or verification.
- **Post-build launch verification and automatic repair.** The host opens
  Python GUI apps with `pythonw`, Java GUI apps with `javaw`, and native
  executables directly. On Windows it polls `MainWindowHandle` and
  `MainWindowTitle` through PowerShell; web deliveries are opened in the
  browser. Success returns `opened`, `open_check` (`window-open`,
  `process-alive`, `startup-verified` or `browser`) and `window_title`; a
  process that exits or never draws a window returns `phase:"verify"` with the
  captured log. That failure re-enters the existing automatic repair loop,
  which fixes the runtime or window error and rebuilds instead of claiming
  success.
- **Dependency provisioning for every delivery pipeline.** Python files are
  import-scanned and missing packages are installed through domestic and
  international pip mirrors; Node projects install missing `npm` dependencies;
  Go modules are resolved before build; all delivery pipelines run a startup
  self-check with the delivered artifact. Missing language toolchains are
  provisioned on demand without changing the system PATH.
- **Release asset refresh and a single supported version.** Version 1.2.0
  replaces every earlier package, tag and screenshot on both platforms. All
  package names, manifests, launchers, mobile versions, binaries, diagrams,
  documentation and console screenshots carry 1.2.0, and older release tags are
  removed from GitHub and GitCode.

### Existing capabilities in this release

- **Office document engine with PDF support.** The Office tab and
  `codeforge_office` tool preview DOCX/DOC, XLSX/XLS/XLSM, PPTX/PPT, PDF,
  ODT/ODS/ODP, RTF, CSV/TSV and text documents. Preview returns real document
  structure (paragraphs, sheet grids, slide shapes, PDF page counts); PDF pages
  render as images. Editing covers text, table cells, workbook cells/rows/sheets,
  slide text and lifecycle, PDF metadata and pages, and text files. Deletions
  move to `workspace/.trash/office` unless permanent deletion is requested.
- **Whole-disk file access with permission gate.** The Files tab and
  `codeforge_fs` list drives, browse folders, search by path, filename glob or
  content, and create, rename, copy, move, trash or delete. Everything outside
  the workspace stays blocked until **Settings → Full filesystem access** is
  enabled. Deletions require `confirm:true`, go to `workspace/.trash/fs`, and
  are appended to `logs/file-access.jsonl`.
- **Email sending with SMTP settings.** The Mail tab and `codeforge_mail`
  send plain-text or HTML mail through any SMTP account with To/Cc/Bcc,
  attachments up to 25 MB, SSL, STARTTLS or no encryption, and a connection
  test. The SMTP password stays in the local KeyStore.
- **Download center for software, games, documents and web files.** The
  Downloads tab and `codeforge_download` fetch a URL or search and rank
  candidates by official domain, exact filename, platform and size. Transfers
  resume with `.part` files, a stalled-body watchdog, retries, live progress,
  speed, ETA, SHA-256 reporting and optional hash verification.
- **Four console views and matching agent tools.** Office, Files, Mail and
  Downloads sit under Tools; the same capabilities reach the model through
  `codeforge_office`, `codeforge_fs`, `codeforge_mail` and
  `codeforge_download`.

### Notes

- Local commits and builds remain unrestricted; remote publishing still needs
  **Allow publishing**.
- Whole-disk operations and mail sending are explicit capabilities: they stay
  disabled or unconfigured until the user turns them on, and their audit and
  trash behavior is visible in the UI.
