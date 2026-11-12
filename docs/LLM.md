# Standing brief

This repository is MvolaAutomation: Appium, Java, TestNG, and Maven for one Android application.

Read `AGENTS.md`, `CLAUDE.md`, and `.cursor/automation-context.md` before changing the framework.

## Rules

1. Do not invent package names, activities, APK paths, locators, phones, PINs, OTPs, or passwords.
2. Keep secrets out of git. Names live in `config.properties`. Values stay empty until someone fills them locally.
3. Add application coverage as one page object and one test class at a time.
4. Keep locators in page objects. Tests call page objects.
5. Reuse `BaseTest`, `BasePage`, `DriverManager`, `SmartLocator`, `MobileActions`, and `WaitUtils`.
6. Do not install or launch an application unless the requested testcase is being executed and `config.properties` has the real package, activity, and APK path.
