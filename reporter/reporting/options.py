"""Which reports are built.

Mandatory for every run: Dev report, QA report, Ready to Report Bugs (plus index.html and the executed workbook).
Optional, only when explicitly requested (CLI --reports or project.json "reports.optional"):
  allure, stakeholder (consolidated across all runs), qa_lead, cto_email.
"""
from __future__ import annotations

MANDATORY = ("dev", "qa", "bugs")
OPTIONAL = ("allure", "stakeholder", "qa_lead", "cto_email")
LABELS = {"dev": "Dev report", "qa": "QA report", "bugs": "Ready to Report Bugs", "allure": "Allure dashboard",
          "stakeholder": "Stakeholder report (consolidated)", "qa_lead": "QA Lead report", "cto_email": "CTO email draft"}


def resolve(requested: str | list | None, project: dict | None = None) -> set[str]:
    """requested: CLI value ('stakeholder,qa_lead', 'all', 'none') or list. Falls back to project.json
    reports.optional when the CLI gives nothing. Returns the set of optional reports to build."""
    if requested is None:
        requested = (project or {}).get("reports", {}).get("optional", [])
    if isinstance(requested, str):
        requested = [r.strip() for r in requested.split(",") if r.strip()]
    wanted = {r.lower().replace("-", "_") for r in requested}
    if "none" in wanted:
        return set()
    if "all" in wanted:
        return set(OPTIONAL)
    unknown = wanted - set(OPTIONAL) - set(MANDATORY)
    if unknown:
        raise ValueError(f"Unknown report(s): {', '.join(sorted(unknown))}. Optional reports: {', '.join(OPTIONAL)}")
    return wanted & set(OPTIONAL)
