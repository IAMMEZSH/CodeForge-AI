# Changelog

All notable changes to CodeForge AI are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## 1.1.0 — 2026-11-20

### Added

- **Office document engine with PDF support.** The new **Office** tab and the
  `codeforge_office` agent tool preview DOCX/DOC, XLSX/XLS/XLSM, PPTX/PPT, PDF,
  ODT/ODS/ODP, RTF, CSV/TSV and plain text documents. Preview returns the real
  document text and structure (paragraphs, sheets and cell grids, slide titles
  and shapes, PDF page counts); PDF pages render as images in the console.
  Editing covers paragraphs, table cells, workbook cells/rows/sheets, slide
  text and lifecycle, PDF metadata and pages, and text files. Creation covers
  DOCX, XLSX, PPTX, PDF, TXT, Markdown, CSV and TSV; ODT/ODS/ODP, RTF and
  legacy Office files open read-only. Deleted documents move to
  `workspace/.trash/office` unless permanent deletion is explicitly requested.
- **Whole-disk file access with permission gate.** The new **Files** tab and
  `codeforge_fs` agent tool list drives, browse folders, stat files, search
  across drives by path, filename glob or file content, and create, rename,
  copy, move, trash or delete files and folders. Everything except the
  permission check is blocked until the user enables **Settings → Full
  filesystem access** (stored as `permissions.full_filesystem` in
  `config.json`). Deletions require `confirm:true` and go to
  `workspace/.trash/fs`; every operation is written to
  `logs/file-access.jsonl`. Sensitive system directories are guarded.
- **Email sending with SMTP settings.** The new **Mail** tab and the
  `codeforge_mail` agent tool send plain-text or HTML email through any SMTP
  account, with To/Cc/Bcc, multiple attachments up to 25 MB total, SSL,
  STARTTLS or no encryption, and a connection test. The SMTP password is
  stored in the local encrypted KeyStore, never in `config.json` and never
  returned by the API.
- **Download center for software, games, documents and web files.** The new
  **Downloads** tab and `codeforge_download` agent tool download directly from
  a URL or, when only a name is given, search and rank likely official files.
  Candidate scoring prefers official domains, exact filename matches, the
  current platform and the requested size; downloads support resume,
  `.part` files, a stalled-body watchdog, same-URL retries, SHA-256 reporting
  and verification against an expected hash. Jobs expose live progress, speed,
  ETA, saved path, cancel and open-folder actions. “Save page itself” stores
  the HTML when the target is a web page.
- **Four new console views and agent tools.** The six-slot rail now maps
  Office, Files, Mail and Downloads under **Tools**, each with a full view:
  document preview and edit controls, PDF canvas, drive browser and search,
  SMTP settings and compose panel, and download jobs with candidate selection.
  The chat agent receives the same capabilities through `codeforge_office`,
  `codeforge_fs`, `codeforge_mail` and `codeforge_download`.
- **Version 1.1.0 release content.** All package names, manifests, launchers,
  mobile versions, binaries, documentation and screenshots now carry 1.1.0.

### Notes

- Local commits and builds remain unrestricted; remote publishing still needs
  **Allow publishing**.
- Whole-disk operations and mail sending are explicit capabilities: they stay
  disabled or unconfigured until the user turns them on, and their audit and
  trash behavior is visible in the UI.
