"""Writes double-click Open Reports launchers next to every built run.

Chrome blocks file:// links between HTML files. These scripts serve the reports
folder over http://127.0.0.1. They live in the reports/ directory (and a
this-run opener in the run folder) so Katalon, Playwright, and API suites all
get the same files — no dependency on finding this git repo at a fixed path.
"""
from __future__ import annotations

import stat
from pathlib import Path

_SERVE_PY = '''#!/usr/bin/env python3
"""Serve this reports/ folder over HTTP and open a run in the browser."""
from __future__ import annotations

import argparse
import http.server
import socketserver
import sys
import threading
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def _latest() -> Path:
    runs = sorted(p.parent for p in ROOT.glob("*/run.json"))
    if not runs:
        sys.exit(f"No runs found under {ROOT}")
    return max(runs, key=lambda p: p.stat().st_mtime)


def _run(name: str | None) -> Path:
    if not name:
        return _latest()
    candidate = (ROOT / name).resolve()
    if (candidate / "index.html").is_file():
        return candidate
    sys.exit(f"No index.html in {candidate}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", help="Run folder name (default: latest)")
    args = parser.parse_args()
    run_dir = _run(args.run)
    rel = run_dir.relative_to(ROOT).as_posix()

    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(ROOT), **kwargs)

        def log_message(self, fmt, *args):  # noqa: ARG002
            return

    socketserver.ThreadingTCPServer.allow_reuse_address = True
    httpd = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{httpd.server_address[1]}/{rel}/index.html"
    print(f"Reports: {url}", flush=True)
    print("Leave this window open while you read the reports.", flush=True)
    webbrowser.open(url)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        print("\\nStopped.")


if __name__ == "__main__":
    main()
'''

_MAC_LATEST = """#!/bin/bash
# Double-click to open the latest reports (http://127.0.0.1 — Chrome blocks file://).
cd "$(dirname "$0")" || exit 1
PY=""
for c in python3.13 python3.12 python3.11 python3.10 python3; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
    PY="$c"
    break
  fi
done
if [ -z "$PY" ]; then
  echo "Python 3.10+ is needed to open the reports."
  read -r -p "Press Enter to close this window..."
  exit 1
fi
"$PY" open_reports.py
read -r -p "Press Enter to close this window (that also stops the report server)..."
"""

_WIN_LATEST = """@echo off
setlocal
rem Double-click to open the latest reports (http://127.0.0.1 — Chrome blocks file://).
cd /d "%~dp0"
set "PY=python"
"%PY%" open_reports.py
if errorlevel 1 echo Python 3.10+ is needed to open the reports.
pause
"""


def _chmod_exec(path: Path) -> None:
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def _write_latest_html(reports_root: Path, run_dir: Path) -> None:
    rel = run_dir.resolve().relative_to(reports_root.resolve()).as_posix()
    (reports_root / "Open Latest Report.html").write_text(
        "<!DOCTYPE html><meta charset=\"utf-8\"><title>Open latest report</title>"
        f"<p><a href=\"{rel}/index.html\">Open latest run ({rel})</a></p>"
        "<p id=\"w\" style=\"display:none;font-family:sans-serif;max-width:40rem\">"
        "Chrome blocks these links when this file is opened directly. "
        "Double-click <b>Open Reports (Mac).command</b> or <b>Open Reports (Windows).bat</b> "
        "in this folder.</p>"
        "<script>if(location.protocol==='file:'){document.getElementById('w').style.display='block'}"
        f"else{{location.replace('{rel}/index.html')}}</script>",
        encoding="utf-8",
    )


def write_openers(run_dir: Path) -> None:
    """Write Open Reports launchers into reports/ and a this-run opener into run_dir."""
    reports_root = run_dir.resolve().parent
    reports_root.mkdir(parents=True, exist_ok=True)
    serve = reports_root / "open_reports.py"
    serve.write_text(_SERVE_PY, encoding="utf-8")
    _chmod_exec(serve)

    mac_latest = reports_root / "Open Reports (Mac).command"
    mac_latest.write_text(_MAC_LATEST, encoding="utf-8")
    _chmod_exec(mac_latest)
    (reports_root / "Open Reports (Windows).bat").write_text(_WIN_LATEST, encoding="utf-8")

    run_name = run_dir.resolve().name
    mac_run = run_dir / "Open This Run (Mac).command"
    mac_run.write_text(
        "#!/bin/bash\n"
        "# Open this run only (http://127.0.0.1 — Chrome blocks file://).\n"
        'cd "$(dirname "$0")/.." || exit 1\n'
        "PY=\"\"\n"
        "for c in python3.13 python3.12 python3.11 python3.10 python3; do\n"
        "  if command -v \"$c\" >/dev/null 2>&1 && \"$c\" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then\n"
        "    PY=\"$c\"\n"
        "    break\n"
        "  fi\n"
        "done\n"
        "if [ -z \"$PY\" ]; then\n"
        "  echo \"Python 3.10+ is needed to open the reports.\"\n"
        "  read -r -p \"Press Enter to close this window...\"\n"
        "  exit 1\n"
        "fi\n"
        f"\"$PY\" open_reports.py --run \"{run_name}\"\n"
        'read -r -p "Press Enter to close this window (that also stops the report server)..."\n',
        encoding="utf-8",
    )
    _chmod_exec(mac_run)
    (run_dir / "Open This Run (Windows).bat").write_text(
        "@echo off\n"
        "setlocal\n"
        "cd /d \"%~dp0..\"\n"
        f"python open_reports.py --run \"{run_name}\"\n"
        "if errorlevel 1 echo Python 3.10+ is needed to open the reports.\n"
        "pause\n",
        encoding="utf-8",
    )
    _write_latest_html(reports_root, run_dir)
