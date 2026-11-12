"""Serve a reports folder over HTTP and open it in the browser.

Chrome (and some other browsers) block clicks between local HTML files
(`file://` → ERR_ACCESS_DENIED). Opening via http://127.0.0.1 avoids that.

Usage:
    python scripts/open_reports.py
    python scripts/open_reports.py --run reports/<run_id>
    python reporter/open_reports.py --reports-dir "<suite>/reports"
"""
from __future__ import annotations

import argparse
import http.server
import socketserver
import subprocess
import sys
import threading
import webbrowser
from pathlib import Path


def _reports_root(cli: Path | None) -> Path:
    if cli:
        return cli.resolve()
    here = Path(__file__).resolve().parent
    for candidate in (Path.cwd() / "reports", here.parent / "reports"):
        if candidate.is_dir():
            return candidate
    sys.exit("No reports/ folder found. Pass --reports-dir.")


def _latest_run(reports_root: Path) -> Path:
    runs = sorted(p.parent for p in reports_root.glob("*/run.json"))
    if not runs:
        sys.exit(f"No runs found under {reports_root}.")
    return max(runs, key=lambda p: p.stat().st_mtime)


def _resolve_run(reports_root: Path, run: Path | None) -> Path:
    if run is None:
        return _latest_run(reports_root)
    run = run.resolve()
    if (run / "index.html").is_file():
        return run
    candidate = reports_root / run.name
    if (candidate / "index.html").is_file():
        return candidate
    sys.exit(f"No index.html in {run}.")


def serve(reports_root: Path, run_dir: Path) -> str:
    """Start a local server for reports_root and return the index URL."""
    root = str(reports_root.resolve())

    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=root, **kwargs)

        def log_message(self, fmt, *args):  # noqa: ARG002
            return

    socketserver.ThreadingTCPServer.allow_reuse_address = True
    httpd = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    rel = run_dir.resolve().relative_to(reports_root.resolve()).as_posix()
    return f"http://127.0.0.1:{httpd.server_address[1]}/{rel}/index.html"


def spawn(run_dir: Path) -> None:
    """Open reports in a detached server process (used after a test run)."""
    script = Path(__file__).resolve()
    reports_root = run_dir.resolve().parent
    subprocess.Popen(
        [sys.executable, str(script), "--reports-dir", str(reports_root), "--run", str(run_dir)],
        start_new_session=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reports-dir", type=Path, help="Suite reports/ folder (default: ./reports)")
    parser.add_argument("--run", type=Path, help="Run folder (default: latest)")
    args = parser.parse_args()
    reports_root = _reports_root(args.reports_dir)
    run_dir = _resolve_run(reports_root, args.run)
    url = serve(reports_root, run_dir)
    print(f"Reports: {url}", flush=True)
    print("Leave this window open while you read the reports. Press Ctrl+C to stop.", flush=True)
    webbrowser.open(url)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
