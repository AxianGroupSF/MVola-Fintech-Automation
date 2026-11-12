"""Build mandatory Dev / QA / Ready-to-Report-Bugs reports for a mobile UI run.

Uses the shared engine under reporter/reporting (same templates as the API suite).
Does not re-run tests.

Usage:
    python scripts/build_reports.py
    python scripts/build_reports.py --run reports/<run_id>
    python scripts/build_reports.py --reports stakeholder,qa_lead
    python scripts/build_reports.py --stakeholder [--env qa]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API_ROOT = ROOT / "reporter"
sys.path.insert(0, str(API_ROOT))

from reporting.build import build_all, build_stakeholder  # noqa: E402


def load_project() -> dict:
    path = ROOT / "config" / "project.json"
    if not path.is_file():
        return {"project_name": "MvolaAutomation", "domain": "ui", "reports": {"optional": []}}
    return json.loads(path.read_text(encoding="utf-8"))


def latest_run() -> Path:
    runs = sorted(p for p in (ROOT / "reports").glob("*/run.json"))
    if not runs:
        sys.exit("No runs found under reports/. Run the suite first (or convert a sample).")
    return max(runs, key=lambda p: p.stat().st_mtime).parent


def write_latest_pointer(run_dir: Path) -> Path:
    rel = run_dir.relative_to(ROOT / "reports").as_posix()
    pointer = ROOT / "reports" / "Open Latest Report.html"
    pointer.parent.mkdir(parents=True, exist_ok=True)
    pointer.write_text(
        "<!DOCTYPE html><meta charset=\"utf-8\"><title>Open latest report</title>"
        f"<p><a href=\"{rel}/index.html\">Open latest run ({rel})</a></p>"
        "<p id=\"w\" style=\"display:none;font-family:sans-serif;max-width:40rem\">"
        "Chrome blocks these links when this file is opened directly. "
        "Double-click <b>Open Reports (Mac).command</b> or <b>Open Reports (Windows).bat</b> "
        "in the reports folder, or run <code>python scripts/open_reports.py</code>.</p>"
        "<script>if(location.protocol==='file:'){document.getElementById('w').style.display='block'}"
        f"else{{location.replace('{rel}/index.html')}}</script>",
        encoding="utf-8",
    )
    return pointer


def build(run_dir: Path, reports: str | None = None) -> dict:
    project = load_project()
    project.setdefault("domain", "ui")
    m = build_all(
        run_dir,
        project,
        {"cases": []},
        optional=reports,
        tools_dir=ROOT / ".tools",
        say=lambda msg: print(f"[mobile-reports] {msg}", flush=True),
    )
    write_latest_pointer(run_dir)
    return m


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run", type=Path, help="Run folder (default: latest under reports/)")
    parser.add_argument("--reports", help="Optional reports: allure, stakeholder, qa_lead, cto_email, all, none")
    parser.add_argument("--stakeholder", action="store_true", help="Only build the consolidated Stakeholder report")
    parser.add_argument("--env", help="With --stakeholder: limit to one environment")
    args = parser.parse_args()
    if args.stakeholder:
        out = build_stakeholder(ROOT / "reports", load_project(), {"cases": []}, args.env)
        print(f"Consolidated Stakeholder report: {out[0] if out else 'no runs found'}")
        return
    run_dir = (
        args.run if args.run and args.run.is_absolute() else (ROOT / args.run) if args.run else latest_run()
    ).resolve()
    if not (run_dir / "run.json").is_file():
        sys.exit(f"No run.json in {run_dir}")
    m = build(run_dir, args.reports)
    extra = f" + optional: {', '.join(m['optional_built'])}" if m["optional_built"] else ""
    print(
        f"Reports rebuilt in {run_dir} - verdict: {m['recommendation']['verdict']} "
        f"(Dev, QA, Ready to Report Bugs{extra})"
    )


if __name__ == "__main__":
    main()
