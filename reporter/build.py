"""CLI entry for the shared reporting engine (templates live in api/reporting/).

Usage (from repo root):
    python reporter/build.py --run reports/<run_id>
    python reporter/build.py --stakeholder --reports-dir reports
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "api"))

from reporting.build import build_all, build_stakeholder  # noqa: E402


def _project(reports_dir: Path | None = None) -> dict:
    for candidate in (ROOT / "config" / "project.json", ROOT / "api" / "config" / "project.json"):
        if candidate.is_file():
            data = json.loads(candidate.read_text(encoding="utf-8"))
            if reports_dir and (reports_dir.parent / "config" / "project.json").is_file():
                # Prefer the suite next to reports/ when building mobile runs.
                suite_cfg = reports_dir.parent / "config" / "project.json"
                if suite_cfg.is_file():
                    return json.loads(suite_cfg.read_text(encoding="utf-8"))
            return data
    return {"project_name": "Project", "domain": "ui", "reports": {"optional": []}}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, help="Run folder containing run.json")
    parser.add_argument("--reports", help="Optional reports CSV or 'all' / 'none'")
    parser.add_argument("--stakeholder", action="store_true")
    parser.add_argument("--reports-dir", type=Path, default=ROOT / "reports")
    parser.add_argument("--env", help="Limit consolidated Stakeholder to one environment")
    args = parser.parse_args()

    if args.stakeholder:
        reports_dir = args.reports_dir.resolve()
        out = build_stakeholder(reports_dir, _project(reports_dir), {"cases": []}, args.env)
        print(f"Consolidated Stakeholder report: {out[0] if out else 'no runs found'}")
        return

    if not args.run:
        parser.error("--run is required unless --stakeholder is set")
    run_dir = args.run if args.run.is_absolute() else (ROOT / args.run)
    run_dir = run_dir.resolve()
    project = _project(run_dir.parent)
    project.setdefault("domain", project.get("domain") or "ui")
    m = build_all(run_dir, project, {"cases": []}, optional=args.reports, tools_dir=ROOT / ".tools")
    extra = f" + optional: {', '.join(m['optional_built'])}" if m["optional_built"] else ""
    print(f"Reports rebuilt in {run_dir} - verdict: {m['recommendation']['verdict']}{extra}")


if __name__ == "__main__":
    main()
