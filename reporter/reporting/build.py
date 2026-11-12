"""Single entry point of the reporting engine: run folder in, reports out.

Mandatory for every run: Dev report, QA report, Ready to Report Bugs (+ index.html, executed workbook, Open Reports launchers).
Optional, only when requested (`optional=` or project.json "reports.optional"): allure, stakeholder, qa_lead, cto_email.
The Stakeholder report is consolidated across every run in the run folder's parent (the suite's reports/ folder).
"""
from __future__ import annotations

import shutil
from pathlib import Path

from reporting import allure, bugs, consolidated, html_reports, model, openers, options, pdf_reports, workbook

RESULT_STATUS = {"passed": "Passed", "failed": "Failed", "broken": "Blocked", "skipped": "Not Executed"}
OPTIONAL_FILES = {
    "qa_lead": ("QA_Lead_Report.html", "QA_Lead_Report.pdf"),
    "cto_email": ("CTO_Email_Draft.html", "CTO_Email_Draft.txt"),
}
LEGACY_PER_RUN = ("Stakeholder_Report.html", "Stakeholder_Report.pdf")


def _remove_stale(run_dir: Path, wanted: set[str]) -> None:
    for key, files in OPTIONAL_FILES.items():
        if key not in wanted:
            for name in files:
                (run_dir / name).unlink(missing_ok=True)
    for name in LEGACY_PER_RUN:
        (run_dir / name).unlink(missing_ok=True)
    if "allure" not in wanted:
        shutil.rmtree(run_dir / "allure-report", ignore_errors=True)
    if "stakeholder" not in wanted:
        for name in consolidated.STAKEHOLDER_FILES:
            (run_dir.parent / name).unlink(missing_ok=True)


def build_all(run_dir: Path, project: dict | None = None, case_file: dict | None = None,
              optional=None, tools_dir: Path | None = None, say=print) -> dict:
    """Reads <run_dir>/run.json (+ optional ai_insights.json) and writes the reports into run_dir.
    optional: None (use project.json reports.optional), 'all', 'none', 'stakeholder,qa_lead', or a list.
    Returns the report model; m["optional_built"] lists the optional reports produced."""
    project, case_file = project or {}, case_file or {"cases": []}
    wanted = options.resolve(optional, project)
    m = model.build(run_dir, project, case_file)

    _remove_stale(run_dir, wanted)
    html_reports.render_all(m, run_dir, project, wanted)
    pdf_reports.render_all(m, run_dir, wanted)
    bugs.write(m, run_dir)
    if case_file.get("cases"):
        results = {t["case_id"]: {"status": RESULT_STATUS.get(t["status"], t["status"]), "actual": t["actual_result"]}
                   for t in m["tests"]}
        name = f"Test cases for {m['project_name']} - Executed {m['run_id']}.xlsx"
        workbook.build(case_file, project, m["environment"], run_dir / name, results=results,
                       execution_date=m["started_at"])

    built = sorted(wanted & {"qa_lead", "cto_email"})
    if "allure" in wanted and allure.generate(run_dir, tools_dir or run_dir.parent.parent / ".tools", say):
        built.append("allure")
    if "stakeholder" in wanted and consolidated.write(run_dir.parent, project, case_file):
        built.append("stakeholder")
    html_reports.render_index(m, run_dir, (run_dir / "allure-report" / "index.html").exists())
    openers.write_openers(run_dir)
    m["optional_built"] = sorted(built)
    return m


def build_stakeholder(reports_root: Path, project: dict | None = None, case_file: dict | None = None,
                      env: str | None = None) -> list[Path]:
    """Only the consolidated Stakeholder report, across every run folder under reports_root."""
    return consolidated.write(reports_root, project, case_file, env)
