# Secrets

Do not commit phones, PINs, OTPs, passwords, or device unlock PINs.

Keep these keys empty in `src/main/resources/config.properties`:

- `phone`
- `pin`
- `otp`
- `username`
- `password`
- `invalidPhone`
- `invalidPin`
- `invalidUsername`
- `invalidPassword`
- `deviceLockPin`

`config/project.json` lists the same names under `secrets` so reports can mask them. Fill values only on the machine that runs the tests.
