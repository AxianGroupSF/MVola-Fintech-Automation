# Automation context

Appium + Java + TestNG Page Object Model for MvolaAutomation. Do not redesign the framework.

Application pages and tests are not implemented yet. Add one page object and one test class at a time.

## Architecture

- Tests extend `src/main/java/com/qaframework/base/BaseTest.java` (Appium server, driver, Extent, pass/fail screenshots).
- Pages extend `src/main/java/com/qaframework/pages/base/BasePage.java` and call `MobileActions` / `WaitUtils` only.
- Driver: `src/main/java/com/qaframework/drivers/DriverManager.java`, capabilities in `CapabilityManager`.
- Config: `src/main/resources/config.properties` via `src/main/java/com/qaframework/config/ConfigReader.java`. Phone, PIN, OTP, username, password, package, activity, and APK path are empty until the new application is configured.
- Actions: `src/main/java/com/qaframework/utils/MobileActions.java` (wait, act, `ScreenshotUtils.captureStep`).
- Waits: `src/main/java/com/qaframework/utils/WaitUtils.java` (`explicitWait` from config). No `Thread.sleep`.
- Locators: `src/main/java/com/qaframework/utils/PlatformLocator.java` and `SmartLocator.java`. Accessibility id, then resource id / text. UiAutomator only when those are missing.
- Screenshots: `src/main/java/com/qaframework/utils/ScreenshotUtils.java`. Every step under `reports/images`. Extent gets failure shots and the final pass screen via `TestListener`.
- Report: `src/main/java/com/qaframework/reports/ExtentReportManager.java` and `RunReportManager.java`, listener `src/main/java/com/qaframework/listeners/TestListener.java`.
- Failures: `StepFailureException`.
- Step rules: `.cursor/rules/step-actions.mdc`.

## Folders

- Pages: `src/test/java/com/qaframework/pages/` (empty)
- Tests: `src/test/java/com/qaframework/tests/` (empty)
- Suite: `testng.xml`

## Patterns

- No application page or test exists yet.
- Keep locators in page objects.
- Read phones, PINs, OTPs, package, activity, and the APK path from `config.properties`.

## Run

```
mvn -DskipTests test-compile
mvn test
```

`mvn test` uses `testng.xml`. The suite runs the registered application tests, including `LoginTest`.
