"""Consolidated Stakeholder report: one report across every run in a reports folder.

Each check shows its most recent executed result (and when/where it was last checked). Checks that were never
executed in any run are 'Not checked yet'. Also shows the most recent run and the history of runs."""
from __future__ import annotations

from collections import Counter, OrderedDict
from pathlib import Path

from reporting import charts, fix_rules, model

STAKEHOLDER_FILES = ("Stakeholder_Report.html", "Stakeholder_Report.pdf")


def _runs(reports_root: Path, project: dict, case_file: dict, env: str | None) -> list[dict]:
    runs = []
    for run_json in reports_root.glob("*/run.json"):
        try:
            m = model.build(run_json.parent, project, case_file)
        except Exception:  # noqa: BLE001 - one broken run must not block the consolidated view
            continue
        if env and m["environment"] != env:
            continue
        runs.append(m)
    return sorted(runs, key=lambda r: r["started_iso"])


def _endpoint_rows(tests: list[dict]) -> list[dict]:
    rows: "OrderedDict[str, dict]" = OrderedDict()
    for t in tests:
        row = rows.setdefault(t["endpoint"], {"endpoint": t["endpoint"], "feature": t["feature"], "total": 0, "passed": 0,
                                              "failed": 0, "broken": 0, "skipped": 0, "executed": 0, "blocked": 0,
                                              "critical_or_high": 0, "status_codes": [], "ai_note": "", "last_checked": ""})
        row["total"] += 1
        row[t["status"]] += 1
        if t["status"] != "skipped":
            row["executed"] += 1
            if t["checked_iso"] > row.get("_last_iso", ""):
                row["_last_iso"], row["last_checked"] = t["checked_iso"], t["checked_at"]
        row["blocked"] += bool(t.get("blocker"))
        row["critical_or_high"] += t.get("severity") in ("Critical", "High") and not t.get("blocker")
    for row in rows.values():
        row["health"] = model._endpoint_health(row)
        row.pop("_last_iso", None)
    return list(rows.values())


def build_model(reports_root: Path, project: dict | None = None, case_file: dict | None = None,
                env: str | None = None) -> dict | None:
    project, case_file = project or {}, case_file or {"cases": []}
    runs = _runs(reports_root, project, case_file, env)
    if not runs:
        return None

    latest_any: "OrderedDict[str, dict]" = OrderedDict()
    latest_exec: dict[str, dict] = {}
    for r in runs:
        for t in r["tests"]:
            rec = {**t, "run_id": r["run_id"], "env": r["environment"], "checked_at": r["started_at"],
                   "checked_iso": r["started_iso"]}
            latest_any[t["case_id"]] = rec
            if t["status"] != "skipped":
                latest_exec[t["case_id"]] = rec
    current = [latest_exec.get(cid, rec) for cid, rec in latest_any.items()]

    counts = {k: sum(1 for t in current if t["status"] == k) for k in ("passed", "failed", "broken", "skipped")}
    total = len(current)
    executed = total - counts["skipped"]
    blocked_tests = [t for t in current if t.get("blocker")]
    failures = [t for t in current if t["status"] in ("failed", "broken") and not t.get("blocker")]
    blocked = len(blocked_tests)
    completed = max(0, executed - blocked)
    connectivity = sum(1 for t in blocked_tests if t.get("category") == "CONNECTIVITY")
    summary = {"total": total, **counts, "executed": executed, "blocked": blocked, "completed": completed,
               "connectivity": connectivity,
               "pass_rate": round(100 * counts["passed"] / completed, 1) if completed else 0.0,
               "executed_pct": round(100 * executed / total, 1) if total else 0.0,
               "completed_pct": round(100 * completed / total, 1) if total else 0.0}

    order = {s: i for i, s in enumerate(fix_rules.SEVERITY_ORDER)}
    failures = sorted(failures, key=lambda f: (order.get(f.get("severity"), 9), f["endpoint"], f["case_id"]))
    skip_reasons = model._skip_reasons(current)
    blocker_reasons = model._blocker_reasons(current)
    min_pct = float(project.get("min_executed_percent", 80))
    latest = runs[-1]
    envs = sorted({r["environment"] for r in runs})

    history = [{"run_id": r["run_id"], "environment": r["environment"], "started_at": r["started_at"],
                "build_version": r["build_version"], **{k: r["summary"].get(k, 0) for k in
                ("total", "executed", "passed", "failed", "broken", "skipped", "blocked", "completed",
                 "pass_rate", "executed_pct", "completed_pct")},
                "verdict": r["recommendation"]["verdict"], "color": r["recommendation"]["color"]}
               for r in reversed(runs)]

    label_keys = ("domain", "api_title", "run_kind", "results_phrase", "dev_title", "qa_title", "lead_title",
                  "stakeholder_title", "area_word")
    return {
        "consolidated": True,
        **{k: latest[k] for k in label_keys if k in latest},
        "project_name": latest["project_name"],
        "environment": env or ", ".join(envs),
        "base_url": latest["base_url"],
        "build_version": latest["build_version"],
        "started_at": latest["started_at"],
        "duration_s": latest["duration_s"],
        "run_id": f"consolidated from {len(runs)} run(s), {runs[0]['started_at']} - {latest['started_at']}",
        "summary": summary,
        "tests": current,
        "failures": failures,
        "blocked_tests": blocked_tests,
        "issues": failures + blocked_tests,
        "endpoints": _endpoint_rows(current),
        "features": model.feature_overview(current),
        "by_severity": {s: sum(1 for f in failures if f.get("severity") == s) for s in fix_rules.SEVERITY_ORDER},
        "by_blocker": Counter(t.get("title", "") for t in blocked_tests),
        "skip_reasons": skip_reasons,
        "blocker_reasons": blocker_reasons,
        "skipped": [t for t in current if t["status"] == "skipped"],
        "recommendation": model._recommendation(failures, summary, skip_reasons, min_pct, blocker_reasons),
        "min_executed_pct": min_pct,
        "latest": history[0],
        "history": history,
        "runs_count": len(runs),
        "first_run_at": runs[0]["started_at"],
    }


def trend_chart(history: list[dict]) -> str:
    return charts.run_columns(list(reversed(history))[-30:])


def write(reports_root: Path, project: dict | None = None, case_file: dict | None = None,
          env: str | None = None) -> list[Path]:
    from reporting import html_reports, pdf_reports

    m = build_model(reports_root, project, case_file, env)
    if m is None:
        return []
    return [html_reports.render_stakeholder(m, reports_root, project or {}),
            pdf_reports.stakeholder_pdf(m, reports_root / "Stakeholder_Report.pdf")]
