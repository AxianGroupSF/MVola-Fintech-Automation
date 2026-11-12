package com.qaframework.utils;

import com.qaframework.config.ConfigReader;
import com.qaframework.drivers.DriverManager;
import com.qaframework.exceptions.StepFailureException;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.UnsupportedCommandException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.FluentWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Resolves one element from verified locators inside a single timeout.
 * On Android the order is accessibility id, resource id, scoped attributes,
 * exact text, then a verified relative XPath. Accessibility id already matches
 * content-desc, so a second content-desc lookup is not added.
 * On iOS the caller order is kept and Android resource ids are not assumed.
 * Exact text is built when the locator is used.
 */
public final class SmartLocator {

    /**
     * Attributes this driver may reject. An unknown-attribute response is treated as
     * absent. Password is not optional, and other driver errors still propagate.
     */
    private static final Set<String> OPTIONAL_ATTRIBUTES = Set.of(
            "editable", "class", "className", "type", "value");

    /** Flutter content-desc is the entered value, this delimiter, then the accessibility label. */
    private static final String VALUE_LABEL_DELIMITER = ", ";

    private static final Logger log = LoggerManager.getLogger(SmartLocator.class);

    private final String logicalName;
    private final Supplier<List<By>> candidates;
    private final By container;
    private final boolean editableOptIn;
    private final boolean digitEntryOptIn;
    private final Verification verification;
    private final String valueLabel;

    private SmartLocator(String logicalName, Supplier<List<By>> candidates, By container,
                         boolean editableOptIn, boolean digitEntryOptIn, Verification verification,
                         String valueLabel) {
        this.logicalName = logicalName;
        this.candidates = candidates;
        this.container = container;
        this.editableOptIn = editableOptIn;
        this.digitEntryOptIn = digitEntryOptIn;
        this.verification = verification == null ? Verification.EXACT : verification;
        this.valueLabel = valueLabel;
    }

    public static SmartLocator of(String logicalName, By... candidateList) {
        List<By> ordered = new ArrayList<>();
        if (candidateList != null) {
            for (By candidate : candidateList) {
                if (candidate != null) {
                    ordered.add(candidate);
                }
            }
        }
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("At least one locator candidate is required for " + logicalName);
        }
        List<By> frozen = List.copyOf(ordered);
        return create(logicalName, () -> prioritize(frozen), null, false, false, Verification.EXACT);
    }

    /**
     * Accessibility id first. {@code visibleText} is added only when it is not blank,
     * which means the caller has verified that exact native text is exposed.
     * Further candidates are reordered on Android. Blank values are omitted.
     */
    public static SmartLocator accessibleThenText(String logicalName, String accessibilityId,
                                                  String visibleText, By... additional) {
        boolean hasAccessibility = accessibilityId != null && !accessibilityId.isBlank();
        boolean hasText = visibleText != null && !visibleText.isBlank();
        boolean hasMore = false;
        if (additional != null) {
            for (By extra : additional) {
                if (extra != null) {
                    hasMore = true;
                    break;
                }
            }
        }
        if (!hasAccessibility && !hasText && !hasMore) {
            throw new IllegalArgumentException("At least one locator candidate is required for " + logicalName);
        }
        By[] extras = additional == null ? new By[0] : additional.clone();
        return create(logicalName, () -> {
            List<By> ordered = new ArrayList<>();
            if (hasAccessibility) {
                ordered.add(PlatformLocator.accessibilityId(accessibilityId));
            }
            if (hasText) {
                ordered.add(PlatformLocator.exactText(visibleText));
            }
            for (By extra : extras) {
                if (extra != null) {
                    ordered.add(extra);
                }
            }
            return prioritize(ordered);
        }, null, false, false, Verification.EXACT);
    }

    /**
     * Verified candidates only. Nothing is invented.
     * Android: accessibility id, resource id, scoped class or description, exact text, relative XPath.
     * iOS: accessibility id, exact label, scoped predicate, relative XPath. The resource id is omitted.
     * {@code exposedText} is used only when native text or the iOS label is actually exposed.
     */
    public static SmartLocator verified(String logicalName, String accessibilityId, String resourceId,
                                        By scopedAttributes, String exposedText, By relativeXPath) {
        boolean hasAccessibility = notBlank(accessibilityId);
        boolean hasResource = notBlank(resourceId);
        boolean hasScoped = scopedAttributes != null;
        boolean hasText = notBlank(exposedText);
        boolean hasXPath = relativeXPath != null;
        if (!hasAccessibility && !hasResource && !hasScoped && !hasText && !hasXPath) {
            throw new IllegalArgumentException("At least one locator candidate is required for " + logicalName);
        }
        return create(logicalName, () -> {
            List<By> ordered = new ArrayList<>();
            if (hasAccessibility) {
                ordered.add(PlatformLocator.accessibilityId(accessibilityId));
            }
            if (ConfigReader.isAndroid() && hasResource) {
                ordered.add(PlatformLocator.resourceId(resourceId));
            }
            if (!ConfigReader.isAndroid() && hasText) {
                ordered.add(PlatformLocator.exactText(exposedText));
            }
            if (hasScoped) {
                ordered.add(scopedAttributes);
            }
            if (ConfigReader.isAndroid() && hasText) {
                ordered.add(PlatformLocator.exactText(exposedText));
            }
            if (hasXPath) {
                ordered.add(relativeXPath);
            }
            return prioritize(ordered);
        }, null, false, false, Verification.EXACT);
    }

    /**
     * When more than one eligible element matches, search only inside this container.
     * The match is used only when the container is unique and exactly one eligible descendant matches.
     */
    public SmartLocator within(By parent) {
        if (parent == null) {
            throw new IllegalArgumentException("A container locator is required for " + logicalName);
        }
        return copy(parent, editableOptIn, digitEntryOptIn, verification, valueLabel);
    }

    /** Default. The exposed value must equal the entered text. An unreadable value fails. */
    public SmartLocator verifyExactly() {
        return copy(container, editableOptIn, digitEntryOptIn, Verification.EXACT, null);
    }

    /**
     * Accepts formatted amounts and spacing, such as {@code 100} shown as {@code 100.00}.
     * An unreadable value still fails.
     */
    public SmartLocator verifyNormalized() {
        return copy(container, editableOptIn, digitEntryOptIn, Verification.NORMALIZED, null);
    }

    /**
     * Accepts a Flutter content-desc of {@code enteredValue + ", " + label}.
     * The label must be the exact suffix. The value before the delimiter must equal the entered text.
     */
    public SmartLocator verifyValueWithLabel(String label) {
        if (label == null || label.isBlank() || label.contains(VALUE_LABEL_DELIMITER)
                || label.indexOf('\n') >= 0 || label.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "A known accessibility label without the value delimiter is required for " + logicalName);
        }
        return copy(container, editableOptIn, digitEntryOptIn, Verification.VALUE_WITH_LABEL, label);
    }

    /**
     * Skips read-back for a Flutter field that does not expose the entered value.
     * This is explicit. An unreadable value is never treated as a successful check.
     */
    public SmartLocator withoutVerification() {
        return copy(container, editableOptIn, digitEntryOptIn, Verification.UNVERIFIED, null);
    }

    /**
     * Page opt-in for a non-secure field whose editability was verified outside the native class.
     * Secure fields still reject clear and sendKeys.
     */
    public SmartLocator asEditableField() {
        return copy(container, true, digitEntryOptIn, verification, valueLabel);
    }

    /**
     * Page opt-in for digit entry on a verified field that has no native editable attribute.
     * Entry uses {@link MobileActions#typeDigits(String)} and does not clear or sendKeys.
     */
    public SmartLocator forDigitEntry() {
        return copy(container, editableOptIn, true, verification, valueLabel);
    }

    private SmartLocator copy(By parent, boolean editable, boolean digits, Verification mode, String label) {
        return new SmartLocator(logicalName, candidates, parent, editable, digits, mode, label);
    }

    public WebElement findElement() {
        return resolve(Requirement.VISIBLE).element();
    }

    public WebElement waitForElement() {
        return findElement();
    }

    public void click() {
        click(ResolutionSession.device());
    }

    public void type(String text) {
        type(ResolutionSession.device(), text);
    }

    public void clear() {
        clear(ResolutionSession.device());
    }

    /**
     * Focuses the field once and types digits through the existing key-event path.
     * The field is not cleared and sendKeys is not used.
     */
    public void enterDigits(String digits) {
        enterDigits(ResolutionSession.device(), digits);
    }

    public boolean isVisible() {
        return visible(ResolutionSession.device());
    }

    void click(ResolutionSession session) {
        performOnce(session, Requirement.CLICKABLE, "Clicking", CandidateHit::click);
    }

    void type(ResolutionSession session, String text) {
        if (text == null) {
            throw new IllegalArgumentException("Text is required for " + logicalName);
        }
        performOnce(session, Requirement.EDITABLE, "Typing " + text.length() + " character(s) into", hit -> {
            hit.type(text);
            verifyTyped(session, hit, text);
        });
    }

    void clear(ResolutionSession session) {
        performOnce(session, Requirement.EDITABLE, "Clearing", CandidateHit::clear);
    }

    void enterDigits(ResolutionSession session, String digits) {
        if (digits == null || digits.isEmpty() || digits.chars().anyMatch(ch -> !Character.isDigit(ch))) {
            throw new IllegalArgumentException("enterDigits requires one or more digits");
        }
        performOnce(session, Requirement.DIGITS, "Entering " + digits.length() + " digit(s) into", hit -> {
            boolean secret = secure(new Attr(hit));
            hit.click();
            session.digitEntry.enter(digits);
            if (!secret) {
                verifyExposedDigits(session, hit, digits);
            }
        });
    }

    boolean visible(ResolutionSession session) {
        return resolve(session, Requirement.VISIBLE, false) != null;
    }

    private CandidateHit resolve(Requirement requirement) {
        return resolve(ResolutionSession.device(), requirement, true);
    }

    private void performOnce(ResolutionSession session, Requirement requirement, String action,
                             Consumer<CandidateHit> interaction) {
        long deadline = System.nanoTime() + session.timeout.toNanos();
        CandidateHit resolved = resolve(session, requirement, deadline, true);
        log.info("{} '{}'", action, logicalName);
        try {
            interaction.accept(resolved);
        } catch (StaleElementReferenceException stale) {
            log.warn("Resolved '{}' went stale. The action was not repeated.", logicalName);
            throw notRepeated(session, stale);
        }
    }

    private CandidateHit resolve(ResolutionSession session, Requirement requirement, boolean fail) {
        long deadline = System.nanoTime() + session.timeout.toNanos();
        return resolve(session, requirement, deadline, fail);
    }

    private CandidateHit resolve(ResolutionSession session, Requirement requirement,
                                 long deadlineNanos, boolean fail) {
        if (session.startedNanos == 0) {
            session.startedNanos = System.nanoTime();
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            if (!fail) {
                return null;
            }
            throw failure(session, null);
        }
        FluentWait<ResolutionSession> wait = new FluentWait<>(session)
                .withTimeout(Duration.ofNanos(remainingNanos))
                .pollingEvery(session.poll)
                .ignoring(StaleElementReferenceException.class);
        try {
            return wait.until(value -> poll(value, requirement));
        } catch (TimeoutException timedOut) {
            if (!fail) {
                log.debug("'{}' was not visible after {}ms. Last cycle: {}",
                        logicalName, elapsedMillis(session), session.lastNotes);
                return null;
            }
            throw failure(session, timedOut);
        }
    }

    private CandidateHit poll(ResolutionSession session, Requirement requirement) {
        session.cycles++;
        List<String> notes = new ArrayList<>();
        CandidateHit[] chosen = new CandidateHit[1];
        try {
            session.lookup.runScoped(() -> inspect(session, requirement, notes, chosen));
            return chosen[0];
        } finally {
            session.lastNotes = List.copyOf(notes);
        }
    }

    private void inspect(ResolutionSession session, Requirement requirement, List<String> notes,
                         CandidateHit[] chosen) {
        for (By candidate : candidateList(notes)) {
            try {
                Selection selection = choose(session, candidate,
                        session.lookup.find(candidate), requirement, notes);
                if (selection.hit != null) {
                    log.info("Resolved '{}' using {}", logicalName, candidate);
                    chosen[0] = selection.hit;
                    return;
                }
                if (selection.wait) {
                    return;
                }
            } catch (StaleElementReferenceException stale) {
                notes.add(candidate + " -> stale");
                log.warn("Stale element while resolving '{}' with {}", logicalName, candidate);
            }
        }
    }

    private Selection choose(ResolutionSession session, By candidate, List<CandidateHit> found,
                             Requirement requirement, List<String> notes) {
        if (found == null || found.isEmpty()) {
            notes.add(candidate + " -> 0");
            return Selection.missed();
        }
        List<CandidateHit> eligible = suitable(found, requirement);
        if (eligible.size() == 1) {
            return Selection.chosen(eligible.get(0));
        }
        if (eligible.size() > 1 || found.size() > 1) {
            return disambiguate(session, candidate, found, eligible, requirement, notes);
        }
        CandidateHit only = found.get(0);
        if (temporarilyUnavailable(only, requirement)) {
            notes.add(candidate + " -> waiting, element not ready");
            return Selection.waitForReady();
        }
        if (requirement == Requirement.EDITABLE && only.displayed() && only.enabled() && secure(new Attr(only))) {
            throw secureRefusal(session);
        }
        notes.add(candidate + " -> " + rejection(only, requirement));
        return Selection.missed();
    }

    private Selection disambiguate(ResolutionSession session, By candidate, List<CandidateHit> found,
                                   List<CandidateHit> eligible, Requirement requirement, List<String> notes) {
        if (container == null) {
            int count = eligible.size() > 1 ? eligible.size() : found.size();
            notes.add(candidate + " -> " + count + " matches, no unique container");
            return Selection.missed();
        }
        List<CandidateHit> parents = session.lookup.find(container);
        if (parents.size() != 1 || !parents.get(0).displayed()) {
            notes.add("container -> " + parents.size() + " matches");
            return Selection.missed();
        }
        List<CandidateHit> inside = session.lookup.findWithin(parents.get(0), candidate);
        List<CandidateHit> eligibleInside = suitable(inside, requirement);
        if (eligibleInside.size() == 1) {
            return Selection.chosen(eligibleInside.get(0));
        }
        if (eligibleInside.isEmpty() && inside.size() == 1
                && temporarilyUnavailable(inside.get(0), requirement)) {
            notes.add(candidate + " -> waiting, element not ready");
            return Selection.waitForReady();
        }
        if (requirement == Requirement.EDITABLE && inside.size() == 1 && inside.get(0).displayed()
                && inside.get(0).enabled() && secure(new Attr(inside.get(0)))) {
            throw secureRefusal(session);
        }
        notes.add(candidate + " -> " + eligibleInside.size() + " inside container");
        return Selection.missed();
    }

    private List<CandidateHit> suitable(List<CandidateHit> found, Requirement requirement) {
        List<CandidateHit> matches = new ArrayList<>();
        for (CandidateHit hit : found) {
            if (matches(hit, requirement)) {
                matches.add(hit);
            }
        }
        return matches;
    }

    private boolean matches(CandidateHit hit, Requirement requirement) {
        if (!hit.displayed()) {
            return false;
        }
        if (requirement == Requirement.VISIBLE) {
            return true;
        }
        if (!hit.enabled()) {
            return false;
        }
        if (requirement == Requirement.CLICKABLE) {
            return true;
        }
        Attr attr = new Attr(hit);
        if (requirement == Requirement.DIGITS) {
            return acceptsDigits(attr);
        }
        return editable(attr);
    }

    private boolean temporarilyUnavailable(CandidateHit hit, Requirement requirement) {
        if (!hit.displayed()) {
            return true;
        }
        return requirement != Requirement.VISIBLE && !hit.enabled();
    }

    private String rejection(CandidateHit hit, Requirement requirement) {
        if (!hit.displayed()) {
            return "not displayed";
        }
        if (requirement != Requirement.VISIBLE && !hit.enabled()) {
            return "not enabled";
        }
        if (requirement == Requirement.EDITABLE && !editable(new Attr(hit))) {
            return "not editable";
        }
        if (requirement == Requirement.DIGITS) {
            return "not a verified digit field";
        }
        return "not usable";
    }

    /**
     * SendKeys is allowed for an explicit opt-in or a verified editable attribute/native text class.
     * Secure controls are excluded so PIN and OTP fields are not cleared or typed with sendKeys.
     */
    private boolean editable(Attr attr) {
        if (secure(attr)) {
            return false;
        }
        if (editableOptIn) {
            return true;
        }
        if (isTrue(attr.get("editable"))) {
            return true;
        }
        return nativeTextClass(attr);
    }

    private boolean acceptsDigits(Attr attr) {
        if (digitEntryOptIn || secure(attr)) {
            return true;
        }
        return nativeTextClass(attr) || isTrue(attr.get("editable"));
    }

    private boolean secure(Attr attr) {
        if (isTrue(attr.get("password"))) {
            return true;
        }
        String cls = controlClass(attr);
        return cls != null && cls.contains("Secure");
    }

    private boolean nativeTextClass(Attr attr) {
        String cls = controlClass(attr);
        if (cls == null || cls.contains("ImageView") || cls.contains("ImageButton") || cls.contains("Secure")) {
            return false;
        }
        return cls.contains("EditText")
                || cls.contains("XCUIElementTypeTextField")
                || cls.contains("XCUIElementTypeTextView");
    }

    private String controlClass(Attr attr) {
        String cls = attr.get("class");
        if (cls != null && !cls.isBlank()) {
            return cls;
        }
        cls = attr.get("className");
        if (cls != null && !cls.isBlank()) {
            return cls;
        }
        return attr.get("type");
    }

    private void verifyTyped(ResolutionSession session, CandidateHit hit, String text) {
        if (verification == Verification.UNVERIFIED) {
            return;
        }
        if (verification == Verification.VALUE_WITH_LABEL) {
            verifyDescribedValue(session, hit, text);
            return;
        }
        String actual = hit.displayedText();
        if (!readable(actual)) {
            throw textNotShown(session, text, actual, false);
        }
        if (!matches(actual, text)) {
            throw textNotShown(session, text, actual, true);
        }
    }

    /**
     * Content-desc must be exactly the entered value, then {@code ", "}, then the known label.
     * Any other shape fails. The check is not a substring search.
     */
    private void verifyDescribedValue(ResolutionSession session, CandidateHit hit, String text) {
        String described = hit.attribute("content-desc");
        String value = valueBeforeLabel(described);
        if (text.isBlank() || value == null || !value.equals(text)) {
            String reported = text.isBlank() ? "value" : text;
            boolean exposed = !text.isBlank() && described != null && !described.isBlank();
            throw textNotShown(session, reported, described, exposed);
        }
    }

    private String valueBeforeLabel(String contentDesc) {
        if (contentDesc == null || valueLabel == null) {
            return null;
        }
        String suffix = VALUE_LABEL_DELIMITER + valueLabel;
        if (contentDesc.length() <= suffix.length() || !contentDesc.endsWith(suffix)) {
            return null;
        }
        String value = contentDesc.substring(0, contentDesc.length() - suffix.length());
        if (value.isBlank()) {
            return null;
        }
        return value;
    }

    /** Non-secure digit entry is checked only when the hierarchy exposes a value. */
    private void verifyExposedDigits(ResolutionSession session, CandidateHit hit, String digits) {
        String actual = hit.displayedText();
        if (!readable(actual)) {
            return;
        }
        if (!digitsOf(actual).equals(digits)) {
            throw textNotShown(session, digits, actual, true);
        }
    }

    private boolean matches(String actual, String expected) {
        if (verification == Verification.NORMALIZED) {
            if (expected.chars().allMatch(Character::isDigit)) {
                return digitsOf(actual).equals(expected);
            }
            return normalize(actual).equals(normalize(expected));
        }
        return actual.equals(expected);
    }

    private static boolean readable(String actual) {
        if (actual == null || actual.isBlank()) {
            return false;
        }
        return !actual.replaceAll("[\\s•●*]+", "").isEmpty();
    }

    private static String digitsOf(String value) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                digits.append(value.charAt(i));
            }
        }
        return digits.toString();
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private StepFailureException textNotShown(ResolutionSession session, String text, String actual, boolean exposed) {
        boolean digits = text.chars().allMatch(Character::isDigit);
        String reason;
        if (!exposed || digits) {
            reason = digits
                    ? "Entered digits were not shown in the field."
                    : "Entered text was not exposed by the field.";
        } else {
            reason = "Entered text was not shown in the field. Expected '" + text
                    + "' but found '" + redactDiagnostics(actual) + "'.";
        }
        return fail(session, reason, null);
    }

    private StepFailureException secureRefusal(ResolutionSession session) {
        return fail(session,
                "Secure field '" + logicalName + "' does not accept clear or sendKeys. Use enterDigits.",
                null);
    }

    private StepFailureException notRepeated(ResolutionSession session, Throwable cause) {
        return fail(session, "The resolved element went stale and the action was not repeated.", cause);
    }

    private StepFailureException failure(ResolutionSession session, Throwable cause) {
        String reason = "No unique match after " + elapsedMillis(session) + "ms (budget "
                + session.timeout.toMillis() + "ms), " + session.cycles
                + " cycle(s). Last cycle: " + session.lastNotes;
        return fail(session, reason, cause);
    }

    private StepFailureException fail(ResolutionSession session, String reason, Throwable cause) {
        String source = pageSourceExcerpt();
        if (!source.isEmpty()) {
            reason = reason + " Page source: " + source;
        }
        log.error("Could not use '{}'. {}", logicalName, reason);
        captureFailure();
        return new StepFailureException(logicalName, strategies(), reason, cause);
    }

    private List<By> candidateList(List<String> notes) {
        try {
            List<By> current = candidates.get();
            return current == null ? List.of() : current;
        } catch (RuntimeException e) {
            if (notes != null) {
                notes.add("locator list unavailable");
            }
            log.warn("Locator list for '{}' could not be read", logicalName);
            return List.of();
        }
    }

    private String strategies() {
        List<By> current = candidateList(null);
        if (current.isEmpty()) {
            return logicalName;
        }
        return current.stream().map(String::valueOf).collect(Collectors.joining(" | "));
    }

    private static long elapsedMillis(ResolutionSession session) {
        if (session.startedNanos == 0) {
            return 0;
        }
        return Math.max(0, (System.nanoTime() - session.startedNanos) / 1_000_000L);
    }

    private void captureFailure() {
        if (!DriverManager.hasDriver()) {
            return;
        }
        try {
            ScreenshotUtils.captureFailureStep(logicalName);
        } catch (RuntimeException screenshotError) {
            log.warn("Failure screenshot was not saved for '{}': {}", logicalName, screenshotError.getMessage());
        }
    }

    private static String pageSourceExcerpt() {
        if (!DriverManager.hasDriver()) {
            return "";
        }
        try {
            String source = DriverManager.getDriver().getPageSource();
            if (source == null || source.isBlank()) {
                return "";
            }
            String compact = redactDiagnostics(source.replaceAll("\\s+", " ").trim());
            if (compact.length() <= 400) {
                return compact;
            }
            return compact.substring(0, 400) + "...";
        } catch (RuntimeException e) {
            log.warn("Page source was not available: {}", e.getMessage());
            return "";
        }
    }

    static String redactDiagnostics(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String passwords = raw.replaceAll("(?i)(password|pin|otp)(\\s*=\\s*\")[^\"]*(\")", "$1$2***$3");
        return passwords.replaceAll("\\d{3,}", "***");
    }

    private static boolean isTrue(String value) {
        return "true".equalsIgnoreCase(value);
    }

    private static List<By> prioritize(List<By> given) {
        if (given == null || given.isEmpty()) {
            return List.of();
        }
        if (!ConfigReader.isAndroid()) {
            return List.copyOf(given);
        }
        List<By> accessibility = new ArrayList<>();
        List<By> resource = new ArrayList<>();
        List<By> scoped = new ArrayList<>();
        List<By> text = new ArrayList<>();
        List<By> other = new ArrayList<>();
        List<By> xpath = new ArrayList<>();
        Set<String> accessibilityValues = new HashSet<>();
        for (By candidate : given) {
            if (isAccessibilityId(candidate)) {
                String value = accessibilityValue(candidate);
                if (value != null) {
                    accessibilityValues.add(value);
                }
            }
        }
        Set<String> seenDescriptions = new HashSet<>();
        for (By candidate : given) {
            if (candidate == null) {
                continue;
            }
            if (isDescriptionOnly(candidate)) {
                String described = quotedArgument(candidate, "description");
                if (described != null && (accessibilityValues.contains(described)
                        || !seenDescriptions.add(described))) {
                    continue;
                }
                accessibility.add(candidate);
                continue;
            }
            if (isAccessibilityId(candidate)) {
                String value = accessibilityValue(candidate);
                if (value != null && !seenDescriptions.add(value)) {
                    continue;
                }
                accessibility.add(candidate);
                continue;
            }
            if (isResource(candidate)) {
                resource.add(candidate);
                continue;
            }
            if (isExactText(candidate)) {
                text.add(candidate);
                continue;
            }
            if (isXPath(candidate)) {
                xpath.add(candidate);
                continue;
            }
            if (isScoped(candidate)) {
                scoped.add(candidate);
                continue;
            }
            other.add(candidate);
        }
        List<By> ordered = new ArrayList<>();
        ordered.addAll(accessibility);
        ordered.addAll(resource);
        ordered.addAll(scoped);
        ordered.addAll(text);
        ordered.addAll(other);
        ordered.addAll(xpath);
        return List.copyOf(ordered);
    }

    private static boolean isAccessibilityId(By candidate) {
        return candidate != null && candidate.getClass().getSimpleName().contains("Accessibility");
    }

    private static boolean isResource(By candidate) {
        String type = candidate.getClass().getSimpleName();
        if (type.contains("Accessibility") || type.contains("XPath")) {
            return false;
        }
        if (type.contains("ById") || type.endsWith("Id")) {
            return true;
        }
        return String.valueOf(candidate).contains("resourceId(");
    }

    private static boolean isExactText(By candidate) {
        String value = String.valueOf(candidate);
        if (value.contains("textContains") || value.contains("textStartsWith") || value.contains("textMatches")) {
            return false;
        }
        boolean textOnly = value.contains(".text(\"")
                && !value.contains("className(")
                && !value.contains("description(")
                && !value.contains("resourceId(");
        boolean iosLabel = value.contains("name ==") && value.contains("label ==") && value.contains("value ==");
        return textOnly || iosLabel;
    }

    private static boolean isXPath(By candidate) {
        String type = candidate.getClass().getSimpleName();
        return type.contains("XPath") || String.valueOf(candidate).startsWith("By.xpath");
    }

    private static boolean isScoped(By candidate) {
        String type = candidate.getClass().getSimpleName();
        return type.contains("UIAutomator") || type.contains("ClassChain") || type.contains("NsPredicate")
                || type.contains("ClassName");
    }

    /** A content-desc selector with no class, resource id, or text. Accessibility id already covers it. */
    private static boolean isDescriptionOnly(By candidate) {
        String value = String.valueOf(candidate);
        if (!value.contains(".description(\"") || value.contains("descriptionContains")
                || value.contains("descriptionStartsWith") || value.contains("descriptionMatches")) {
            return false;
        }
        return !value.contains("className(") && !value.contains("resourceId(") && !value.contains(".text(");
    }

    private static String accessibilityValue(By candidate) {
        String value = String.valueOf(candidate);
        String marker = "accessibilityId:";
        int start = value.indexOf(marker);
        if (start < 0) {
            return null;
        }
        return value.substring(start + marker.length()).trim();
    }

    private static String quotedArgument(By candidate, String method) {
        String value = String.valueOf(candidate);
        String marker = "." + method + "(\"";
        int start = value.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = value.indexOf("\")", start);
        if (end < start) {
            return null;
        }
        return value.substring(start, end);
    }

    private static String readOptional(String name, Supplier<String> read) {
        try {
            return read.get();
        } catch (UnsupportedCommandException unsupported) {
            if (OPTIONAL_ATTRIBUTES.contains(name) && unknownAttribute(unsupported)) {
                log.debug("Attribute '{}' is not supported and was treated as absent", name);
                return null;
            }
            throw unsupported;
        }
    }

    private static boolean unknownAttribute(UnsupportedCommandException unsupported) {
        String message = unsupported.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("attribute is unknown");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    static SmartLocator fromSource(String logicalName, Supplier<List<By>> source) {
        return create(logicalName, source, null, false, false, Verification.EXACT);
    }

    private static SmartLocator create(String logicalName, Supplier<List<By>> source, By parent,
                                       boolean editableOptIn, boolean digitEntryOptIn, Verification verification) {
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("A logical element name is required");
        }
        if (source == null) {
            throw new IllegalArgumentException("At least one locator candidate is required for " + logicalName);
        }
        return new SmartLocator(logicalName, source, parent, editableOptIn, digitEntryOptIn, verification, null);
    }

    private enum Verification {
        EXACT,
        NORMALIZED,
        UNVERIFIED,
        VALUE_WITH_LABEL
    }

    @Override
    public String toString() {
        return logicalName + " [" + strategies() + "]";
    }

    private enum Requirement {
        VISIBLE,
        CLICKABLE,
        EDITABLE,
        DIGITS
    }

    private static final class Selection {
        private final CandidateHit hit;
        private final boolean wait;

        private Selection(CandidateHit hit, boolean wait) {
            this.hit = hit;
            this.wait = wait;
        }

        private static Selection chosen(CandidateHit hit) {
            return new Selection(hit, false);
        }

        private static Selection waitForReady() {
            return new Selection(null, true);
        }

        private static Selection missed() {
            return new Selection(null, false);
        }
    }

    private static final class Attr {
        private final CandidateHit hit;
        private final Map<String, String> values = new HashMap<>();
        private final Set<String> loaded = new HashSet<>();

        private Attr(CandidateHit hit) {
            this.hit = hit;
        }

        private String get(String name) {
            if (loaded.add(name)) {
                values.put(name, readOptional(name, () -> hit.attribute(name)));
            }
            return values.get(name);
        }
    }

    interface Lookup {
        List<CandidateHit> find(By locator);

        List<CandidateHit> findWithin(CandidateHit parent, By locator);

        default void runScoped(Runnable action) {
            action.run();
        }
    }

    interface CandidateHit {
        boolean displayed();

        boolean enabled();

        String attribute(String name);

        void click();

        void type(String text);

        void clear();

        String displayedText();

        WebElement element();
    }

    interface DigitEntry {
        void enter(String digits);
    }

    static final class ResolutionSession {
        final Lookup lookup;
        final Duration timeout;
        final Duration poll;
        final DigitEntry digitEntry;
        int cycles;
        long startedNanos;
        List<String> lastNotes = List.of();

        private ResolutionSession(Lookup lookup, Duration timeout, Duration poll, DigitEntry digitEntry) {
            if (lookup == null) {
                throw new IllegalArgumentException("A lookup is required");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Timeout must be greater than zero");
            }
            this.lookup = lookup;
            this.timeout = timeout;
            this.poll = poll == null || poll.isZero() || poll.isNegative() ? Duration.ofMillis(200) : poll;
            this.digitEntry = digitEntry == null ? digits -> {
                throw new IllegalStateException("Digit entry is not configured");
            } : digitEntry;
        }

        static ResolutionSession device() {
            return new ResolutionSession(
                    new DriverLookup(),
                    Duration.ofSeconds(ConfigReader.getInt("explicitWait", 20)),
                    Duration.ofMillis(ConfigReader.getInt("fluentWaitPolling", 500)),
                    MobileActions::typeDigits);
        }

        static ResolutionSession of(Lookup lookup, Duration timeout, Duration poll) {
            return new ResolutionSession(lookup, timeout, poll, null);
        }

        static ResolutionSession of(Lookup lookup, Duration timeout, Duration poll, DigitEntry digitEntry) {
            return new ResolutionSession(lookup, timeout, poll, digitEntry);
        }
    }

    private static final class DriverLookup implements Lookup {
        @Override
        public void runScoped(Runnable action) {
            WaitUtils.withoutImplicitWait(() -> {
                action.run();
                return Boolean.TRUE;
            });
        }

        @Override
        public List<CandidateHit> find(By locator) {
            return DriverManager.getDriver().findElements(locator).stream()
                    .map(WebHit::new)
                    .collect(Collectors.toList());
        }

        @Override
        public List<CandidateHit> findWithin(CandidateHit parent, By locator) {
            WebElement element = parent.element();
            if (element == null) {
                return List.of();
            }
            return element.findElements(locator).stream().map(WebHit::new).collect(Collectors.toList());
        }
    }

    private static final class WebHit implements CandidateHit {
        private final WebElement element;

        private WebHit(WebElement element) {
            this.element = element;
        }

        @Override
        public boolean displayed() {
            return element.isDisplayed();
        }

        @Override
        public boolean enabled() {
            return element.isEnabled();
        }

        @Override
        public String attribute(String name) {
            return element.getAttribute(name);
        }

        @Override
        public void click() {
            element.click();
        }

        @Override
        public void type(String text) {
            element.clear();
            element.sendKeys(text);
        }

        @Override
        public void clear() {
            element.clear();
        }

        @Override
        public String displayedText() {
            String fromAttribute = element.getAttribute("text");
            if (fromAttribute != null && !fromAttribute.isBlank()) {
                return fromAttribute;
            }
            String fromElement = element.getText();
            if (fromElement != null && !fromElement.isBlank()) {
                return fromElement;
            }
            String described = element.getAttribute("content-desc");
            if (described != null && !described.isBlank()) {
                return described;
            }
            return readOptional("value", () -> element.getAttribute("value"));
        }

        @Override
        public WebElement element() {
            return element;
        }
    }
}
