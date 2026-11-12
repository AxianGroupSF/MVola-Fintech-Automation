"""PDF versions of the Dev, QA Lead and Stakeholder reports (pure Python, ReportLab)."""
from __future__ import annotations

import json
import textwrap
from pathlib import Path
from xml.sax.saxutils import escape

from reportlab.graphics.charts.barcharts import VerticalBarChart
from reportlab.graphics.charts.piecharts import Pie
from reportlab.graphics.shapes import Drawing, String
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (KeepTogether, PageBreak, Paragraph, Preformatted, SimpleDocTemplate, Spacer,
                                Table, TableStyle)

from reporting import qa_points
from reporting.fix_rules import SEVERITY_ORDER
from reporting.html_reports import STAKEHOLDER_LABEL, plain_issues, plain_verdict

BRAND = colors.HexColor("#1f4e79")
C = {"passed": colors.HexColor("#1e9e5a"), "failed": colors.HexColor("#d64545"),
     "broken": colors.HexColor("#e08a1e"), "skipped": colors.HexColor("#9aa3ad"),
     "Critical": colors.HexColor("#b3261e"), "High": colors.HexColor("#e0662a"),
     "Medium": colors.HexColor("#e0a91e"), "Low": colors.HexColor("#6b8fb3"),
     "green": colors.HexColor("#1e9e5a"), "red": colors.HexColor("#d64545"),
     "amber": colors.HexColor("#e0a91e"), "grey": colors.HexColor("#9aa3ad"), "review": colors.HexColor("#6b4fbb")}

_ss = getSampleStyleSheet()
S = {
    "h1": ParagraphStyle("h1", parent=_ss["Title"], fontSize=18, textColor=BRAND, alignment=0, spaceAfter=4),
    "h2": ParagraphStyle("h2", parent=_ss["Heading2"], fontSize=13, textColor=BRAND, spaceBefore=10, spaceAfter=6),
    "h3": ParagraphStyle("h3", parent=_ss["Heading3"], fontSize=10.5, spaceBefore=6, spaceAfter=3),
    "body": ParagraphStyle("body", parent=_ss["BodyText"], fontSize=9.5, leading=13),
    "small": ParagraphStyle("small", parent=_ss["BodyText"], fontSize=8, leading=10, textColor=colors.HexColor("#5b6773")),
    "cell": ParagraphStyle("cell", parent=_ss["BodyText"], fontSize=8, leading=10),
    "kpi_v": ParagraphStyle("kpi_v", parent=_ss["BodyText"], fontSize=18, leading=22, alignment=TA_CENTER),
    "kpi_l": ParagraphStyle("kpi_l", parent=_ss["BodyText"], fontSize=7.5, alignment=TA_CENTER,
                            textColor=colors.HexColor("#5b6773")),
    "code": ParagraphStyle("code", fontName="Courier", fontSize=7, leading=8.5, backColor=colors.HexColor("#f1f3f5"),
                           borderPadding=4, spaceBefore=2, spaceAfter=6),
    "fix": ParagraphStyle("fix", parent=_ss["BodyText"], fontSize=8.5, leading=11,
                          backColor=colors.HexColor("#fff8e6"), borderPadding=5, spaceAfter=6),
}


def P(text, style="body") -> Paragraph:
    return Paragraph(escape(str(text if text is not None else "")).replace("\n", "<br/>"), S[style])


def _ascii(text: str) -> str:
    return (text or "").replace("\u2192", "->").replace("\u2014", "-").replace("\u2013", "-")


def code(text: str, width: int = 118, max_lines: int = 160) -> Preformatted:
    lines = []
    for raw in _ascii(text).splitlines() or [""]:
        lines.extend(textwrap.wrap(raw, width) or [""])
    if len(lines) > max_lines:
        lines = lines[:max_lines] + [f"... [{len(lines) - max_lines} more lines - see HTML report / execution.log]"]
    return Preformatted("\n".join(lines), S["code"])


def table(rows, widths, header=True, zebra=True) -> Table:
    data = [[c if not isinstance(c, str) else Paragraph(escape(c).replace("\n", "<br/>"), S["cell"]) for c in r]
            for r in rows]
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    style = [("GRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dbe1")),
             ("VALIGN", (0, 0), (-1, -1), "TOP"),
             ("LEFTPADDING", (0, 0), (-1, -1), 4), ("RIGHTPADDING", (0, 0), (-1, -1), 4)]
    if header:
        style += [("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#eef2f6"))]
    if zebra:
        for i in range(1 if header else 0, len(rows)):
            if i % 2 == 0:
                style.append(("BACKGROUND", (0, i), (-1, i), colors.HexColor("#fafbfc")))
    t.setStyle(TableStyle(style))
    return t


def kpis(items: list[tuple[str, str, object]]) -> Table:
    cells = [[Paragraph(f'<font color="#{c.hexval()[2:] if c else "1d2733"}"><b>{v}</b></font>', S["kpi_v"]),
              Paragraph(escape(l.upper()), S["kpi_l"])] for l, v, c in items]
    t = Table([[c[0] for c in cells], [c[1] for c in cells]], colWidths=[180 * mm / len(items)] * len(items))
    t.setStyle(TableStyle([("BOX", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dbe1")),
                           ("INNERGRID", (0, 0), (-1, -1), 0.3, colors.HexColor("#e3e7eb")),
                           ("TOPPADDING", (0, 0), (-1, -1), 6), ("BOTTOMPADDING", (0, 0), (-1, -1), 6)]))
    return t


def pie(m: dict, width=170, height=140, plain: bool = False) -> Drawing:
    s = m["summary"]
    if plain:
        blocked = s.get("blocked", 0)
        product_fail = max(0, s["failed"] + s["broken"] - blocked)
        named = [("Working", s["passed"], "passed"), ("Needs attention", product_fail, "failed"),
                 ("Could not run", blocked, "broken"), ("Not checked", s["skipped"], "skipped")]
    else:
        named = [(k.title(), s[k], k) for k in ("passed", "failed", "broken", "skipped")]
    pairs = [(color, v, label) for label, v, color in named if v]
    d = Drawing(width, height)
    if not pairs:
        d.add(String(width / 2, height / 2, "No tests", textAnchor="middle"))
        return d
    p = Pie()
    p.x, p.y, p.width, p.height = 10, 10, 115, 115
    p.data = [v for _, v, _ in pairs]
    p.labels = [f"{label} {v}" for _, v, label in pairs]
    p.simpleLabels = 0
    p.sideLabels = 1
    p.slices.strokeWidth = 0.5
    p.slices.strokeColor = colors.white
    for i, (k, _, _) in enumerate(pairs):
        p.slices[i].fillColor = C[k]
        p.slices[i].fontSize = 7.5
    d.add(p)
    return d


def severity_bars(m: dict, width=250, height=140) -> Drawing:
    d = Drawing(width, height)
    bc = VerticalBarChart()
    bc.x, bc.y, bc.width, bc.height = 30, 25, width - 45, height - 40
    bc.data = [[m["by_severity"].get(k, 0) for k in SEVERITY_ORDER]]
    bc.categoryAxis.categoryNames = SEVERITY_ORDER
    bc.categoryAxis.labels.fontSize = 7.5
    bc.valueAxis.valueMin = 0
    bc.valueAxis.valueMax = max(bc.data[0] + [1]) + 1
    bc.valueAxis.valueStep = max(1, bc.valueAxis.valueMax // 4)
    bc.valueAxis.labels.fontSize = 7
    bc.bars.strokeWidth = 0
    for i, k in enumerate(SEVERITY_ORDER):
        bc.bars[(0, i)].fillColor = C[k]
    d.add(bc)
    d.add(String(width / 2, height - 10, "Issues by severity", textAnchor="middle", fontSize=8.5))
    return d


def _header(m: dict, title: str) -> list:
    return [P(title, "h1"),
            P(f"{m['project_name']}  |  Environment: {m['environment']}  |  Build: {m['build_version']}  |  "
              f"Run: {m['started_at']} ({m['duration_s']} s)  |  Run ID: {m['run_id']}", "small"),
            Spacer(1, 8)]


def _on_page(canvas, doc):
    canvas.saveState()
    canvas.setFont("Helvetica", 7)
    canvas.setFillColor(colors.HexColor("#5b6773"))
    canvas.drawString(15 * mm, 8 * mm, doc.title)
    canvas.drawRightString(A4[0] - 15 * mm, 8 * mm, f"Page {doc.page}")
    canvas.restoreState()


def _build(path: Path, title: str, story: list) -> Path:
    doc = SimpleDocTemplate(str(path), pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm,
                            topMargin=14 * mm, bottomMargin=14 * mm, title=title)
    doc.build(story, onFirstPage=_on_page, onLaterPages=_on_page)
    return path


def _feature_table(m):
    rows = [["Feature / module", "Total", "Passed", "Failed", "Could not run", "Overview"]]
    for f in m.get("features") or []:
        rows.append([f["feature"], str(f["total"]), str(f["passed"]), str(f["product_fail"]),
                     str(f["blocked"]), f["summary"]])
    if len(rows) == 1:
        return P("No features in this run.")
    return table(rows, [36 * mm, 14 * mm, 16 * mm, 16 * mm, 26 * mm, 72 * mm])


def _summary_kpis(m):
    s = m["summary"]
    return kpis([("Tests", s["total"], None), ("Passed", s["passed"], C["passed"]), ("Failed", s["failed"], C["failed"]),
                 ("Could not run", s.get("blocked", 0), C["broken"]), ("Skipped", s["skipped"], C["skipped"]),
                 ("Pass rate", f"{s['pass_rate']}%", None)])


def _failure_table(m, fix_col=False):
    if not m["failures"]:
        return P("No product defects in the checks that completed.")
    head = ["#", "Severity", "Endpoint", "Issue", "Test case"] + (["Suggested fix"] if fix_col else ["Expected", "Actual"])
    rows = [head]
    for i, f in enumerate(m["failures"], 1):
        tail = [f["ai_fix"] or f["fix"]] if fix_col else [" / ".join(map(str, f["expected_status"])) or "-",
                                                             str(f["actual_status"] or "no response")]
        rows.append([str(i), f["severity"], f["endpoint"], f"{f['title']}\n{f['summary']}", f["case_id"]] + tail)
    widths = [7 * mm, 16 * mm, 34 * mm, 45 * mm, 22 * mm] + ([56 * mm] if fix_col else [28 * mm, 28 * mm])
    return table(rows, widths)


def _endpoint_table(m, plain=False):
    if plain:
        names = {"Healthy": "Working", "Partly tested": "Partly checked", "At risk": "Minor problems",
                 "Failing": "Needs attention", "Not tested": "Not checked", "Blocked": "Could not check"}
        rows = [["Part of the system", "Status", "Checks working"]]
        rows += [[f"{e['feature'] or e['endpoint']} ({e['endpoint']})", names[e["health"]], f"{e['passed']} of {e['total']}"]
                 for e in m["endpoints"]]
        return table(rows, [110 * mm, 35 * mm, 35 * mm])
    rows = [["Endpoint", "Health", "Passed", "Failed", "Broken", "Skipped", "Status codes"]]
    rows += [[e["endpoint"] + (f"\nAI: {e['ai_note']}" if e["ai_note"] else ""), e["health"], str(e["passed"]),
              str(e["failed"]), str(e["broken"]), str(e["skipped"]), ", ".join(map(str, e["status_codes"]))]
             for e in m["endpoints"]]
    return table(rows, [62 * mm, 20 * mm, 14 * mm, 14 * mm, 14 * mm, 15 * mm, 41 * mm])


def _skip_table(m):
    rows = [["Reason", "Checks", "Endpoints affected"]]
    rows += [[s["reason"], str(s["count"]), ", ".join(s["endpoints"])] for s in m["skip_reasons"]]
    return table(rows, [70 * mm, 16 * mm, 94 * mm])


def _coverage_banner(m):
    if m["recommendation"]["color"] not in ("review", "grey"):
        return []
    s = m["summary"]
    if s.get("blocked"):
        reason = m.get("blocker_reasons", [{}])[0].get("reason", "environment / setup")
        return [P(f"{s['blocked']} of {s['total']} checks could not run ({s.get('completed_pct', s['executed_pct'])}% completed). "
                  f"Main reason: {reason}", "fix"), Spacer(1, 4)]
    return [P(f"{s['skipped']} of {s['total']} checks were not executed ({s['executed_pct']}% executed). "
              f"Main reason: {m['skip_reasons'][0]['reason'] if m['skip_reasons'] else '-'}", "fix"), Spacer(1, 4)]


def dev_pdf(m: dict, out: Path) -> Path:
    story = _header(m, m["dev_title"]) + _coverage_banner(m)
    story += [_summary_kpis(m), Spacer(1, 6), P("Features and modules", "h2"), _feature_table(m),
              Spacer(1, 4), P(f"Base URL: {m['base_url']}  |  Python {m['python']}", "small")]
    if m["ai_summary"]:
        story += [Spacer(1, 4), P(f"AI analysis: {m['ai_summary']}", "fix")]
    if m.get("blocked_tests"):
        story += [P(f"Could not run ({len(m['blocked_tests'])})", "h2"),
                  P("These checks never reached the intended assertion. They are environment or setup blockers, not product defects.", "small"),
                  table([["Problem", "Checks", "Endpoints"]] +
                        [[r["reason"], str(r["count"]), ", ".join(r["endpoints"])] for r in m.get("blocker_reasons", [])],
                        [70 * mm, 16 * mm, 94 * mm]),
                  table([["#", "Test case", "Endpoint", "Reason"]] +
                        [[str(i), t["case_id"], t["endpoint"],
                          (t.get("title") or t.get("actual_result") or t.get("skip_reason") or "Could not run")[:120]]
                         for i, t in enumerate(m["blocked_tests"], 1)],
                        [10 * mm, 28 * mm, 32 * mm, 110 * mm])]
    story += [P(f"Product defect summary ({len(m['failures'])})", "h2"), _failure_table(m)]
    if m["skip_reasons"]:
        story += [P("Not executed - reasons", "h2"), _skip_table(m)]
    story += [
              P("Per-endpoint results", "h2"), _endpoint_table(m)]

    if m["failures"]:
        story += [PageBreak(), P("Failure details, fix suggestions and evidence", "h2")]
    for f in m["failures"]:
        block = [P(f"[{f['severity']}] {f['case_id']} - {f['endpoint']} - {f['title']}", "h3"),
                 P(f"Scenario: {f['summary']}\nExpected: {f['expected_result']}\nActual: {f['error']}", "cell"),
                 Spacer(1, 3), P(f"Suggested fix (rule-based): {f['fix']}", "fix")]
        if f.get("ai_fix"):
            block.append(P(f"AI suggestion: {f['ai_fix']}", "fix"))
        story.append(KeepTogether(block))
        for c in f["calls"]:
            story += [P(f"{c['method']} {c['url']} -> {c.get('status') or c.get('error')} ({c.get('duration_ms')} ms) "
                        f"[{c.get('client')}]", "small"), code(c["curl"])]
            if c.get("request_body"):
                story += [P("Request body", "small"), code(c["request_body"])]
            if c.get("response_headers"):
                story += [P("Response headers", "small"), code(json.dumps(c["response_headers"], indent=1))]
            if c.get("response_body"):
                story += [P("Response body", "small"), code(c["response_body"])]
        story += [P("Traceback", "small"), code(f["traceback"], max_lines=40), P("Test log", "small"), code(f["log"])]

    story += [PageBreak(), P("All tests with complete logs", "h2")]
    for t in m["tests"]:
        story.append(P(f"[{t['status_label']}] {t['case_id']} - {t['endpoint']} - {t['summary']} ({t['duration_ms']} ms)", "h3"))
        if t["skip_reason"]:
            story.append(P(f"Skipped: {t['skip_reason']}", "cell"))
        if t["log"]:
            story.append(code(t["log"], max_lines=60))
    return _build(out, f"Developer Report - {m['project_name']}", story)


def qa_lead_pdf(m: dict, out: Path) -> Path:
    rec = m["recommendation"]
    story = _header(m, m["lead_title"])
    verdict = Table([[Paragraph(f'<font color="white"><b>{escape(rec["verdict"])}</b></font>', S["body"]),
                      P(rec["reason"])]], colWidths=[40 * mm, 140 * mm])
    verdict.setStyle(TableStyle([("BACKGROUND", (0, 0), (0, 0), C[rec["color"]]), ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                                 ("BOX", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dbe1"))]))
    s = m["summary"]
    story += [verdict, Spacer(1, 6)] + _coverage_banner(m)
    if m["ai_summary"]:
        story += [P(f"Analyst summary: {m['ai_summary']}", "fix")]
    story += [kpis([("Automated tests", s["total"], None),
                    ("Completed", f"{s.get('completed_pct', s['executed_pct'])}%",
                     C["review"] if s.get("completed_pct", s["executed_pct"]) < m["min_executed_pct"] else None),
                    ("Pass rate", f"{s['pass_rate']}%", None), ("Product defects", len(m["failures"]), C["failed"]),
                    ("Could not run", s.get("blocked", 0), C["broken"]),
                    ("Manual", len(m["manual_cases"]), None)]), Spacer(1, 6),
              P("Features and modules", "h2"), _feature_table(m), Spacer(1, 6)]
    charts_row = Table([[pie(m), severity_bars(m)]], colWidths=[80 * mm, 100 * mm])
    story += [charts_row]

    bad = [e["endpoint"] for e in m["endpoints"] if e["health"] == "Failing"]
    findings = [f"{s.get('completed', s['executed'])} of {s['total']} automated checks completed on {m['environment']} "
                f"({s.get('completed_pct', s['executed_pct'])}%); {s['passed']} passed ({s['pass_rate']}% of completed).",
                *[f"Could not run: {r['count']} check(s) across {len(r['endpoints'])} endpoint(s) - {r['reason']}."
                  for r in m.get("blocker_reasons", [])],
                *[f"Not executed: {r['count']} check(s) across {len(r['endpoints'])} endpoint(s) - {r['reason']}."
                  for r in m["skip_reasons"]],
                f"{len(m['failures'])} product defect(s): " + ", ".join(f"{m['by_severity'].get(k, 0)} {k}" for k in SEVERITY_ORDER) + ".",
                *[f"{cat}: {n} occurrence(s)." for cat, n in m["by_category"].most_common(5)],
                f"Endpoints failing: {', '.join(bad) if bad else 'none'}."]
    story += [P("Key findings for the CTO email", "h2")] + [P(f"- {x}") for x in findings]
    story += [P("A ready-to-send draft is in CTO_Email_Draft.html / .txt in this run folder.", "small")]
    story += [P("Failure summary", "h2"), _failure_table(m, fix_col=True), P("Endpoint health", "h2"), _endpoint_table(m)]
    story += [P("Coverage by test type", "h2"),
              table([["Test type", "Tests"]] + [[k, str(v)] for k, v in m["by_type"].most_common()], [120 * mm, 60 * mm])]
    if m["skip_reasons"]:
        story += [P("Not executed - reasons", "h2"), _skip_table(m)]
    if m["skipped"]:
        story += [P("Not executed - individual checks", "h2"),
                  table([["Test case", "Endpoint", "Reason"]] + [[t["case_id"], t["endpoint"], t["skip_reason"]]
                                                                 for t in m["skipped"]], [30 * mm, 50 * mm, 100 * mm])]
    if m["manual_cases"]:
        story += [P("Manual / not automated test cases", "h2"),
                  table([["ID", "Endpoint", "Summary", "Reason"]] +
                        [[c["id"], c.get("endpoint", ""), c.get("summary", ""), c.get("manual_reason") or "Not automated"]
                         for c in m["manual_cases"]], [25 * mm, 40 * mm, 70 * mm, 45 * mm])]
    story += [P("Run details", "h2"),
              table([["Environment", f"{m['environment']} ({m['base_url']})"], ["Build", m["build_version"]],
                     ["Started / finished", f"{m['started_at']} / {m['finished_at']} ({m['duration_s']} s)"],
                     ["Executed by", m["assignee"] or "-"]], [45 * mm, 135 * mm], header=False)]
    return _build(out, f"QA Lead Report - {m['project_name']}", story)


def stakeholder_pdf(m: dict, out: Path) -> Path:
    rec = m["recommendation"]
    label = STAKEHOLDER_LABEL.get(rec["color"], "Ready with some risks")
    s = m["summary"]
    story = _header(m, m["stakeholder_title"])
    l = m["latest"]
    story += [P(f"This summary combines all {m['runs_count']} test run(s) from {m['first_run_at']} to {l['started_at']}. "
                "Each check shows its most recent result.", "small"), Spacer(1, 4)]
    if rec["color"] in ("review", "grey"):
        story += [P(f"Many checks have not been run yet. Across {m['runs_count']} run(s), {s['executed']} of {s['total']} "
                    "checks have been run at least once, so these results do not show the full picture yet.", "fix"),
                  Spacer(1, 4)]
    status = Table([[Paragraph(f'<font color="white" size="14"><b>{label}</b></font>', S["body"]), P(plain_verdict(m))]],
                   colWidths=[50 * mm, 130 * mm])
    status.setStyle(TableStyle([("BACKGROUND", (0, 0), (0, 0), C[rec["color"]]), ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                                ("TOPPADDING", (0, 0), (-1, -1), 8), ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
                                ("BOX", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dbe1"))]))
    story += [status, Spacer(1, 8),
              kpis([("Features covered", len(m["endpoints"]), None), ("Checks working", s["passed"], C["passed"]),
                    ("Need attention", len(m["failures"]), C["failed"]),
                    ("Could not run", s.get("blocked", 0), C["broken"]),
                    ("Runs included", m["runs_count"], None)]),
              Spacer(1, 6), P("Features and modules", "h2"), _feature_table(m), Spacer(1, 4),
              Spacer(1, 6), Table([[pie(m, plain=True)]], colWidths=[180 * mm]),
              P(f"{s['passed']} of {s['total']} checks working; {s.get('blocked', 0)} could not run; {s['skipped']} not run yet.", "body"),
              P("Latest run", "h2"),
              P(f"{l['started_at']} on {l['environment']} (build {l['build_version']}) - {l['verdict']}. "
                f"{l.get('completed', l['executed'])} of {l['total']} checks completed: {l['passed']} working, "
                f"{l.get('blocked', 0)} could not run, {l['skipped']} not run."),
              P("What needs attention", "h2")]
    issues = plain_issues(m)
    if issues:
        names = {"Critical": "Urgent", "High": "High", "Medium": "Medium", "Low": "Low"}
        story += [table([["Priority", "What we found", "Where", "Checks"]] +
                        [[names[i["severity"]], i["plain"], i["where"], str(i["count"])] for i in issues],
                        [22 * mm, 95 * mm, 45 * mm, 18 * mm]),
                  P("The development team has a separate technical report with full details and suggested fixes.", "small")]
    else:
        story.append(P("Nothing needs attention. Everything that was checked is working."))
    story += [P("Progress over time", "h2"),
              table([["When", "Environment", "Result", "Checks completed", "Working", "Could not run"]] +
                    [[r["started_at"], r["environment"], r["verdict"],
                      f"{r.get('completed', r['executed'])} of {r['total']}", str(r["passed"]),
                      str(r.get("blocked", 0))] for r in m["history"]],
                    [36 * mm, 30 * mm, 34 * mm, 32 * mm, 22 * mm, 26 * mm])]
    names = {"Healthy": "Working", "Partly tested": "Partly checked", "At risk": "Minor problems",
             "Failing": "Needs attention", "Not tested": "Not checked yet", "Blocked": "Could not check"}
    story += [P("How each part of the system is doing", "h2"),
              table([["Part of the system", "Status", "Checks working", "Last checked"]] +
                    [[f"{e['feature'] or e['endpoint']} ({e['endpoint']})", names[e["health"]], f"{e['passed']} of {e['total']}",
                      e.get("last_checked") or "Not yet"] for e in m["endpoints"]],
                    [90 * mm, 30 * mm, 26 * mm, 34 * mm])]
    return _build(out, f"{m['stakeholder_title']} - {m['project_name']}", story)


def qa_pdf(m: dict, out: Path) -> Path:
    rec, s = m["recommendation"], m["summary"]
    story = _header(m, m["qa_title"]) + _coverage_banner(m)
    verdict = Table([[Paragraph(f'<font color="white"><b>{escape(rec["verdict"])}</b></font>', S["body"]),
                      P(rec["reason"])]], colWidths=[40 * mm, 140 * mm])
    verdict.setStyle(TableStyle([("BACKGROUND", (0, 0), (0, 0), C[rec["color"]]), ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                                 ("BOX", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dbe1"))]))
    blockers = qa_points.blockers(m)
    story += [verdict, Spacer(1, 6),
              kpis([("Automated", s["total"], None),
                    ("Completed", f"{s.get('completed_pct', s['executed_pct'])}%",
                     C["review"] if s.get("completed_pct", s["executed_pct"]) < m["min_executed_pct"] else C["passed"]),
                    ("Pass rate", f"{s['pass_rate']}%", None), ("Product defects", len(m["failures"]), C["failed"]),
                    ("Could not run", s.get("blocked", 0), C["broken"]), ("Planned", len(m["planned_cases"]), None)]),
              Spacer(1, 6), P("Features and modules", "h2"), _feature_table(m),
              Spacer(1, 6), Table([[pie(m), severity_bars(m)]], colWidths=[80 * mm, 100 * mm]),
              table([["Environment", f"{m['environment']} ({m['base_url']})", "Build", m["build_version"]],
                     ["Run", m["run_id"], "Started / duration", f"{m['started_at']} / {m['duration_s']} s"]],
                    [28 * mm, 70 * mm, 32 * mm, 50 * mm], header=False)]
    story += [P("Talking points (ready to share)", "h2")] + [P(f"{i}. {t}") for i, t in enumerate(qa_points.talking_points(m), 1)]
    story += [P("Blockers and actions for QA", "h2"),
              table([["Priority", "Type", "What", "Action"]] + [[b["severity"], b["kind"], b["text"], b["action"]] for b in blockers],
                    [18 * mm, 24 * mm, 70 * mm, 68 * mm]) if blockers else P("No blockers.")]
    story += [P("If someone asks", "h2")]
    for q, a in qa_points.faq(m):
        story += [P(q, "h3"), P(a)]
    if m.get("blocked_tests"):
        story += [P("Could not run", "h2"),
                  table([["Problem", "Checks", "Endpoints"]] +
                        [[r["reason"], str(r["count"]), ", ".join(r["endpoints"])] for r in m.get("blocker_reasons", [])],
                        [70 * mm, 16 * mm, 94 * mm]),
                  table([["#", "Test case", "Endpoint", "Reason"]] +
                        [[str(i), t["case_id"], t["endpoint"],
                          (t.get("title") or t.get("actual_result") or t.get("skip_reason") or "Could not run")[:120]]
                         for i, t in enumerate(m["blocked_tests"], 1)],
                        [10 * mm, 28 * mm, 32 * mm, 110 * mm]),
                  P("These are environment / setup / not-automated blockers, not product defects. Do not file them as Jira bugs.", "small")]
    story += [P("Product defects found", "h2")]
    if m["failures"]:
        rows = [["Bug", "Severity", "Test case", "Endpoint", "Issue", "Suggested fix"]]
        rows += [[f["bug_ref"], f["severity"], f["case_id"], f["endpoint"], f"{f['title']}\n{f['summary']}", f["ai_fix"] or f["fix"]]
                 for f in m["failures"]]
        story.append(table(rows, [16 * mm, 16 * mm, 20 * mm, 32 * mm, 40 * mm, 56 * mm]))
    else:
        story.append(P("No product defects in the checks that completed."))
    if m["skip_reasons"]:
        story += [P("Not executed - reasons", "h2"), _skip_table(m)]
    if m["open_questions"]:
        story += [P("Open questions (documentation gaps)", "h2"),
                  table([["#", "Question", "Impact", "Answer"]] +
                        [[str(i), q.get("question", ""), q.get("impact", ""), q.get("answer") or "Pending"]
                         for i, q in enumerate(m["open_questions"], 1)], [8 * mm, 80 * mm, 52 * mm, 40 * mm])]
    cm = m["coverage_matrix"]
    if cm["rows"]:
        codes = [str(c) for c in cm["codes"]]
        w = (180 - 55) / max(1, len(codes))
        story += [P("Status-code coverage matrix", "h2"),
                  table([["Endpoint"] + codes] + [[r["endpoint"]] + [r["cells"].get(c, "") for c in cm["codes"]] for r in cm["rows"]],
                        [55 * mm] + [w * mm] * len(codes))]
    story += [P("Endpoint health", "h2"), _endpoint_table(m)]
    if m["slowest"]:
        story += [P("Slowest executed tests", "h2"),
                  table([["Test case", "Endpoint", "Status", "ms"]] + [[t["case_id"], t["endpoint"], t["status_label"], str(t["duration_ms"])]
                                                                      for t in m["slowest"]], [30 * mm, 90 * mm, 30 * mm, 30 * mm])]
    story += [P("Traceability (case - result - bug)", "h2"),
              table([["Test case", "Endpoint", "Type", "Priority", "Result", "Bug"]] +
                    [[t["case_id"], t["endpoint"], t["test_type"], t["priority"], t["status_label"], t.get("bug_ref", "")]
                     for t in m["tests"]], [26 * mm, 62 * mm, 32 * mm, 18 * mm, 22 * mm, 20 * mm])]
    if m["manual_cases"]:
        story += [P("Manual / not automated test cases", "h2"),
                  table([["ID", "Endpoint", "Summary", "Reason"]] +
                        [[c["id"], c.get("endpoint", ""), c.get("summary", ""), c.get("manual_reason") or "Not automated"]
                         for c in m["manual_cases"]], [25 * mm, 40 * mm, 70 * mm, 45 * mm])]
    return _build(out, f"QA Report - {m['project_name']}", story)


def render_all(m: dict, out_dir: Path, optional: set | None = None) -> list[Path]:
    """Per-run PDFs: Dev + QA always; QA Lead only when requested. Stakeholder is consolidated (reporting.consolidated)."""
    out = [dev_pdf(m, out_dir / "Dev_Report.pdf"), qa_pdf(m, out_dir / "QA_Report.pdf")]
    if "qa_lead" in (optional or set()):
        out.append(qa_lead_pdf(m, out_dir / "QA_Lead_Report.pdf"))
    return out
