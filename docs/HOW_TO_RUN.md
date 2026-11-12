# How to run

## Compile

From `C:\MvolaAutomation`:

```powershell
mvn -DskipTests test-compile
```

This checks the framework and does not install or launch an application.

## Framework unit tests

```powershell
mvn test
```

`testng.xml` currently runs `com.qaframework.utils.SmartLocatorTest` only. That class does not need a device or an APK.

## Application tests

After a page object and a test class exist:

1. Set `androidAppPath`, `androidAppPackage`, and `androidAppActivity` in `src/main/resources/config.properties`.
2. Put the APK in `apps/` when the package is not already installed.
3. Connect a device (`adb devices` shows `device`).
4. Add the test class to `testng.xml`.
5. Run `mvn test`, or one class with `mvn test "-Dsurefire.suiteXmlFiles=" "-Dtest=com.qaframework.tests.YourTest"`.

`run-tests.ps1` compiles the project and does not launch an application.
