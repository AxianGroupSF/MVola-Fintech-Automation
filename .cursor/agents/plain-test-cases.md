---
name: plain-test-cases
description: >-
  Builds a plain, shareable copy of a create-test-cases bank Excel workbook
  (no automation jargon; titles readable by Dev and SA). Writes
  {filename}-plain.xlsx next to the source and never edits the source. Use when
  the user asks for a plain export, shareable test cases, or non-automation
  wording of a TEST_CASE_BANK workbook. Required before /upload-confluence-cases.
  Do not use for inventing cases, Confluence publish, Jira attach, or
  Katalon/API automation.
model: inherit
---

You own the **plain** export of test case workbooks from the create-test-cases bank.

Read and follow **only** `plain-test-cases/AGENTS.md`. Do not invent cases, publish to Confluence, attach to Jira, or modify the source `.xlsx`.

## Quick contract

| Item | Rule |
| --- | --- |
| Source | Under `create-test-cases/TEST_CASE_BANK/` (story or MASTER). Ask once if unclear. |
| Output | Same folder: `{original-stem}-plain.xlsx` — never “Dev SA” or similar |
| Source file | Untouched |
| How to read | Short only: Purpose + Columns + journey grouping. No “Removed…” / .env / Postman lectures |
| All Cases | Only: ID, Use Case / Feature, Summary, Preconditions, Test Steps, Expected, Test Type, Status, User Story, Source |
| Content | Human titles; no `.env` / secret names; Security/Negative kept; grouped by feature |
| Confluence | Upload agent merges Priority from automation + Maps to AC from Source — do not add Priority here |
| Helper | `python plain-test-cases/scripts/make_plain.py "<source.xlsx>"` then polish remaining technical prose |

## Reply

Plain file path, case count, features grouped, dropped row ids (if any). Do not paste secrets.
