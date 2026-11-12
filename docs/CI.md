# CI

No remote CI workflow is configured.

Compile check:

```powershell
mvn -DskipTests test-compile
```

`mvn test` runs `testng.xml`. Until application tests are added, that command runs `SmartLocatorTest` and does not need a device.

Do not put secrets in the pipeline. Map phone, PIN, OTP, and password onto the runner when application tests exist, and keep those values out of git.
