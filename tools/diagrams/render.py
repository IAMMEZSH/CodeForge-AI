#!/usr/bin/env python3
"""Render the CodeForge AI explanatory diagrams with headless Microsoft Edge.

Usage:
    python tools/diagrams/render.py
    python tools/diagrams/render.py --only banner quickstart

Output: docs/images/banner.png and docs/images/diagram-*.png
"""
from __future__ import annotations

import argparse
import shutil
import struct
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OUT = ROOT / "docs" / "images"

EDGE_CANDIDATES = [
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
    Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
    Path("/usr/bin/microsoft-edge"),
    Path("/usr/bin/google-chrome"),
]

JOBS = [
    ("banner.html", "banner.png", 1600, 600),
    ("architecture.html", "diagram-architecture.png", 1600, 1000),
    ("debug-loop.html", "diagram-debug-loop.png", 1600, 1000),
    ("platforms.html", "diagram-platforms.png", 1600, 1280),
    ("models.html", "diagram-models.png", 1600, 1000),
    ("security.html", "diagram-security.png", 1600, 1000),
    ("quickstart.html", "diagram-quickstart.png", 1600, 1000),
    ("lifecycle.html", "diagram-lifecycle.png", 1600, 1000),
]


def log(stage: str, message: str) -> None:
    print(f"[{stage}] {message}", flush=True)


def find_edge() -> Path:
    for candidate in EDGE_CANDIDATES:
        if candidate.is_file():
            return candidate
    found = shutil.which("msedge") or shutil.which("google-chrome")
    if found:
        return Path(found)
    raise RuntimeError("Microsoft Edge or Chrome not found")


def png_size(path: Path) -> tuple[int, int]:
    with open(path, "rb") as handle:
        header = handle.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        raise RuntimeError(f"not a PNG: {path}")
    width, height = struct.unpack(">II", header[16:24])
    return width, height


def render(edge: Path, name: str, width: int, height: int) -> Path:
    source = HERE / name
    if not source.is_file():
        raise RuntimeError(f"missing diagram source: {source}")
    target = OUT / (name.replace(".html", ".png"))
    if name == "banner.html":
        target = OUT / "banner.png"
    elif not target.name.startswith("diagram-"):
        target = OUT / ("diagram-" + target.name)
    profile = Path(tempfile.mkdtemp(prefix="cforge-diagram-"))
    command = [
        str(edge),
        "--headless=new",
        "--disable-gpu",
        "--hide-scrollbars",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-extensions",
        "--allow-file-access-from-files",
        "--force-device-scale-factor=1",
        f"--window-size={width},{height}",
        f"--user-data-dir={profile}",
        "--virtual-time-budget=3500",
        f"--screenshot={target}",
        source.resolve().as_uri(),
    ]
    log("render", f"{name} -> {target.name} ({width}x{height})")
    started = time.time()
    try:
        result = subprocess.run(command, capture_output=True, text=True,
                                timeout=120)
        if result.returncode != 0:
            log("render", (result.stderr or result.stdout).strip()[:500])
        if not target.is_file():
            raise RuntimeError(f"screenshot was not produced: {target}")
        actual = png_size(target)
        if actual != (width, height):
            log("render", f"warning: expected {width}x{height}, got {actual[0]}x{actual[1]}")
        log("render", f"done in {time.time() - started:.1f}s, "
                      f"{target.stat().st_size / 1024:.0f} KB")
        return target
    finally:
        shutil.rmtree(profile, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Render CodeForge diagrams")
    parser.add_argument("--only", nargs="*", default=[],
                        help="render only the named HTML files")
    args = parser.parse_args()
    edge = find_edge()
    OUT.mkdir(parents=True, exist_ok=True)
    selected = [job for job in JOBS
                if not args.only or job[0] in args.only
                or job[0].removesuffix(".html") in args.only]
    if not selected:
        log("error", f"no matching diagrams; known: {[job[0] for job in JOBS]}")
        return 1
    for name, _, width, height in selected:
        render(edge, name, width, height)
    log("done", f"{len(selected)} diagram(s) written to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
