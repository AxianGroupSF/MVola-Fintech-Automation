"""Writes the test case workbook ('Test cases for X API.xlsx') and the executed copy for a run."""
from __future__ import annotations

from pathlib import Path

from openpyxl import Workbook
from openpyxl.cell.cell import ILLEGAL_CHARACTERS_RE
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

COLUMNS = ["ID", "UseCase/Feature", "Test Case Summary", "Preconditions", "Input", "Expected Result",
           "Actual Result", "Test Type", "Status", "Priority", "Environment", "Build version", "Assignee",
           "Source / Traceability", "Automation", "Defect ID", "Execution Date"]
WIDTHS = [14, 20, 45, 35, 45, 45, 40, 18, 14, 10, 16, 16, 18, 35, 12, 12, 16]
STATUS_FILL = {"Passed": "C6EFCE", "Failed": "FFC7CE", "Blocked": "FFEB9C", "Not Executed": "EDEDED"}


def _excel_safe(value):
    """Strip control chars (e.g. ANSI color codes in Playwright errors) that Excel rejects."""
    if isinstance(value, str):
        return ILLEGAL_CHARACTERS_RE.sub("", value)
    return value


def _style_sheet(ws, widths):
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    for cell in ws[1]:
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor="1F4E79")
        cell.alignment = Alignment(wrap_text=True, vertical="center")
    for row in ws.iter_rows(min_row=2):
        for cell in row:
            cell.alignment = Alignment(wrap_text=True, vertical="top")
    ws.freeze_panes = "A2"
    ws.auto_filter.ref = ws.dimensions


def build(case_file: dict, project: dict, environment: str, out_path: Path, results: dict | None = None,
          execution_date: str = "") -> Path:
    """results: {case_id: {"status": Passed|Failed|Blocked|Not Executed, "actual": str}} for the executed copy."""
    wb = Workbook()
    ui = (project.get("domain") or "").lower() == "ui"
    ws = wb.active
    ws.title = "UI Test Cases" if ui else "API Test Cases"
    ws.append(COLUMNS)
    for c in case_file.get("cases", []):
        r = (results or {}).get(c["id"], {})
        ws.append([_excel_safe(v) for v in [
            c["id"], c.get("feature", ""), c.get("summary", ""), c.get("preconditions", ""), c.get("input", ""),
            c.get("expected_result", ""), r.get("actual", ""), c.get("test_type", "API"),
            r.get("status", "Not Executed"), c.get("priority", "Medium"), environment,
            project.get("build_version", ""), project.get("assignee", ""), c.get("source", ""),
            c.get("automation", "Automated"), "", execution_date if r else "",
        ]])
        fill = STATUS_FILL.get(r.get("status", "Not Executed"))
        if results is not None and fill:
            ws.cell(row=ws.max_row, column=9).fill = PatternFill("solid", fgColor=fill)
    _style_sheet(ws, WIDTHS)

    cov = wb.create_sheet("Screen coverage" if ui else "Endpoint Coverage")
    codes = sorted({c.get("expected_status") for c in case_file.get("cases", []) if c.get("expected_status")})
    cov.append(["Endpoint", "Total cases"] + [str(x) for x in codes])
    endpoints: dict[str, dict] = {}
    for c in case_file.get("cases", []):
        row = endpoints.setdefault(c.get("endpoint", "Unmapped"), {"total": 0})
        row["total"] += 1
        if c.get("expected_status"):
            row[c["expected_status"]] = row.get(c["expected_status"], 0) + 1
    for ep, row in endpoints.items():
        cov.append([ep, row["total"]] + [row.get(x, "") for x in codes])
    _style_sheet(cov, [40, 12] + [8] * len(codes))

    questions = case_file.get("open_questions", [])
    if questions:
        qs = wb.create_sheet("Open Questions")
        qs.append(["#", "Question / gap in the documentation", "Impact", "Answer"])
        for i, q in enumerate(questions, 1):
            qs.append([i, q.get("question", ""), q.get("impact", ""), q.get("answer", "")])
        _style_sheet(qs, [5, 70, 40, 40])

    out_path.parent.mkdir(parents=True, exist_ok=True)
    wb.save(out_path)
    return out_path
