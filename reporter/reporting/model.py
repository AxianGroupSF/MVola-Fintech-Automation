"""Builds one report model from run.json + cases.json + optional ai_insights.json.
Every report (HTML, PDF, email, bug file, workbook) renders from this model."""
from __future__ import annotations

import json
from collections import Counter, OrderedDict
from datetime import datetime
from pathlib import Path

from reporting import fix_rules

STATUS_LABEL = {"passed": "Passed", "failed": "Failed", "broken": "Broken", "skipped": "Skipped"}
PLAIN_STATUS = {"passed": "Working", "failed": "Needs attention", "broken": "Needs attention", "skipped": "Not checked"}
BLOCKER_PLAIN_STATUS = "Could not run"


def _labels(domain: str, project_name: str) -> dict:
    """Wording for report titles. Missing domain stays the original API wording."""
    if domain == "ui":
        return {
            "api_title": project_name,
            "run_kind": "Functional validation run",
            "results_phrase": "Functional validation results",
            "dev_title": "Developer Report - Functional Validation",
            "qa_title": "QA Report - Functional Validation",
            "lead_title": "QA Lead Report - Functional Validation",
            "stakeholder_title": "Functional Quality Summary",
            "area_word": "Screens",
        }
    title = project_name if project_name.lower().endswith("api") else f"{project_name} API"
    return {
        "api_title": title,
        "run_kind": "API test run",
        "results_phrase": "API test results",
        "dev_title": "Developer Report - API Test Execution",
        "qa_title": "QA Report - API Test Execution",
        "lead_title": "QA Lead Report - API Test Execution",
        "stakeholder_title": "API Quality Summary",
        "area_word": "Endpoints",
    }


def _parse_time(value: str) -> datetime | None:
    try:
        return datetime.fromisoformat(value)
    except (TypeError, ValueError):
        return None


def _blocker_reason(blocker_reasons: list[dict], blocked: int) -> str:
    top = blocker_reasons[0]["reason"] if blocker_reasons else "environment / setup"
    return f"{blocked} check(s) could not run ({top})"


def _recommendation(failures: list[dict], totals: dict, skip_reasons: list[dict], min_executed_pct: float,
                    blocker_reasons: list[dict] | None = None) -> dict:
    """Verdicts use product defects only. Environment/setup blockers count as 'could not run'."""
    blocker_reasons = blocker_reasons or []
    sev = Counter(f["severity"] for f in failures)
    executed, total, skipped = totals["executed"], totals["total"], totals["skipped"]
    blocked = totals.get("blocked", 0)
    completed = totals.get("completed", max(0, executed - blocked))
    completed_pct = totals.get("completed_pct", round(100 * completed / total, 1) if total else 0.0)
    connectivity = totals.get("connectivity", 0)
    top_skip = skip_reasons[0]["reason"] if skip_reasons else ""
    not_run = f" {skipped} check(s) were not executed." if skipped else ""
    block_note = f" {_blocker_reason(blocker_reasons, blocked)}." if blocked else ""
    if executed == 0:
        return {"verdict": "Inconclusive", "color": "grey",
                "reason": f"None of the {total} checks were executed (main reason: {top_skip}). Fix the blocker and re-run."}
    if connectivity and connectivity >= max(1, executed // 2):
        return {"verdict": "Inconclusive", "color": "grey",
                "reason": (f"{connectivity} of {executed} checks could not reach the service. "
                           "Fix the environment and re-run before deciding.")}
    if blocked and blocked >= max(1, executed // 2):
        return {"verdict": "Review required", "color": "review",
                "reason": (f"{_blocker_reason(blocker_reasons, blocked)}. Only {completed} of {total} checks "
                           f"completed their intended assertion ({completed_pct}%). The results are not enough to "
                           "approve a release — resolve the blocker and re-run.")}
    if completed_pct < min_executed_pct:
        extra = f"{skipped} were not executed (main reason: {top_skip}). " if skipped else ""
        extra += f"{_blocker_reason(blocker_reasons, blocked)}. " if blocked else ""
        return {"verdict": "Review required", "color": "review",
                "reason": (f"Only {completed} of {total} checks completed ({completed_pct}%, minimum {min_executed_pct:g}%). "
                           f"{extra}The results are not enough to approve a release - resolve the blocker and re-run.")}
    if sev["Critical"]:
        return {"verdict": "No-Go", "color": "red",
                "reason": f"{sev['Critical']} critical issue(s) found (security or crashes). Release should wait for fixes.{not_run}{block_note}"}
    if sev["High"]:
        return {"verdict": "Go with conditions", "color": "amber",
                "reason": f"{sev['High']} high-severity product issue(s) found. Release only with an agreed fix plan and owner sign-off.{not_run}{block_note}"}
    if failures:
        return {"verdict": "Go with known issues", "color": "amber",
                "reason": f"{len(failures)} medium/low product issue(s) found. Track them for the next release.{not_run}{block_note}"}
    if blocked:
        return {"verdict": "Review required", "color": "review",
                "reason": (f"{_blocker_reason(blocker_reasons, blocked)}. The remaining executed checks passed, but "
                           "the blocked checks still need a clean re-run before a release decision.")}
    return {"verdict": "Go", "color": "green", "reason": f"All executed checks passed.{not_run}"}


def _skip_reasons(tests: list[dict]) -> list[dict]:
    groups: dict[str, dict] = {}
    for t in tests:
        if t["status"] != "skipped":
            continue
        row = groups.setdefault(t["skip_reason"] or "No reason given", {"reason": t["skip_reason"] or "No reason given",
                                                                     "count": 0, "endpoints": set()})
        row["count"] += 1
        row["endpoints"].add(t["endpoint"])
    out = sorted(groups.values(), key=lambda r: -r["count"])
    for row in out:
        row["endpoints"] = sorted(row["endpoints"])
    return out


def _blocker_reasons(tests: list[dict]) -> list[dict]:
    groups: dict[str, dict] = {}
    for t in tests:
        if not t.get("blocker"):
            continue
        reason = t.get("title") or "Could not run"
        row = groups.setdefault(reason, {"reason": reason, "category": t.get("category", ""),
                                         "count": 0, "endpoints": set()})
        row["count"] += 1
        row["endpoints"].add(t["endpoint"])
    out = sorted(groups.values(), key=lambda r: -r["count"])
    for row in out:
        row["endpoints"] = sorted(row["endpoints"])
    return out


_FEATURE_ORDER = {"Failing": 0, "At risk": 1, "Blocked": 2, "Partly tested": 3, "Not tested": 4, "Healthy": 5}


def _feature_summary(row: dict) -> str:
    """Overview text: use-case / area names only (no health prefix like 'Healthy. Covers')."""
    areas = row["areas"][:4]
    extra = len(row["areas"]) - len(areas)
    if not areas:
        return ""
    return ", ".join(areas) + (f" (+{extra} more)" if extra else "")


def feature_overview(tests: list[dict]) -> list[dict]:
    """One row per feature/module, in the same population as the run totals."""
    groups: "OrderedDict[str, dict]" = OrderedDict()
    for t in tests:
        name = (t.get("feature") or "").strip() or "Unassigned"
        row = groups.setdefault(name, {"feature": name, "total": 0, "passed": 0, "failed": 0, "broken": 0,
                                       "skipped": 0, "blocked": 0, "executed": 0, "critical_or_high": 0, "areas": []})
        row["total"] += 1
        row[t["status"]] += 1
        row["executed"] += t["status"] != "skipped"
        row["blocked"] += bool(t.get("blocker"))
        row["critical_or_high"] += t.get("severity") in ("Critical", "High") and not t.get("blocker")
        area = (t.get("endpoint") or "").strip()
        if area and area not in row["areas"]:
            row["areas"].append(area)
    out = []
    for row in groups.values():
        row["product_fail"] = row["failed"] + row["broken"] - row["blocked"]
        row["health"] = _endpoint_health(row)
        row["summary"] = _feature_summary(row)
        out.append(row)
    out.sort(key=lambda r: (_FEATURE_ORDER.get(r["health"], 9), r["feature"].lower()))
    return out


def _endpoint_health(row: dict) -> str:
    if row["executed"] == 0:
        return "Not tested"
    blocked = row.get("blocked", 0)
    product_fail = row["failed"] + row["broken"] - blocked
    if blocked and product_fail == 0 and row["passed"] == 0:
        return "Blocked"
    if row["critical_or_high"]:
        return "Failing"
    if product_fail:
        return "At risk"
    if row["skipped"] or blocked:
        return "Partly tested"
    return "Healthy"


_CELL_ORDER = ["failed", "broken", "skipped", "manual", "passed"]


def _coverage_matrix(planned: list[dict], tests: list[dict]) -> dict:
    """Endpoint x expected status code -> worst result among the cases planned for that cell."""
    status_by_case = {t["case_id"]: t["status"] for t in tests}
    rows: "OrderedDict[str, dict]" = OrderedDict()
    codes: set = set()
    for c in planned:
        code = c.get("expected_status")
        if not code:
            continue
        codes.add(code)
        cell_status = status_by_case.get(c["id"], "manual")
        row = rows.setdefault(c.get("endpoint", "Unmapped"), {})
        current = row.get(code)
        if current is None or _CELL_ORDER.index(cell_status) < _CELL_ORDER.index(current):
            row[code] = cell_status
    return {"codes": sorted(codes), "rows": [{"endpoint": ep, "cells": cells} for ep, cells in rows.items()]}


TEST_DEFAULTS = {
    "nodeid": "", "name": "", "endpoint": "Unmapped", "feature": "", "summary": "", "test_type": "Functional",
    "priority": "Medium", "expected_result": "", "markers": [], "status": "passed", "duration_ms": 0, "error": None,
    "error_kind": None, "error_details": {}, "traceback": "", "skip_reason": "", "expected_status": [], "calls": [],
    "log": "", "attachments": [],
}


def _normalise(t: dict) -> dict:
    out = {**TEST_DEFAULTS, **t}
    out["case_id"] = out.get("case_id") or out["nodeid"] or out["name"]
    out["summary"] = out["summary"] or out["name"] or out["case_id"]
    return out


def _unrun_reason(case: dict) -> str:
    """Plain reason for a planned case that has no result in run.json."""
    reason = (case.get("manual_reason") or "").strip()
    if reason:
        return reason
    if case.get("automation", "Automated") != "Automated":
        return "Marked Manual — not executed in this run"
    return "Planned automated case was not present in this run"


def _synthetic_unrun(case: dict) -> dict:
    """Build a could-not-run (blocker) record for a planned case missing from the run."""
    reason = _unrun_reason(case)
    return {
        "case_id": case["id"],
        "status": "broken",
        "endpoint": case.get("endpoint") or case.get("feature") or "Unmapped",
        "feature": case.get("feature") or "",
        "summary": case.get("summary") or case["id"],
        "expected_result": case.get("expected_result") or "",
        "test_type": case.get("test_type") or "Functional",
        "priority": case.get("priority") or "Medium",
        "duration_ms": 0,
        "error": f"NotAutomated: {reason}",
        "error_kind": "not_automated",
        "skip_reason": reason,
    }


def _merge_unrun_planned(run_tests: list[dict], planned: list[dict]) -> list[dict]:
    """Append planned cases with no run result as NotAutomated blockers (Could not run)."""
    present = {t.get("case_id") or t.get("nodeid") or t.get("name") for t in run_tests}
    extra = [_synthetic_unrun(c) for c in planned if c.get("id") and c["id"] not in present]
    return list(run_tests) + extra


def build(run_dir: Path, project: dict | None = None, case_file: dict | None = None) -> dict:
    """project: suite/project settings (project_name, build_version, assignee, min_executed_percent, ...).
    case_file: planned test cases ({"cases": [...], "open_questions": [...]}); optional."""
    project, case_file = project or {}, case_file or {"cases": []}
    run = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    run.setdefault("project_name", project.get("project_name", "Project"))
    run.setdefault("environment", "")
    run.setdefault("base_url", "")
    run.setdefault("run_id", run_dir.name)
    ai_path = run_dir / "ai_insights.json"
    ai = json.loads(ai_path.read_text(encoding="utf-8")) if ai_path.exists() else {}
    ai_tests, ai_endpoints = ai.get("tests", {}), ai.get("endpoints", {})

    planned = case_file.get("cases", [])
    raw_tests = _merge_unrun_planned(run.get("tests") or [], planned)

    tests = []
    for t in raw_tests:
        t = _normalise(t)
        t["status_label"] = STATUS_LABEL.get(t["status"], t["status"])
        t["plain_status"] = PLAIN_STATUS.get(t["status"], t["status"])
        last = t["calls"][-1] if t["calls"] else {}
        t["actual_status"] = last.get("status")
        t["actual_result"] = (
            f"HTTP {last.get('status')}" if last.get("status") else (last.get("error") or "")
        )
        if t["status"] in ("failed", "broken"):
            t.update(fix_rules.suggest(t))
            t["ai_fix"] = ai_tests.get(t["case_id"], "")
            detail = (t["error"] or "").split(": ", 1)[-1][:500] if t["error"] else ""
            t["actual_result"] = detail or (t["error"] or "")[:500]
            if t.get("category") == "NOT_AUTOMATED" and (t.get("skip_reason") or detail):
                # Group Could not run by the concrete manual / missing reason, not the category label.
                t["title"] = (t.get("skip_reason") or detail)[:200]
            if t.get("blocker"):
                t["plain_status"] = BLOCKER_PLAIN_STATUS
                t["status_label"] = "Could not run"
        elif t["status"] == "skipped":
            t["actual_result"] = f"Not executed: {t['skip_reason']}"
        else:
            t["actual_result"] = f"As expected ({t['actual_result']})" if t["actual_result"] else "As expected"
        tests.append(t)

    totals = Counter(t["status"] for t in tests)
    total = len(tests)
    executed = total - totals["skipped"]
    blocked_tests = [t for t in tests if t.get("blocker")]
    product_failures = [t for t in tests if t["status"] in ("failed", "broken") and not t.get("blocker")]
    blocked = len(blocked_tests)
    completed = max(0, executed - blocked)
    connectivity = sum(1 for t in blocked_tests if t.get("category") == "CONNECTIVITY")
    summary = {
        "total": total,
        "passed": totals["passed"],
        "failed": totals["failed"],
        "broken": totals["broken"],
        "skipped": totals["skipped"],
        "executed": executed,
        "blocked": blocked,
        "completed": completed,
        "connectivity": connectivity,
        "pass_rate": round(100 * totals["passed"] / completed, 1) if completed else 0.0,
        "executed_pct": round(100 * executed / total, 1) if total else 0.0,
        "completed_pct": round(100 * completed / total, 1) if total else 0.0,
    }

    endpoints: "OrderedDict[str, dict]" = OrderedDict()
    for t in tests:
        row = endpoints.setdefault(t["endpoint"], {"endpoint": t["endpoint"], "feature": t["feature"], "total": 0,
                                                    "passed": 0, "failed": 0, "broken": 0, "skipped": 0,
                                                    "executed": 0, "blocked": 0, "critical_or_high": 0, "status_codes": set()})
        row["total"] += 1
        row[t["status"]] += 1
        row["executed"] += t["status"] != "skipped"
        row["blocked"] += bool(t.get("blocker"))
        row["critical_or_high"] += t.get("severity") in ("Critical", "High") and not t.get("blocker")
        for code in t.get("expected_status") or []:
            row["status_codes"].add(code)
    for row in endpoints.values():
        row["health"] = _endpoint_health(row)
        row["status_codes"] = sorted(row["status_codes"])
        row["ai_note"] = ai_endpoints.get(row["endpoint"], "")

    skip_reasons = _skip_reasons(tests)
    blocker_reasons = _blocker_reasons(tests)
    min_executed_pct = float(project.get("min_executed_percent", 80))
    failures = product_failures
    order = {s: i for i, s in enumerate(fix_rules.SEVERITY_ORDER)}
    failures.sort(key=lambda f: (order.get(f["severity"], 9), f["endpoint"], f["case_id"]))
    for i, f in enumerate(failures, 1):
        f["bug_ref"] = f"BUG-{i:03d}"
    issues = sorted(failures + blocked_tests,
                    key=lambda f: (0 if f.get("blocker") else 1, order.get(f.get("severity"), 9),
                                   f["endpoint"], f["case_id"]))

    started, finished = _parse_time(run.get("started_at")), _parse_time(run.get("finished_at"))
    duration_s = round((finished - started).total_seconds(), 1) if started and finished else 0

    # Unrun planned cases are already in tests as Could not run blockers — keep Manual section
    # only for Manual cases that somehow still lack a result (should be empty after merge).
    present_ids = {t["case_id"] for t in tests}
    manual = [c for c in planned if c.get("automation", "Automated") != "Automated" and c["id"] not in present_ids]

    domain = (run.get("domain") or project.get("domain") or "api").strip().lower()
    labels = _labels(domain, run["project_name"])
    return {
        "run_dir": str(run_dir),
        "run_id": run["run_id"],
        "project_name": run["project_name"],
        "domain": domain,
        "api_title": labels["api_title"],
        "run_kind": labels["run_kind"],
        "results_phrase": labels["results_phrase"],
        "dev_title": labels["dev_title"],
        "qa_title": labels["qa_title"],
        "lead_title": labels["lead_title"],
        "stakeholder_title": labels["stakeholder_title"],
        "area_word": labels["area_word"],
        "environment": run["environment"],
        "base_url": run["base_url"],
        "build_version": run.get("build_version") or project.get("build_version") or "TBD",
        "assignee": run.get("assignee") or project.get("assignee", ""),
        "started_iso": started.isoformat() if started else "",
        "started_at": started.astimezone().strftime("%d %b %Y, %H:%M") if started else "",
        "finished_at": finished.astimezone().strftime("%d %b %Y, %H:%M") if finished else "",
        "duration_s": duration_s,
        "python": run.get("python", ""),
        "platform": run.get("platform", ""),
        "summary": summary,
        "tests": tests,
        "failures": failures,
        "blocked_tests": blocked_tests,
        "issues": issues,
        "endpoints": list(endpoints.values()),
        "features": feature_overview(tests),
        "by_severity": Counter(f["severity"] for f in failures),
        "by_category": Counter(f["title"] for f in failures),
        "by_blocker": Counter(t["title"] for t in blocked_tests),
        "by_type": Counter(t["test_type"].split(" / ")[0] for t in tests),
        "recommendation": _recommendation(failures, summary, skip_reasons, min_executed_pct, blocker_reasons),
        "min_executed_pct": min_executed_pct,
        "skip_reasons": skip_reasons,
        "blocker_reasons": blocker_reasons,
        "ai_summary": ai.get("overall_summary", ""),
        "ai_generated_by": ai.get("generated_by", ""),
        "planned_cases": planned,
        "manual_cases": manual,
        "skipped": [t for t in tests if t["status"] == "skipped"],
        "broken": [t for t in tests if t["status"] == "broken"],
        "open_questions": case_file.get("open_questions", []),
        "slowest": sorted((t for t in tests if t["status"] != "skipped"), key=lambda t: -t["duration_ms"])[:10],
        "coverage_matrix": _coverage_matrix(planned, tests),
        "secrets_expected": project.get("secrets", []),
    }
