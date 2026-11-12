---
name: jira-qa-worklogs
description: Plan, validate, and submit approved QA worklogs for Jira stories in a sprint or issue list.
---
# Jira QA Worklog Skill

## Inputs
- `sprint`: sprint ID/name OR `issues`: list of issue keys
- `start_date`, `end_date` (inclusive)
- `timezone` (e.g. Asia/Karachi)
- `daily_target_hours`: 8 by default when the user confirms that day length
- `period_max_hours`: optional cap for the whole range
- `working_days`: Monday–Friday by default
- `activity_notes`: optional user-provided notes by date/issue

## Procedure
1. Validate dates, identity, Jira connection, project permissions, and time tracking.
2. Resolve sprint to its issues, or fetch supplied issues. Read summaries, descriptions, acceptance criteria, issue type, status, and relevant comments when available.
3. Fetch the current user's existing worklogs for the date range. Existing hours count toward that day. Do not add more on a day that is already at or above the daily target.
4. Plan every remaining working day in the range up to the confirmed daily target. A day with no Jira status change, comment, transition, or worklog is still a working day. Skip it only when it is a weekend, leave, holiday, or the user excluded it.
5. Draft varied, issue-specific QA comments from the real issue scope. Use a mix of BRD review, test design, Figma comparison, functional and negative testing, pre-prod checks, defect follow-up, clarification with a developer, and verifying a fix on a developer's machine. Do not make a meeting or a developer session the comment on every day. Do not describe a bug retest on a date before that bug existed.
6. Allocate time across the stories and bugs in the sprint. Keep each new day within the daily target. If a period maximum is set, keep the range total at or under it by shortening the new entries.
7. When the user has asked to add or post the hours, submit the batch. Otherwise present a preview: `Date | Issue | QA activity | Worklog comment | Hours | Existing hours | Daily total`, plus daily and issue totals and any cap warning.
8. Use configured Jira write actions to submit each entry with its correct date and duration. Track individual results and avoid duplicate retries.
9. Return a submission report and an exportable CSV/JSON manifest. If Jira integration lacks worklog-write support, provide the import-ready output and setup instructions.

## Output requirements
- No invented issue keys.
- Never claim success without a successful Jira API/tool response.
- Show totals by day and issue; clearly distinguish existing, submitted, and failed entries.
- State the range total against the daily target and against any period maximum.
