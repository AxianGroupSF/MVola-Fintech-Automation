"""QA-only content: shareable talking points, blockers with the action QA must take, and ready answers."""
from __future__ import annotations

from reporting.fix_rules import SEVERITY_ORDER


def _skip_action(reason: str, secrets: list[str]) -> str:
    r = reason.lower()
    if "credential" in r or r.startswith("missing"):
        named = [s for s in secrets if s.lower() in r] or secrets
        return ("Provide the credentials (" + ", ".join(named) + ") in .env / .env.<environment> or CI secrets, then re-run."
                if named else "Provide the missing credentials in .env / CI secrets, then re-run.")
    if "production" in r:
        return "Expected on production - these tests never run there. Run them on a non-production environment."
    if "disabled" in r:
        return "Enable the matching flag in config/environments/<env>.json if these tests should run here."
    return "Review the reason and unblock, then re-run."


def blockers(m: dict) -> list[dict]:
    out = []
    for s in m["skip_reasons"]:
        area = "screen(s)" if m.get("domain") == "ui" else "endpoint(s)"
        out.append({"kind": "Not executed", "severity": "High" if m["recommendation"]["color"] in ("review", "grey") else "Medium",
                    "text": f"{s['count']} test(s) on {len(s['endpoints'])} {area} skipped: {s['reason']}",
                    "action": _skip_action(s["reason"], m["secrets_expected"])})
    blocked = m.get("blocked_tests") or [t for t in m.get("broken", []) if t.get("blocker")]
    by_cat: dict[str, list] = {}
    for t in blocked:
        by_cat.setdefault(t.get("category") or "TEST_ERROR", []).append(t)
    rate = by_cat.pop("RATE_LIMITED", [])
    setup = by_cat.pop("SETUP_BLOCKED", [])
    conn = by_cat.pop("CONNECTIVITY", [])
    if rate:
        sample = (rate[0].get("error") or "")[:120]
        out.append({"kind": "Rate limit", "severity": "High",
                    "text": (f"{len(rate)} test(s) could not run because the environment returned HTTP 429 "
                             f"(too many attempts) during login or setup. {sample}"),
                    "action": "Wait for the rate-limit window, use dedicated test credentials, then re-run. Do not log these as product bugs."})
    if setup:
        out.append({"kind": "Setup", "severity": "High",
                    "text": (f"{len(setup)} test(s) never started because login or fixture setup failed "
                             f"({setup[0].get('error', '')[:120]})"),
                    "action": "Restore a valid token/credentials and fix the login fixture, then re-run. Do not log these as product bugs."})
    if conn:
        out.append({"kind": "Environment", "severity": "High",
                    "text": f"{len(conn)} test(s) could not reach the service ({conn[0].get('error', '')[:120]})",
                    "action": "Check base URL, VPN/firewall and service health for this environment, then re-run. Do not log these as product bugs."})
    other = [t for ts in by_cat.values() for t in ts] + [
        t for t in m.get("broken", []) if not t.get("blocker") and t.get("category") == "TEST_ERROR"]
    if other:
        out.append({"kind": "Test error", "severity": "Medium",
                    "text": f"{len(other)} test(s) broke with unexpected errors ({', '.join(t['case_id'] for t in other[:5])})",
                    "action": ("QA to review the object locators and the screen state; raise with DEV if the UI no longer matches the design."
                               if m.get("domain") == "ui" else
                               "QA to review test data/setup; raise with DEV if the API response shape changed.")})
    unanswered = [q for q in m["open_questions"] if not q.get("answer")]
    if unanswered:
        out.append({"kind": "Open question", "severity": "Medium",
                    "text": f"{len(unanswered)} documentation question(s) still unanswered",
                    "action": ("Confirm with BA / design and update the BRS, user story, or Figma, then update test_cases/cases.json."
                               if m.get("domain") == "ui" else
                               "Confirm with BA / API owner and update test_cases/cases.json.")})
    if m["manual_cases"]:
        out.append({"kind": "Manual", "severity": "Low", "text": f"{len(m['manual_cases'])} planned case(s) are manual / not automated",
                    "action": "Execute manually or plan automation; track in the executed workbook."})
    return sorted(out, key=lambda b: SEVERITY_ORDER.index(b["severity"]))


def talking_points(m: dict) -> list[str]:
    s, rec = m["summary"], m["recommendation"]
    pts = [f"Verdict: {rec['verdict']} - {rec['reason']}",
           f"Coverage: {s['completed']} of {s['total']} automated tests completed their intended check "
           f"({s.get('completed_pct', s['executed_pct'])}%) on {m['environment']}, build {m['build_version']}. "
           f"{s['executed']} were started; {s.get('blocked', 0)} could not run.",
           f"Result of completed tests: {s['passed']} passed, {len(m['failures'])} product defect(s) "
           f"({s['pass_rate']}% pass rate of completed checks)."]
    if s.get("blocked"):
        pts.append("Could not run: " + "; ".join(
            f"{r['count']} test(s) - {r['reason']}" for r in m.get("blocker_reasons", [])[:3]) + ".")
    if m["failures"]:
        sev = ", ".join(f"{m['by_severity'].get(k, 0)} {k}" for k in SEVERITY_ORDER if m["by_severity"].get(k))
        pts.append(f"Product defects found: {len(m['failures'])} ({sev}). Top: " +
                   "; ".join(f"{f['bug_ref']} {f['endpoint']} - {f['title']}" for f in m["failures"][:3]) + ".")
    else:
        pts.append("No product defects in the checks that completed.")
    for s_ in m["skip_reasons"][:2]:
        pts.append(f"Not executed: {s_['count']} test(s) - {s_['reason']}.")
    healthy = [e["endpoint"] for e in m["endpoints"] if e["health"] == "Healthy"]
    if healthy:
        pts.append(f"Fully verified {'screens' if m.get('domain') == 'ui' else 'endpoints'} ({len(healthy)}): " + ", ".join(healthy[:6]) + ("..." if len(healthy) > 6 else "") + ".")
    if m["ai_summary"]:
        pts.append(f"Analyst view: {m['ai_summary']}")
    return pts


def faq(m: dict) -> list[tuple[str, str]]:
    s, rec = m["summary"], m["recommendation"]
    tested = ", ".join(sorted({e["feature"] or e["endpoint"] for e in m["endpoints"] if e["executed"]})) or "nothing"
    not_tested = [e["endpoint"] for e in m["endpoints"] if e["health"] == "Not tested"]
    risks = [f"{f['bug_ref']} [{f['severity']}] {f['endpoint']}: {f['title']}" for f in m["failures"][:5]]
    blockers_ = blockers(m)
    ui = m.get("domain") == "ui"
    tested_phrase = "automated UI checks" if ui else "automated API tests"
    rerun = ("Double-click 'Run UI Validation (Mac).command' / 'Run UI Validation (Windows).bat', or "
             "python scripts/run_agent.py from the ui-design-validator folder."
             if ui else
             "Double-click 'Run API Tests (Mac).command' / 'Run API Tests (Windows).bat', or "
             f"python scripts/run_tests.py --env {m['environment']} from the suite folder.")
    area = "screen(s)" if ui else "endpoint(s)"
    return [
        ("Is it ready for release?", f"{rec['verdict']}. {rec['reason']}"),
        ("What was tested?", f"{s['executed']} {tested_phrase} across: {tested}."),
        ("What was not tested, and why?",
         ((f"{s.get('blocked', 0)} test(s) could not run"
           + (": " + "; ".join(f"{r['count']} x {r['reason']}" for r in m.get('blocker_reasons', [])[:2]) if m.get("blocker_reasons") else "")
           + ". " if s.get("blocked") else "")
          + ((f"{s['skipped']} test(s) not executed" + (f", {len(not_tested)} {area} not tested at all" if not_tested else "") +
              ". " + " ".join(f"{r['count']} x {r['reason']}." for r in m["skip_reasons"])) if s["skipped"] else
             ("" if s.get("blocked") else "Everything automated completed.")))),
        ("What are the main risks?",
         ("; ".join(f"{r['count']} checks could not run: {r['reason']}" for r in m.get("blocker_reasons", [])[:2])
          + ("; " if (m.get("blocker_reasons") and risks) else "")
          + ("; ".join(risks) if risks else ("No product defects in the checks that completed." if not m.get("blocker_reasons") else "")))
         + (" The main risk is the untested scope above." if s["skipped"] and not m.get("blocker_reasons") else "")),
        ("What is blocking QA?", "; ".join(f"{b['text']} -> {b['action']}" for b in blockers_) if blockers_ else "Nothing."),
        ("Where is the evidence?", "Dev_Report (logs and evidence), allure-report/index.html when present, logs/execution.log, "
                                   "Ready to Report Bugs.md and the executed test case workbook in this run folder."),
        ("How do I re-run it?", rerun),
    ]
