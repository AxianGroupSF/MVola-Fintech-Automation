"""Serve reports/ over http://127.0.0.1 and open the latest (or named) run."""
from __future__ import annotations

import argparse
import http.server
import socketserver
import sys
import threading
import webbrowser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORTS = ROOT / "reports"


def _latest() -> Path:
    runs = sorted(p.parent for p in REPORTS.glob("*/run.json"))
    if not runs:
        sys.exit(f"No runs found under {REPORTS}")
    return max(runs, key=lambda p: p.stat().st_mtime)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", help="Run folder name under reports/ (default: latest)")
    parser.add_argument("--reports-dir", type=Path, default=REPORTS)
    args = parser.parse_args()
    reports_root = args.reports_dir.resolve()
    if args.run:
        run_dir = (reports_root / args.run).resolve()
    else:
        runs = sorted(p.parent for p in reports_root.glob("*/run.json"))
        if not runs:
            sys.exit(f"No runs found under {reports_root}")
        run_dir = max(runs, key=lambda p: p.stat().st_mtime)
    if not (run_dir / "index.html").is_file():
        sys.exit(f"No index.html in {run_dir}. Run scripts/build_reports.py first.")
    rel = run_dir.relative_to(reports_root).as_posix()

    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=str(reports_root), **kw)

        def log_message(self, fmt, *a):  # noqa: ARG002
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
        print("\nStopped.")


if __name__ == "__main__":
    main()
