"""Writes 'Ready to Report Bugs.md' (+ .json): everything another agent or human needs to log Jira bugs
without re-running the tests."""
from __future__ import annotations

import json
from pathlib import Path

JIRA_PRIORITY = {"Critical": "Highest", "High": "High", "Medium": "Medium", "Low": "Low"}


def _bug(f: dict, m: dict) -> dict:
    last = f["calls"][-1] if f["calls"] else {}
    steps = []
    for i, c in enumerate(f["calls"], 1):
        steps.append(f"Send `{c['method']} {c['url']}` as the **{c.get('client')}** client"
                     + (" with the request body shown in Evidence." if c.get("request_body") else "."))
    if not steps:
        if m.get("domain") == "ui":
            steps.append("Open the screen named in the test case and compare the visible UI with the expected result in Evidence.")
        else:
            steps.append("See the test log in Evidence - the failure happened before any HTTP call.")
    steps.append("Observe the response status and body.")
    expected, actual = f.get("expected_status") or [], f.get("actual_status")
    if f.get("error_kind") == "status" and expected and actual:
        headline = f"{f['title']}: expected HTTP {' or '.join(map(str, expected))}, got {actual}"
    else:
        headline = f"{f['title']}: {(f['error'] or '').split(': ', 1)[-1][:120]}"
    return {
        "bug_ref": f["bug_ref"],
        "summary": f"[{'UI' if m.get('domain') == 'ui' else 'API'}][{f['endpoint']}] {headline}"[:250],
        "scenario": f["summary"],
        "issue_type": "Bug",
        "severity": f["severity"],
        "jira_priority": JIRA_PRIORITY.get(f["severity"], "Medium"),
        "labels": ["ui" if m.get("domain") == "ui" else "api", "automation", f["category"].lower(), m["environment"]],
        "component_endpoint": f["endpoint"],
        "feature": f["feature"],
        "test_case_id": f["case_id"],
        "environment": f"{m['environment']} ({m['base_url']})",
        "build_version": m["build_version"],
        "detected_at": m["started_at"],
        "run_id": m["run_id"],
        "preconditions": next((c.get("preconditions", "") for c in m["planned_cases"] if c["id"] == f["case_id"]), ""),
        "steps_to_reproduce": steps,
        "expected_result": f["expected_result"] or f"HTTP {' or '.join(map(str, f['expected_status']))}",
        "actual_result": f["error"],
        "actual_status": last.get("status"),
        "suggested_fix": f.get("ai_fix") or f["fix"],
        "rule_based_fix": f["fix"],
        "evidence": [{"method": c["method"], "url": c["url"], "status": c.get("status"), "error": c.get("error"),
                      "duration_ms": c.get("duration_ms"), "curl": c["curl"], "request_body": c.get("request_body", ""),
                      "response_headers": c.get("response_headers", {}), "response_body": c.get("response_body", "")}
                     for c in f["calls"]],
        "log_excerpt": "\n".join((f["log"] or "").splitlines()[-60:]),
    }


def write(m: dict, out_dir: Path) -> list[Path]:
    bugs = [_bug(f, m) for f in m["failures"]]
    blocked = m.get("blocked_tests") or []
    md = [f"# Ready to Report Bugs - {m['project_name']}", "",
          "> **For the agent or person logging these in Jira:** every bug below is a **product defect**. "
          "Environment, rate-limit and setup blockers are listed separately and must not be filed. "
          "Create one Jira issue per `BUG-nnn` section, copy *Summary* into the Jira summary, map *Jira priority*, "
          "add the *Labels*, and paste *Steps*, *Expected*, *Actual*, *Suggested fix* and *Evidence* into the "
          "description. Attach the Dev_Report.pdf of this run. Secrets are masked as `***REDACTED***`; replace with "
          "a valid token to reproduce. Do not re-run the suite - all evidence is here. Machine-readable copy: "
          "`ready_to_report_bugs.json`.", "",
          "| Field | Value |", "| --- | --- |",
          f"| Project | {m['project_name']} |", f"| Environment | {m['environment']} ({m['base_url']}) |",
          f"| Build | {m['build_version']} |", f"| Run | {m['run_id']} ({m['started_at']}) |",
          f"| Product bugs | {len(bugs)} |",
          f"| Excluded (could not run) | {len(blocked)} |", ""]
    if blocked:
        md += ["## Could not run — do not file these", "",
               "These checks never completed their intended assertion. They are environment or setup blockers, "
               "not product defects.", "",
               "| Checks | Problem |", "| --- | --- |"]
        md += [f"| {r['count']} | {r['reason']} |" for r in m.get("blocker_reasons", [])]
        md += ["", ""]
    if not bugs:
        md.append("No product defects in this run - nothing to report.")
    else:
        md += ["## Index", "", "| Ref | Severity | Endpoint | Summary |", "| --- | --- | --- | --- |"]
        md += [f"| {b['bug_ref']} | {b['severity']} | `{b['component_endpoint']}` | {b['summary']} |" for b in bugs]
        md.append("")
    for b in bugs:
        md += ["---", "", f"## {b['bug_ref']} - {b['summary']}", "",
               "| Field | Value |", "| --- | --- |",
               f"| Issue type | {b['issue_type']} |", f"| Severity | {b['severity']} |",
               f"| Jira priority | {b['jira_priority']} |", f"| Labels | {', '.join(b['labels'])} |",
               f"| Endpoint | `{b['component_endpoint']}` |", f"| Feature | {b['feature']} |",
               f"| Test case | {b['test_case_id']} - {b['scenario']} |", f"| Environment | {b['environment']} |",
               f"| Build | {b['build_version']} |", f"| Detected | {b['detected_at']} (run {b['run_id']}) |", "",
               "**Preconditions**", "", b["preconditions"] or "-", "",
               "**Steps to reproduce**", ""] + [f"{i}. {s}" for i, s in enumerate(b["steps_to_reproduce"], 1)] + [
               "", "**Expected result**", "", b["expected_result"], "",
               "**Actual result**", "", b["actual_result"] or "-", "",
               "**Suggested fix**", "", b["suggested_fix"], "", "**Evidence**", ""]
        for e in b["evidence"]:
            md += [f"`{e['method']} {e['url']}` -> **{e['status'] or e['error']}** ({e['duration_ms']} ms)", "",
                   "```bash", e["curl"], "```"]
            if e["request_body"]:
                md += ["Request body:", "```json", e["request_body"], "```"]
            if e["response_body"]:
                md += ["Response body:", "```json", e["response_body"], "```"]
        md += ["", "<details><summary>Log excerpt</summary>", "", "```text", b["log_excerpt"], "```", "", "</details>", ""]

    md_path, json_path = out_dir / "Ready to Report Bugs.md", out_dir / "ready_to_report_bugs.json"
    md_path.write_text("\n".join(md), encoding="utf-8")
    json_path.write_text(json.dumps({
        "project": m["project_name"], "run_id": m["run_id"], "bugs": bugs,
        "excluded": [{"reason": r["reason"], "count": r["count"], "category": r.get("category", ""),
                      "endpoints": r.get("endpoints", [])} for r in m.get("blocker_reasons", [])],
    }, indent=2, ensure_ascii=False), encoding="utf-8")
    return [md_path, json_path]
