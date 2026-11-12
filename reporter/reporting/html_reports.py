"""Renders the audience-specific HTML reports, the run index and the CTO email draft."""
from __future__ import annotations

import shutil
from collections import OrderedDict
from pathlib import Path

from jinja2 import Environment, FileSystemLoader

from reporting import attachments, charts, qa_points
from reporting.fix_rules import SEVERITY_ORDER

TEMPLATES = Path(__file__).resolve().parent / "templates"
STAKEHOLDER_LABEL = {"green": "Ready", "red": "Not ready", "grey": "Could not decide", "review": "Needs review"}


def _env() -> Environment:
    return Environment(loader=FileSystemLoader(TEMPLATES), autoescape=lambda name: bool(name and name.endswith(".html.j2")),
                       trim_blocks=True, lstrip_blocks=True)


def plain_skip_reason(reason: str) -> str:
    r = reason.lower()
    if "credential" in r or r.startswith("missing"):
        return "the access details (credentials) for the test system were not provided, so these checks could not start"
    if "production" in r:
        return "these checks never run on the live system"
    if "disabled" in r:
        return "these checks are switched off for this environment"
    return reason


def plain_issues(m: dict) -> list[dict]:
    """Non-technical rows: blockers first, then product defects, then skips."""
    groups: "OrderedDict[tuple, dict]" = OrderedDict()
    for f in list(m.get("blocked_tests") or []) + list(m["failures"]):
        feature = f["feature"] or f["endpoint"]
        key = (f["plain"], feature)
        row = groups.setdefault(key, {"plain": f["plain"], "where": feature, "severity": f["severity"], "count": 0})
        row["count"] += 1
        if SEVERITY_ORDER.index(f["severity"]) < SEVERITY_ORDER.index(row["severity"]):
            row["severity"] = f["severity"]
    rows = list(groups.values())
    blocking = m["recommendation"]["color"] in ("review", "grey")
    for s in m["skip_reasons"]:
        n_areas = len(s["endpoints"])
        rows.append({"plain": f"{s['count']} check(s) did not run: {plain_skip_reason(s['reason'])}.",
                     "where": f"{n_areas} part(s) of the system", "severity": "High" if blocking else "Medium",
                     "count": s["count"]})
    return sorted(rows, key=lambda r: SEVERITY_ORDER.index(r["severity"]))


def plain_verdict(m: dict) -> str:
    color = m["recommendation"]["color"]
    s = m["summary"]
    blocked = s.get("blocked", 0)
    completed = s.get("completed", s["executed"])
    top_block = (m.get("blocker_reasons") or [{}])[0].get("reason", "a setup or environment problem")
    if color == "green":
        extra = f" {s['skipped']} check(s) were not run." if s["skipped"] else ""
        return f"All {completed} checks that completed are working as expected.{extra}"
    if color == "review" and m.get("consolidated"):
        extra = f" {blocked} latest results could not run ({top_block})." if blocked else ""
        return (f"Across all {m['runs_count']} runs, only {completed} of {s['total']} checks have a completed result, so we "
                f"cannot yet say the system is ready.{extra} The QA team needs to remove the blocker and run the remaining checks.")
    if color == "review":
        if blocked:
            return (f"{blocked} of {s['total']} checks could not run because of {top_block.lower()}. "
                    "We cannot yet say the system is ready. The QA team needs to clear the blocker and run the checks again.")
        return (f"Only {completed} of {s['total']} checks could be completed, so we cannot yet say the system is ready. "
                "The QA team needs to remove the blocker and run the checks again.")
    if color == "grey":
        return "The checks could not run properly this time, so no decision can be made yet. The QA team is re-running them."
    if color == "red":
        return ("Some serious problems were found (for example security or system errors). "
                "The development team is fixing them before release.")
    return "Most things work. A few problems were found that the team should fix or plan for."


def _chart_set(m: dict, stakeholder: bool = False) -> dict:
    s = m["summary"]
    slices = [("Passed", s["passed"], charts.COLORS["passed"]), ("Failed", s["failed"], charts.COLORS["failed"]),
              ("Broken", s["broken"], charts.COLORS["broken"]), ("Skipped", s["skipped"], charts.COLORS["skipped"])]
    if stakeholder:
        blocked = s.get("blocked", 0)
        product_fail = max(0, s["failed"] + s["broken"] - blocked)
        plain = [("Working", s["passed"], charts.COLORS["passed"]),
                 ("Needs attention", product_fail, charts.COLORS["failed"]),
                 ("Could not run", blocked, charts.COLORS["broken"]),
                 ("Not checked", s["skipped"], charts.COLORS["skipped"])]
        donut = charts.donut(plain, f"{s['passed']}/{s['total']}", "checks working")
    else:
        donut = charts.donut(slices, f"{s['pass_rate']:.0f}%", "pass rate (executed)")
    severity = charts.column_bars([(k, m["by_severity"].get(k, 0), charts.COLORS[k]) for k in SEVERITY_ORDER])
    endpoints = charts.stacked_bars([{"label": e["endpoint"], **{k: e[k] for k in ("passed", "failed", "broken", "skipped")}}
                                     for e in m["endpoints"]])
    return {"donut": donut, "severity": severity, "endpoints": endpoints}


def _context(m: dict, project: dict) -> dict:
    top = [f for f in m["failures"] if f.get("severity") in ("Critical", "High")][:8] or m["failures"][:5]
    if not top and m.get("blocked_tests"):
        top = m["blocked_tests"][:5]
    ctx = {"m": m, "plain_issues": plain_issues(m), "plain_verdict": plain_verdict(m),
           "stakeholder_label": STAKEHOLDER_LABEL.get(m["recommendation"]["color"], "Ready with some risks"),
           "top_issues": top, "cto_name": project.get("cto_name"), "qa_lead_name": project.get("qa_lead_name")}
    if not m.get("consolidated"):
        ctx.update(blockers=qa_points.blockers(m), talking_points=qa_points.talking_points(m), faq=qa_points.faq(m))
    return ctx


def prepare_run_assets(m: dict, out_dir: Path) -> None:
    """Copy screenshots/attachments into the run folder and wire feature detail links."""
    attachments.materialize(m["tests"], out_dir)
    attachments.enrich_features(m.get("features") or [], m["tests"], link=True)


def filter_counts(tests: list[dict]) -> dict:
    """Counts used by the case filter chips (blocked separate from product broken)."""
    blocked = sum(1 for t in tests if t.get("blocker"))
    product = sum(1 for t in tests if t["status"] in ("failed", "broken") and not t.get("blocker"))
    broken_product = sum(1 for t in tests if t["status"] == "broken" and not t.get("blocker"))
    return {
        "total": len(tests),
        "passed": sum(1 for t in tests if t["status"] == "passed"),
        "failed": sum(1 for t in tests if t["status"] == "failed"),
        "broken_product": broken_product,
        "skipped": sum(1 for t in tests if t["status"] == "skipped"),
        "blocked": blocked,
        "product": product,
    }


def case_column_options(tests: list[dict]) -> dict:
    """Distinct values for Priority / Type / Status column filters."""
    pri_order = {"Critical": 0, "High": 1, "Medium": 2, "Low": 3}
    priorities = sorted({(t.get("priority") or "Medium") for t in tests},
                        key=lambda p: (pri_order.get(p, 9), p))
    types = sorted({(t.get("test_type") or "Functional") for t in tests})
    statuses = sorted({(t.get("status_label") or t.get("status") or "") for t in tests})
    return {"priorities": priorities, "types": types, "statuses": statuses}


def render_feature_pages(m: dict, out_dir: Path) -> list[Path]:
    """One HTML page per feature with every case and any screenshots."""
    features_dir = out_dir / "features"
    if features_dir.exists():
        shutil.rmtree(features_dir)
    features_dir.mkdir(parents=True, exist_ok=True)
    env = _env()
    template = env.get_template("feature.html.j2")
    written: list[Path] = []
    for feature in m.get("features") or []:
        tests = feature.get("tests") or []
        slug = feature.get("slug") or attachments.feature_slug(feature["feature"])
        path = features_dir / f"{slug}.html"
        path.write_text(
            template.render(
                title=f"Feature - {feature['feature']}",
                m=m,
                feature=feature,
                filter_counts=filter_counts(tests),
                column_options=case_column_options(tests),
                asset_prefix="../",
            ),
            encoding="utf-8",
        )
        written.append(path)
    return written


def render_cases_page(m: dict, out_dir: Path, active_filter: str = "all") -> Path:
    """All cases for the run, with status filter chips and expandable details."""
    tests = m.get("tests") or []
    path = out_dir / "cases.html"
    path.write_text(
        _env().get_template("cases.html.j2").render(
            title="Test cases",
            m=m,
            filter_counts=filter_counts(tests),
            column_options=case_column_options(tests),
            active_filter=active_filter,
        ),
        encoding="utf-8",
    )
    return path


def render_all(m: dict, out_dir: Path, project: dict, optional: set | None = None) -> list[Path]:
    """Per-run HTML: Dev + QA always; QA Lead and CTO email only when requested in `optional`."""
    optional = optional or set()
    prepare_run_assets(m, out_dir)
    env = _env()
    ctx = _context(m, project)
    outputs = {"Dev_Report.html": ("dev.html.j2", m["dev_title"]), "QA_Report.html": ("qa.html.j2", m["qa_title"])}
    if "qa_lead" in optional:
        outputs["QA_Lead_Report.html"] = ("qa_lead.html.j2", m["lead_title"])
    if "cto_email" in optional:
        outputs["CTO_Email_Draft.html"] = ("cto_email.html.j2", "CTO email")
        outputs["CTO_Email_Draft.txt"] = ("cto_email.txt.j2", "CTO email")
    written = []
    for name, (template, title) in outputs.items():
        path = out_dir / name
        path.write_text(env.get_template(template).render(title=title, charts=_chart_set(m), **ctx), encoding="utf-8")
        written.append(path)
    written.append(render_cases_page(m, out_dir))
    written.extend(render_feature_pages(m, out_dir))
    return written


def render_stakeholder(m: dict, out_dir: Path, project: dict) -> Path:
    """Consolidated Stakeholder report (model from reporting.consolidated)."""
    from reporting.consolidated import trend_chart

    # Consolidated view has no per-run feature pages — keep names plain text.
    attachments.enrich_features(m.get("features") or [], m.get("tests") or [], link=False)
    charts_ = {**_chart_set(m, stakeholder=True), "trend": trend_chart(m["history"])}
    path = out_dir / "Stakeholder_Report.html"
    path.write_text(_env().get_template("stakeholder.html.j2").render(title=m["stakeholder_title"], charts=charts_,
                                                                     **_context(m, project)), encoding="utf-8")
    return path


def render_index(m: dict, out_dir: Path, has_allure: bool) -> Path:
    """Links only to reports that exist for this run (+ the consolidated Stakeholder report one level up)."""
    candidates = [
        ("../Stakeholder_Report.html", "../Stakeholder_Report.pdf", "Stakeholder report (all runs)",
         "Non-technical stakeholders", "Consolidated dashboard across every run: current status, latest run, trend.",
         "#1e9e5a", "S"),
        ("QA_Report.html", "QA_Report.pdf", "QA report", "QA team",
         "Talking points, blockers and actions, ready answers, coverage matrix, traceability.", "#0f8a8a", "QA"),
        ("Dev_Report.html", "Dev_Report.pdf", "Developer report", "DEV team",
         "Full logs, request/response evidence, curl commands and fix suggestions.", "#6b4fbb", "D"),
        ("Ready to Report Bugs.md", "ready_to_report_bugs.json", "Ready to Report Bugs", "Bug-logging agent / QA",
         "Everything needed to log Jira bugs without re-running tests.", "#d64545", "B"),
        ("QA_Lead_Report.html", "QA_Lead_Report.pdf", "QA Lead report", "QA Lead",
         "Release recommendation, dashboard, key findings and failure summary.", "#1f4e79", "L"),
        ("CTO_Email_Draft.html", "CTO_Email_Draft.txt", "CTO email draft", "QA Lead",
         "Ready-to-send email with the key figures filled in.", "#e0662a", "E"),
        ("allure-report/index.html", None, "Allure dashboard", "QA / DEV",
         "Interactive dashboard: categories, suites, timeline, attachments.", "#e0a91e", "A"),
    ]
    reports = [{"href": h, "pdf": pdf if pdf and (out_dir / pdf).exists() else None, "title": t, "who": w, "text": x,
                "color": c, "tag": tag}
               for h, pdf, t, w, x, c, tag in candidates if (out_dir / h).exists()]
    path = out_dir / "index.html"
    path.write_text(_env().get_template("index.html.j2").render(m=m, reports=reports, charts=_chart_set(m, True),
                                                               stakeholder_label=STAKEHOLDER_LABEL.get(
                                                                   m["recommendation"]["color"], "Ready with some risks")),
                    encoding="utf-8")
    return path
