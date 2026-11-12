
# Mobile Automation Framework — Global Rules

## 1. Technology Stack
- Language: Java
- Automation: Appium 2
- Test Framework: TestNG
- Build Tool: Maven
- Platforms: Android and iOS
- Application: Flutter (Release Build)
- Architecture: Page Object Model (POM)

## 2. Framework Architecture Rules
- Analyze existing code before implementing anything.
- Reuse existing BasePage, DriverManager, utilities and listeners.
- Follow the existing project package structure.
- Never create duplicate classes or methods.
- Follow SOLID, DRY and clean-code principles.
- Keep test logic separate from page interaction logic.
- Never modify unrelated working functionality.
- Do not introduce dependencies without justification.
- Never rebuild the framework without approval.

## 3. Smart Locator Priority (MANDATORY)

Always attempt available locators in this order:

1. Accessibility ID
2. Exact visible text or label
3. Native resource ID / stable attributes
4. Relative attribute-based XPath
5. Image recognition (if supported)
6. Coordinates (only with explicit approval)

Rules:
- Stop when a unique, valid element is found.
- Never guess locators.
- Never use absolute XPath by default.
- Avoid index-based XPath.
- Avoid partial-text matching unless explicitly required.
- Do not assume Flutter widgets without semantics
  are available in the Appium hierarchy.
- If a strategy is unavailable, skip it.
- Do not silently use coordinates.
- Verify locator uniqueness before interacting.

## 4. Platform-Specific Rules

### Android
- Prefer AppiumBy.accessibilityId().
- For text, use Android UiAutomator when supported.
- Prefer stable resource IDs over fragile XPath.
- Escape dynamic text safely.
- Do not assume Flutter text is exposed natively.

### iOS
- Prefer AppiumBy.accessibilityId().
- Use iOS predicate strings for supported labels.
- Use class chains when appropriate.
- Avoid index-based locators.
- Verify the target is interactable.

## 5. Smart Locator Utility Rules
- Implement one centralized locator resolver.
- Support find, click, type, clear and visibility checks.
- Accept an ordered list of available locator candidates.
- Use a single configurable overall timeout.
- Use explicit waits.
- Avoid Thread.sleep().
- Avoid mixing implicit and explicit waits.
- Handle stale elements and transient failures.
- Never swallow exceptions silently.
- Use descriptive error messages.
- Keep locators in Page Objects.
- Do not put raw locators in TestNG tests.

## 6. Flutter-Specific Rules
- Inspect actual Appium page source first.
- Use accessibility/text only when exposed.
- Do not rely on Flutter debug-only features
  for release builds.
- Use image matching only when configured.
- Use screen-relative coordinates only as a last resort.
- Validate interactions across supported screen sizes.
- Report elements that require developer semantics.
- Do not claim every Flutter widget is automatable
  without semantics.

## 7. Interaction and Validation Rules
- Confirm the element is displayed and enabled.
- Verify a unique match before clicking.
- Locate the editable control, not just its text label.
- Verify text entry when possible.
- Verify important state changes after clicking.
- Never blindly repeat payment or submission actions.
- Avoid retries that could duplicate transactions.

## 8. TestNG and Parallel Execution Rules
- Keep test cases independent.
- Avoid shared mutable driver instances.
- Use thread-safe driver management.
- Keep test data separate from test logic.
- Use existing TestNG listeners and reporting.
- Avoid hardcoded test data.
- Ensure cleanup after test execution.

## 9. Error Handling and Reporting
When locator resolution fails:
- Log the element's logical name.
- Log each attempted strategy.
- Record the elapsed time.
- Capture a screenshot.
- Capture relevant page-source diagnostics.
- Throw a descriptive exception.
- Never mark failed interactions as passed.
- Do not log passwords, tokens or sensitive data.

## 10. Code Quality Rules
- Follow existing Java naming conventions.
- Use meaningful method and class names.
- Prefer reusable methods over duplicate implementations.
- Avoid unnecessary abstractions.
- Avoid hardcoded device dimensions.
- Do not add unused dependencies or imports.
- Add comments only where logic requires explanation.
- Keep code readable and maintainable.

## 11. AI Agent Execution Rules
Before making changes:
1. Inspect the existing implementation.
2. Identify reusable components.
3. Explain the proposed changes.
4. Wait for approval for major changes.

During implementation:
1. Make minimal, targeted changes.
2. Preserve existing public interfaces where possible.
3. Add appropriate unit tests.
4. Validate Android and iOS differences.
5. Do not modify unrelated files.

After implementation:
1. Run Maven compilation.
2. Run relevant TestNG tests.
3. Report passed and failed tests.
4. List all modified files.
5. Explain unresolved limitations.
6. Never claim unexecuted tests have passed.

## 12. Definition of Done
A task is complete only when:
- Changes follow the existing architecture.
- No unnecessary duplicate code is introduced.
- Locator priority is respected.
- Failure handling is implemented.
- Relevant tests are executed or blockers documented.
- Existing functionality remains compatible.
- A clear implementation summary is provided.
