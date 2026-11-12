# Architecture

MvolaAutomation is an Appium 2 + Java 11 + TestNG + Maven project. Tests use the Page Object Model.

| Piece | Path |
|---|---|
| Test base | `src/main/java/com/qaframework/base/BaseTest.java` |
| Page base | `src/main/java/com/qaframework/pages/base/BasePage.java` |
| Driver | `src/main/java/com/qaframework/drivers/DriverManager.java` |
| Capabilities | `src/main/java/com/qaframework/drivers/CapabilityManager.java` |
| Config | `src/main/resources/config.properties` via `ConfigReader` |
| Locators | `SmartLocator`, `PlatformLocator` |
| Actions | `MobileActions`, `ElementUtils`, `WaitUtils` |
| Reports | `ExtentReportManager`, `RunReportManager`, `TestListener` |
| Application pages | `src/test/java/com/qaframework/pages/` |
| Application tests | `src/test/java/com/qaframework/tests/` |
| Suite | `testng.xml` |

Application pages and tests are empty. Add one page object and one test class at a time. Locators stay in page objects. Phones, PINs, OTPs, the package name, the activity, and the APK path stay in `config.properties`.
