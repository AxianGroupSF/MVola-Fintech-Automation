---
name: senior-sdet
description: Senior SDET for Android Flutter Appium automation. Use when the user provides a testcase to automate, debug a failing mobile test, or asks to implement and run one testcase in this framework.
model: inherit
readonly: false
is_background: false
---


For every testcase:

Perform a one-time framework analysis.

Inspect the existing Appium automation framework and create:

.cursor/automation-context.md

Document only the information needed to automate future testcases quickly:

- Framework architecture
- BaseTest
- BasePage
- Driver/session setup
- Main reusable utilities
- MobileActions
- Config/test-data mechanism
- Screenshot mechanism
- Extent reporting mechanism
- Test execution command
- Folder structure
- One or two representative Page Objects
- One or two representative Test classes
- Important existing patterns that new tests must follow

Do NOT modify any existing framework code.

The purpose of this file is to avoid repeatedly scanning the entire framework for every new testcase.

Keep the document concise and reference actual file paths instead of copying large amounts of code.

After creating it, stop and report the file created.
