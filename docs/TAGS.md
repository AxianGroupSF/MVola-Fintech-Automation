# Suites

Application tests are registered in `testng.xml`.

The current suite runs `com.qaframework.utils.SmartLocatorTest`. That class checks locator resolution without a device or an application.

When an application test is added, put its class name in `testng.xml` and extend `BaseTest`. Do not put secrets in suite names or test descriptions.
