#!/usr/bin/env python3
"""Merge compiled classes and server-java/lib/*.jar into one runnable fat jar.

The launcher starts the server with `java -jar`, so office/PDF/mail
dependencies must live inside the jar. Service provider entries are merged
line by line; signature files and duplicate module descriptors are dropped.
"""
from __future__ import annotations

import os
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "server-java"
CLASSES = SERVER / "build" / "classes"
LIB = SERVER / "lib"
OUTPUT = SERVER / "build" / "CodeForgeServer.jar"
SKIP_PREFIXES = ("META-INF/versions/9/module-info.class",)
SKIP_NAMES = {
    "META-INF/MANIFEST.MF", "META-INF/INDEX.LIST",
    "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
    "META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/maven/",
}
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def is_skipped(name: str) -> bool:
    upper = name.upper()
    if name == "META-INF/MANIFEST.MF":
        return True
    if name.startswith("META-INF/maven/"):
        return True
    if upper.startswith("META-INF/") and upper.endswith(SIGNATURE_SUFFIXES):
        return True
    if name in SKIP_NAMES or name in SKIP_PREFIXES:
        return True
    if name.endswith("/module-info.class") and name.startswith("META-INF/versions/"):
        return True
    return False


def main() -> int:
    if not CLASSES.is_dir():
        raise SystemExit("missing build/classes; compile first")
    jars = sorted(LIB.glob("*.jar"))
    if not jars:
        raise SystemExit("server-java/lib has no dependency jars")

    services: dict[str, list[str]] = {}
    written: set[str] = set()
    count = 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temp = OUTPUT.with_suffix(".jar.tmp")

    with zipfile.ZipFile(temp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as out:
        for jar in jars:
            with zipfile.ZipFile(jar) as src:
                for info in src.infolist():
                    name = info.filename
                    if info.is_dir() or is_skipped(name):
                        continue
                    if name.startswith("META-INF/services/"):
                        try:
                            text = src.read(name).decode("utf-8", "replace")
                        except Exception:
                            continue
                        bucket = services.setdefault(name, [])
                        for line in text.splitlines():
                            line = line.strip()
                            if line and not line.startswith("#") and line not in bucket:
                                bucket.append(line)
                        continue
                    if name in written:
                        continue
                    try:
                        data = src.read(name)
                    except Exception:
                        continue
                    out.writestr(name, data)
                    written.add(name)
                    count += 1

        for name, entries in services.items():
            body = "# merged by CodeForge AI build\n" + "\n".join(entries) + "\n"
            out.writestr(name, body.encode("utf-8"))
            written.add(name)

        for path in CLASSES.rglob("*.class"):
            name = path.relative_to(CLASSES).as_posix()
            if name in written:
                continue
            out.writestr(name, path.read_bytes())
            written.add(name)
            count += 1

        manifest = (
            "Manifest-Version: 1.0\r\n"
            "Main-Class: codeforge.server.ServerMain\r\n"
            "Multi-Release: true\r\n"
            "Implementation-Title: CodeForge AI\r\n"
            "Implementation-Version: 1.2.1\r\n"
            "\r\n"
        )
        out.writestr("META-INF/MANIFEST.MF", manifest.encode("utf-8"))

    os.replace(temp, OUTPUT)
    with zipfile.ZipFile(OUTPUT) as check:
        bad = check.testzip()
        if bad:
            raise SystemExit("corrupt entry: " + bad)
        names = set(check.namelist())
        for required in ("codeforge/server/ServerMain.class",
                         "org/apache/poi/xwpf/usermodel/XWPFDocument.class",
                         "org/apache/pdfbox/pdmodel/PDDocument.class",
                         "jakarta/mail/Session.class",
                         "META-INF/services/jakarta.mail.Provider",
                         "META-INF/services/org.apache.poi.ss.usermodel.WorkbookProvider"):
            if required not in names:
                raise SystemExit("missing from fat jar: " + required)
    print(f"wrote {OUTPUT} ({OUTPUT.stat().st_size:,} bytes, {len(written):,} entries, "
          f"{len(services)} merged service files)")
    print(f"dependencies merged: {len(jars)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())