# AI report analysis

Read one finished run and add **2-3 line fix notes** per failed test, per area, and one overall summary. Then rebuild the reports. **Do not re-run tests.**

The rule engine already writes a generic fix for every failure (`reporter/reporting/fix_rules.py`). The notes replace that generic text with a specific one: what the evidence shows, where the fault likely sits, and what to change.

`endpoint` in `run.json` is the area under test. For an API run it is a route such as `GET /products/{id}`. For a UI run it is the screen or feature. The `endpoints` object in `ai_insights.json` uses that same string.

## Where a run lives

Any folder that contains `run.json`. Usual places:

| Runner | Run folder |
| --- | --- |
| API suite | `api-automation-tester/API_SUITES/<X> API/reports/<run_id>/` |
| Katalon cases | `create-katalon-cases/reports/<run_id>/` or `<Katalon project>/reports/<run_id>/` |
| Playwright | `<Playwright project>/reports/<run_id>/` |
| UI design validator | `ui-design-validator/reports/<run_id>/` |

Use the newest run unless the user names one.

## Evidence

| File | Use |
| --- | --- |
| `run.json` | Source of truth. Status, error, `error_kind`, log, and for API runs the HTTP `calls` |
| `cases.json` or the suite's `test_cases/cases.json` | Expected result, preconditions, source. The run folder's `cases.json` wins when both exist |
| `Dev_Report.html` | The same evidence in reading order |
| `logs/execution.log` | API suites only. Full log of that run |
| `discrepancies.md` | UI design runs only. Use it when a failure is a design mismatch |

Consider only tests whose `status` is `failed` or `broken`. Do not write notes for `passed` or `skipped`.

## What to write

Each note is **2-3 sentences**, plain ASCII punctuation, no secrets.

**API failure.** State the evidence (status, body, or error). Name the likely route, guard, validator, or handler. Say "likely" when the code was not in the run folder. Name the change.

**UI failure.** Use `error_kind`:

| `error_kind` | What the note must say |
| --- | --- |
| `assertion` | App defect. What the screen showed versus the expected result, the likely screen or component, and the product change |
| `test_error` | Script issue, not a product bug. Likely locator, page object, or keyword. Do not recommend an app change |
| `connectivity` | Environment. Device, install, profile, or session. Not a product bug |

If an API failure is a bad test, missing credential, or unreachable service, say that in the note so it is not logged as a product bug.

**Area note.** One 2-3 sentence note for every `endpoint` value that has at least one failed or broken test. Summarise the risk and the order to fix.

**Overall summary.** 2-3 sentences a QA Lead can paste to a CTO: the risk, how many critical items, and the recommended action.

## `ai_insights.json`

Save it in the run folder:

```json
{
  "generated_by": "AI analyst (model or person name)",
  "generated_at": "2026-09-27T18:50:00+05:00",
  "run_id": "2026-09-27_18-45-12_mock",
  "overall_summary": "2-3 sentences for QA Lead / CTO.",
  "endpoints": {
    "GET /products/{id}": "2-3 sentences for this area."
  },
  "tests": {
    "TC-API-031": "2-3 sentences: evidence, likely location, concrete fix."
  }
}
```

Keys in `endpoints` must match `endpoint` in `run.json` exactly. Keys in `tests` must match `case_id`.

## Rebuild

From the repo root, Python 3.10 or newer (`python` or `python3` on PATH — do not hard-code an OS-specific install path):

```text
python reporter/build.py --run "<run folder>"
```

An API suite or the UI design validator may instead run its own `scripts/build_reports.py --run reports/<run_id>`. That calls the same engine. Install `reporter/requirements.txt` first if the import fails.

Rebuild with the **same optional reports** as the previous build of this run (`--reports qa_lead` and so on only if those files already existed because they were requested). Do not add optional reports on your own.

Confirm `Dev_Report.html` shows the AI suggestion and `QA_Report.html` the analyst talking point. Confirm `QA_Lead_Report.html` only if that report was built. The reporter writes **both** `Open Reports (Mac).command` and `Open Reports (Windows).bat` (and `Open This Run` for each OS in the run folder). Double-click the pair for this machine. Do not rely on `file://`.

## Rules

- Every statement must come from the run files above. Do not invent stack traces or source files.
- Keep `***REDACTED***` masked.
- Do not change test code, cases, or statuses. If a test looks wrong, say so in the note.
- Do not create Jira issues. `Ready to Report Bugs.md` picks up the notes as the suggested fix after the rebuild (`/log-defects` after an explicit yes).
