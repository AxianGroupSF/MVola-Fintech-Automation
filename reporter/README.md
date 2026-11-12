# Reporter (mobile + API monorepo)

This product repo keeps the reporting **engine and templates** in `api/reporting/` (synced copy of the shared reporter).

- **Build a run:** `python reporter/build.py --run reports/<run_id>`  
  or `python scripts/build_reports.py --run reports/<run_id>`
- **Open reports:** `python scripts/open_reports.py` (or the Open Reports launchers written into `reports/`)

Mobile Appium/TestNG writes `reports/<YYYY-MM-DD_HH-MM-SS>_<env>/run.json` with `"domain": "ui"`, then invokes the build. Do not edit templates under a one-off suite copy — change `api/reporting/` here.
