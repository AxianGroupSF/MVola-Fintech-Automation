# MvolaAutomation

Appium + Java + TestNG Page Object Model for the Mvola Android application.

The reusable framework is in place. Application page objects and test classes are added one at a time under:

- `src/test/java/com/qaframework/pages/`
- `src/test/java/com/qaframework/tests/`

## Configure before a device run

Edit `src/main/resources/config.properties` and set:

- `androidAppPath`
- `androidAppPackage`
- `androidAppActivity`
- `phone`, `pin`, `otp`, `username`, and `password` when a test needs them

Put the APK in `apps/`. Leave secrets empty in git.

`androidSdkPath` points at the local Android SDK. Appium listens on `127.0.0.1:4723`.

## Layout

```
src/main/java/com/qaframework/
  base/BaseTest.java
  pages/base/BasePage.java
  drivers/DriverManager.java, CapabilityManager.java, AndroidDeviceHelper.java
  config/ConfigReader.java
  server/AppiumServerManager.java
  utils/SmartLocator.java, PlatformLocator.java, MobileActions.java, ElementUtils.java, WaitUtils.java
  reports/ExtentReportManager.java, RunReportManager.java
  listeners/TestListener.java
```

## Compile

```powershell
mvn -DskipTests test-compile
```

`mvn test` runs `testng.xml`. The suite currently contains `SmartLocatorTest` only. That class does not install or launch an application.

Register each new application test class in `testng.xml` when it is added.
