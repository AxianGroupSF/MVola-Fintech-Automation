package com.qaframework.utils;

import com.qaframework.exceptions.StepFailureException;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.UnsupportedCommandException;
import org.openqa.selenium.WebElement;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Resolver behavior without an Appium session. Device lookup is not covered here.
 */
public class SmartLocatorTest {

    private String previousPlatform;

    @BeforeMethod
    public void useAndroid() {
        previousPlatform = System.getProperty("platform");
        System.setProperty("platform", "android");
    }

    @AfterMethod
    public void restorePlatform() {
        if (previousPlatform == null) {
            System.clearProperty("platform");
        } else {
            System.setProperty("platform", previousPlatform);
        }
    }

    @Test(timeOut = 5000)
    public void accessibilityIdIsUsedBeforeExactText() {
        RecordingLookup lookup = new RecordingLookup();
        Hit button = Hit.clickable("continue", lookup);
        lookup.handler = by -> accessibility(by) ? List.of(button) : List.of(Hit.clickable("text-copy", lookup));

        continueButton().click(session(lookup, 300));

        Assert.assertEquals(button.clicks, 1);
        Assert.assertEquals(button.attributeNames.size(), 0, button.attributeNames.toString());
        Assert.assertEquals(lookup.calls, 1, lookup.order.toString());
        Assert.assertEquals(lookup.findsAtInteraction, 1);
        Assert.assertTrue(accessibility(lookup.order.get(0)), lookup.order.toString());
        Assert.assertTrue(lookup.order.stream().noneMatch(SmartLocatorTest::textLocator), lookup.order.toString());
    }

    @Test(timeOut = 5000)
    public void exactTextIsUsedWhenAccessibilityIdMisses() {
        RecordingLookup lookup = new RecordingLookup();
        Hit button = Hit.clickable("continue-text", lookup);
        lookup.handler = by -> textLocator(by) ? List.of(button) : List.of();

        continueButton().click(session(lookup, 300));

        Assert.assertEquals(button.clicks, 1);
        Assert.assertEquals(lookup.calls, 2, lookup.order.toString());
        Assert.assertEquals(lookup.findsAtInteraction, 2);
        Assert.assertTrue(accessibility(lookup.order.get(0)), lookup.order.toString());
        Assert.assertTrue(textLocator(lookup.order.get(1)), lookup.order.toString());
    }

    @Test(timeOut = 5000)
    public void duplicateTextIsRejectedWithoutAContainer() {
        RecordingLookup lookup = new RecordingLookup();
        Hit left = Hit.clickable("left", lookup);
        Hit right = Hit.clickable("right", lookup);
        lookup.handler = by -> textLocator(by) ? List.of(left, right) : List.of();

        try {
            continueButton().click(session(lookup, 160));
            Assert.fail("Duplicate text must not be clicked");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("2 matches, no unique container"),
                    expected.getMessage());
        }
        Assert.assertEquals(left.clicks, 0);
        Assert.assertEquals(right.clicks, 0);
    }

    @Test(timeOut = 5000)
    public void duplicateTextResolvesInsideAVerifiedContainer() {
        RecordingLookup lookup = new RecordingLookup();
        Hit left = Hit.clickable("left", lookup);
        Hit right = Hit.clickable("right", lookup);
        Hit screen = Hit.clickable("screen", lookup);
        Hit inside = Hit.clickable("inside", lookup);
        By container = PlatformLocator.accessibilityId("Amount screen");
        lookup.handler = by -> {
            String value = String.valueOf(by);
            if (value.contains("Amount screen")) {
                return List.of(screen);
            }
            if (textLocator(value)) {
                return List.of(left, right);
            }
            return List.of();
        };
        lookup.withinHandler = parent -> List.of(inside);

        SmartLocator amount = SmartLocator.accessibleThenText("Amount", "100 Unit", "100 Unit").within(container);
        amount.click(session(lookup, 300));

        Assert.assertEquals(inside.clicks, 1);
        Assert.assertEquals(left.clicks, 0);
        Assert.assertEquals(right.clicks, 0);
        Assert.assertEquals(lookup.containers, List.of("screen"));
        Assert.assertEquals(lookup.calls, 3, lookup.order.toString());
        Assert.assertEquals(lookup.findsAtInteraction, 3);
    }

    @Test(timeOut = 5000)
    public void ambiguousMatchInsideContainerIsRejected() {
        RecordingLookup lookup = new RecordingLookup();
        Hit left = Hit.clickable("left", lookup);
        Hit right = Hit.clickable("right", lookup);
        Hit screen = Hit.clickable("screen", lookup);
        lookup.handler = by -> {
            String value = String.valueOf(by);
            if (value.contains("Amount screen")) {
                return List.of(screen);
            }
            if (textLocator(value)) {
                return List.of(left, right);
            }
            return List.of();
        };
        lookup.withinHandler = parent -> List.of(left, right);

        SmartLocator amount = SmartLocator.accessibleThenText("Amount", "100 Unit", "100 Unit")
                .within(PlatformLocator.accessibilityId("Amount screen"));
        try {
            amount.click(session(lookup, 160));
            Assert.fail("Two matches inside the container must not be clicked");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("inside container"), expected.getMessage());
        }
        Assert.assertEquals(left.clicks, 0);
        Assert.assertEquals(right.clicks, 0);
    }

    @Test(timeOut = 5000)
    public void staleLookupIsRetriedWithinTheSameTimeout() {
        RecordingLookup lookup = new RecordingLookup();
        Hit button = Hit.clickable("continue", lookup);
        int[] accessibilityAttempts = {0};
        lookup.handler = by -> {
            if (!accessibility(by)) {
                return List.of();
            }
            accessibilityAttempts[0]++;
            if (accessibilityAttempts[0] == 1) {
                throw new StaleElementReferenceException("detached");
            }
            return List.of(button);
        };

        continueButton().click(session(lookup, 400));

        Assert.assertEquals(button.clicks, 1);
        Assert.assertEquals(accessibilityAttempts[0], 2);
        Assert.assertEquals(lookup.calls, 3, lookup.order.toString());
        Assert.assertEquals(lookup.findsAtInteraction, 3);
    }

    @Test(timeOut = 5000)
    public void staleClickIsNotRetried() {
        RecordingLookup lookup = new RecordingLookup();
        Hit button = Hit.clickable("continue", lookup);
        button.failClickOnce = true;
        lookup.handler = by -> accessibility(by) ? List.of(button) : List.of();

        try {
            continueButton().click(session(lookup, 400));
            Assert.fail("A stale click must not be repeated");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not repeated"), expected.getMessage());
        }
        Assert.assertEquals(button.clicks, 0);
        Assert.assertEquals(lookup.calls, 1, lookup.order.toString());
    }

    @Test(timeOut = 5000)
    public void candidatesShareOneTimeout() {
        RecordingLookup lookup = new RecordingLookup();
        lookup.handler = by -> List.of();
        long started = System.nanoTime();
        try {
            continueButton().click(session(lookup, 250));
            Assert.fail("The search must stop when the shared timeout expires");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("cycle"), expected.getMessage());
        }
        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        long gapMs = TimeUnit.NANOSECONDS.toMillis(lookup.times.get(1) - lookup.times.get(0));
        Assert.assertTrue(accessibility(lookup.order.get(0)), lookup.order.toString());
        Assert.assertTrue(textLocator(lookup.order.get(1)), lookup.order.toString());
        Assert.assertTrue(gapMs < 100, "Candidates were " + gapMs + "ms apart: " + lookup.order);
        Assert.assertTrue(count(lookup.order, "accessibilityId") >= 2, lookup.order.toString());
        Assert.assertTrue(count(lookup.order, "text(") >= 2, lookup.order.toString());
        Assert.assertTrue(lookup.scopeEntries >= 2, "scopes " + lookup.scopeEntries);
        Assert.assertEquals(lookup.calls, lookup.scopeEntries * 2);
        Assert.assertTrue(totalMs < 2000, "Resolution ran for " + totalMs + "ms");
    }

    @Test(timeOut = 5000)
    public void completeMissReportsNameAndStrategies() {
        RecordingLookup lookup = new RecordingLookup();
        try {
            continueButton().click(session(lookup, 120));
            Assert.fail("A total miss must fail the step");
        } catch (StepFailureException expected) {
            String message = expected.getMessage();
            Assert.assertTrue(expected instanceof AssertionError);
            Assert.assertTrue(message.contains("Continue"), message);
            Assert.assertTrue(message.contains("accessibilityId"), message);
            Assert.assertTrue(message.contains("text("), message);
            Assert.assertTrue(message.contains("-> 0"), message);
            Assert.assertTrue(message.contains("cycle"), message);
            Assert.assertTrue(message.contains("budget 120ms"), message);
            Assert.assertTrue(message.matches("(?s).*after \\d+ms.*"), message);
        }
    }

    @Test(timeOut = 5000)
    public void missingElementIsNotVisibleAndDoesNotFail() {
        RecordingLookup lookup = new RecordingLookup();
        Assert.assertFalse(continueButton().visible(session(lookup, 80)));
    }

    @Test(timeOut = 5000)
    public void flutterEditableAttributeAcceptsANonEditText() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = Hit.field("flutter-field", lookup, Map.of(
                "class", "android.view.View",
                "editable", "true"));
        lookup.handler = by -> accessibility(by) ? List.of(field) : List.of();

        SmartLocator.accessibleThenText("Recipient", "Enter number", "Enter number")
                .type(session(lookup, 300), "55500001");

        Assert.assertEquals(field.typed, "55500001");
        Assert.assertEquals(lookup.calls, 1, lookup.order.toString());
        Assert.assertEquals(lookup.findsAtInteraction, 1);
    }

    @Test(timeOut = 5000)
    public void flutterClickableLongClickableViewIsNotImplicitlyEditable() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = Hit.field("otp", lookup, Map.of(
                "class", "android.view.View",
                "clickable", "true",
                "longClickable", "true"));
        lookup.handler = by -> accessibility(by) ? List.of(field) : List.of();

        try {
            SmartLocator.accessibleThenText("OTP", "OTP", "OTP").type(session(lookup, 120), "1234");
            Assert.fail("clickable and longClickable must not make a field editable");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not editable"), expected.getMessage());
        }
        Assert.assertNull(field.typed);
        Assert.assertEquals(field.clearCount, 0);
        Assert.assertFalse(field.attributeNames.contains("clickable"), field.attributeNames.toString());
        Assert.assertFalse(field.attributeNames.contains("longClickable"), field.attributeNames.toString());
    }

    @Test(timeOut = 5000)
    public void imageButtonFallsThroughToEditText() {
        RecordingLookup lookup = new RecordingLookup();
        Hit image = Hit.field("continue-image", lookup, Map.of(
                "class", "android.widget.ImageView",
                "clickable", "true",
                "longClickable", "true"));
        Hit editText = Hit.field("amount", lookup, Map.of("class", "android.widget.EditText"));
        lookup.handler = by -> textLocator(by) ? List.of(editText) : List.of(image);

        SmartLocator.accessibleThenText("Amount", "Enter Amount", "Enter Amount")
                .type(session(lookup, 300), "100");

        Assert.assertNull(image.typed);
        Assert.assertEquals(editText.typed, "100");
        Assert.assertEquals(image.clicks, 0);
        Assert.assertFalse(image.attributeNames.contains("longClickable"), image.attributeNames.toString());
        Assert.assertFalse(editText.attributeNames.contains("longClickable"), editText.attributeNames.toString());
        Assert.assertEquals(lookup.calls, 2, lookup.order.toString());
        Assert.assertEquals(lookup.findsAtInteraction, 2);
        Assert.assertTrue(accessibility(lookup.order.get(0)), lookup.order.toString());
        Assert.assertTrue(textLocator(lookup.order.get(1)), lookup.order.toString());
    }

    @Test(timeOut = 5000)
    public void nonEditableMatchFailsWhenNoFieldIsFound() {
        RecordingLookup lookup = new RecordingLookup();
        Hit label = Hit.field("label", lookup, Map.of("class", "android.view.View"));
        lookup.handler = by -> List.of(label);

        try {
            SmartLocator.accessibleThenText("Amount", "Enter Amount", "Enter Amount")
                    .type(session(lookup, 120), "100");
            Assert.fail("A non-editable element must not receive text");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not editable"), expected.getMessage());
        }
        Assert.assertNull(label.typed);
    }

    @Test
    public void exactTextIsPlatformSpecific() {
        String android = PlatformLocator.exactText("Continue").toString();
        Assert.assertTrue(android.contains("text(\"Continue\")"), android);
        String escaped = PlatformLocator.exactText("Say \"Hi\"").toString();
        Assert.assertTrue(escaped.contains("\\\"Hi\\\""), escaped);

        System.setProperty("platform", "ios");
        try {
            String ios = PlatformLocator.exactText("Continue").toString();
            Assert.assertTrue(ios.contains("name == \"Continue\""), ios);
            Assert.assertTrue(ios.contains("label == \"Continue\""), ios);
            Assert.assertTrue(ios.contains("value == \"Continue\""), ios);
            Assert.assertFalse(ios.toUpperCase().contains("CONTAINS"), ios);
        } finally {
            System.setProperty("platform", "android");
        }

        try {
            PlatformLocator.exactText("  ");
            Assert.fail("Blank exact text must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("Exact text"));
        }

        String slashed = PlatformLocator.text("a\\b").toString();
        Assert.assertTrue(slashed.contains("a\\\\b"), slashed);
        try {
            PlatformLocator.exactText("line\nbreak");
            Assert.fail("A new line must not be embedded in a text selector");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("new line"));
        }
    }

    @Test(timeOut = 5000)
    public void exactTextUsesThePlatformAtResolutionTime() {
        SmartLocator locator = SmartLocator.accessibleThenText("Continue", "Continue", "Continue");
        System.setProperty("platform", "ios");
        RecordingLookup lookup = new RecordingLookup();
        try {
            locator.click(session(lookup, 80));
            Assert.fail("Both candidates miss");
        } catch (StepFailureException expected) {
            Assert.assertTrue(lookup.order.get(1).contains("name =="), lookup.order.get(1));
            Assert.assertFalse(lookup.order.get(1).contains("UiSelector"), lookup.order.get(1));
        } finally {
            System.setProperty("platform", "android");
        }
    }

    @Test(timeOut = 5000)
    public void hiddenDuplicateDoesNotBlockTheVisibleElement() {
        RecordingLookup lookup = new RecordingLookup();
        Hit hidden = Hit.clickable("hidden", lookup);
        hidden.displayed = false;
        Hit visible = Hit.clickable("visible", lookup);
        lookup.handler = by -> textLocator(by) ? List.of(hidden, visible) : List.of();

        continueButton().click(session(lookup, 300));

        Assert.assertEquals(visible.clicks, 1);
        Assert.assertEquals(hidden.clicks, 0);
    }

    @Test(timeOut = 5000)
    public void hiddenAccessibilityElementWaitsInsteadOfUsingText() {
        RecordingLookup lookup = new RecordingLookup();
        Hit gate = Hit.clickable("continue", lookup);
        gate.displayed = false;
        int[] attempts = {0};
        lookup.handler = by -> {
            if (!accessibility(by)) {
                throw new AssertionError("Text was used while an accessibility element exists: " + by);
            }
            attempts[0]++;
            if (attempts[0] >= 3) {
                gate.displayed = true;
            }
            return List.of(gate);
        };

        continueButton().click(session(lookup, 400));

        Assert.assertEquals(gate.clicks, 1);
        Assert.assertTrue(attempts[0] >= 3);
    }

    @Test(timeOut = 5000)
    public void disabledAccessibilityElementWaitsInsteadOfUsingText() {
        RecordingLookup lookup = new RecordingLookup();
        Hit gate = Hit.clickable("continue", lookup);
        gate.enabled = false;
        int[] attempts = {0};
        lookup.handler = by -> {
            if (!accessibility(by)) {
                throw new AssertionError("Text was used while an accessibility element exists: " + by);
            }
            attempts[0]++;
            if (attempts[0] >= 3) {
                gate.enabled = true;
            }
            return List.of(gate);
        };

        continueButton().click(session(lookup, 400));

        Assert.assertEquals(gate.clicks, 1);
        Assert.assertTrue(attempts[0] >= 3);
    }

    @Test(timeOut = 5000)
    public void secureFieldRejectsClearAndSendKeys() {
        RecordingLookup lookup = new RecordingLookup();
        Hit pin = Hit.field("pin", lookup, Map.of(
                "class", "android.widget.EditText",
                "password", "true"));
        lookup.handler = by -> accessibility(by) ? List.of(pin) : List.of(Hit.field("other", lookup,
                Map.of("class", "android.widget.EditText")));
        SmartLocator field = SmartLocator.accessibleThenText("PIN", "PIN", "PIN");

        try {
            field.type(session(lookup, 300), "1234");
            Assert.fail("sendKeys must not run on a secure field");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("enterDigits"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("1234"), expected.getMessage());
        }
        Assert.assertNull(pin.typed);
        Assert.assertEquals(pin.clearCount, 0);
        Assert.assertEquals(pin.clicks, 0);
        Assert.assertEquals(lookup.calls, 1, lookup.order.toString());

        try {
            field.clear(session(lookup, 300));
            Assert.fail("clear must not run on a secure field");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("enterDigits"), expected.getMessage());
        }
        Assert.assertEquals(pin.clearCount, 0);
    }

    @Test(timeOut = 5000)
    public void enterDigitsFocusesOnceAndDoesNotSendKeys() {
        RecordingLookup lookup = new RecordingLookup();
        Hit pin = Hit.field("pin", lookup, Map.of(
                "class", "android.widget.EditText",
                "password", "true"));
        lookup.handler = by -> accessibility(by) ? List.of(pin) : List.of();
        List<String> entered = new ArrayList<>();

        SmartLocator.accessibleThenText("PIN", "PIN", "PIN")
                .enterDigits(session(lookup, 300, entered::add), "1234");

        Assert.assertEquals(pin.clicks, 1);
        Assert.assertNull(pin.typed);
        Assert.assertEquals(pin.clearCount, 0);
        Assert.assertEquals(pin.textReads, 0);
        Assert.assertEquals(entered, List.of("1234"));
        Assert.assertEquals(lookup.calls, 1, lookup.order.toString());
    }

    @Test(timeOut = 5000)
    public void digitEntryRequiresAnExplicitOptInWhenTheFieldIsNotVerified() {
        RecordingLookup lookup = new RecordingLookup();
        Hit view = Hit.field("otp", lookup, Map.of(
                "class", "android.view.View",
                "clickable", "true",
                "longClickable", "true"));
        lookup.handler = by -> accessibility(by) ? List.of(view) : List.of();
        List<String> entered = new ArrayList<>();
        SmartLocator otp = SmartLocator.accessibleThenText("OTP", "OTP", "OTP");

        try {
            otp.enterDigits(session(lookup, 120, entered::add), "1234");
            Assert.fail("An unverified view must not receive digits");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not a verified digit field"), expected.getMessage());
        }
        Assert.assertEquals(view.clicks, 0);
        Assert.assertTrue(entered.isEmpty());

        otp.forDigitEntry().enterDigits(session(lookup, 300, entered::add), "1234");
        Assert.assertEquals(view.clicks, 1);
        Assert.assertNull(view.typed);
        Assert.assertEquals(entered, List.of("1234"));
    }

    @Test(timeOut = 5000)
    public void editableOptInTypesAVerifiedNonSecureField() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = Hit.field("note", lookup, Map.of("class", "android.view.View"));
        lookup.handler = by -> accessibility(by) ? List.of(field) : List.of();

        SmartLocator.accessibleThenText("Note", "Note", "Note")
                .asEditableField()
                .type(session(lookup, 300), "Ama");

        Assert.assertEquals(field.typed, "Ama");
        Assert.assertEquals(lookup.calls, 1, lookup.order.toString());
    }

    @Test(timeOut = 5000)
    public void textVerificationRedactsDigitsAndReportsLetters() {
        RecordingLookup lookup = new RecordingLookup();
        Hit amount = Hit.field("amount", lookup, Map.of("class", "android.widget.EditText"));
        amount.shown = "999";
        lookup.handler = by -> accessibility(by) ? List.of(amount) : List.of();

        try {
            SmartLocator.accessibleThenText("Amount", "Amount", "Amount").type(session(lookup, 300), "100");
            Assert.fail("A mismatched amount must fail");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not shown"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("100"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("999"), expected.getMessage());
        }

        Hit name = Hit.field("name", lookup, Map.of("class", "android.widget.EditText"));
        name.shown = "Nope";
        lookup.handler = by -> accessibility(by) ? List.of(name) : List.of();
        try {
            SmartLocator.accessibleThenText("Name", "Name", "Name").type(session(lookup, 300), "Ama");
            Assert.fail("Mismatched text must fail");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("Ama"), expected.getMessage());
            Assert.assertTrue(expected.getMessage().contains("Nope"), expected.getMessage());
        }
    }

    @Test
    public void diagnosticsRedactSecrets() {
        String redacted = SmartLocator.redactDiagnostics(
                "password=\"1234\" pin=\"9876\" balance 1393 phone 55500001");
        Assert.assertFalse(redacted.contains("1234"), redacted);
        Assert.assertFalse(redacted.contains("9876"), redacted);
        Assert.assertFalse(redacted.contains("1393"), redacted);
        Assert.assertFalse(redacted.contains("55500001"), redacted);
        Assert.assertTrue(redacted.contains("password=\"***\""), redacted);
    }

    @Test(timeOut = 5000)
    public void formattedAmountUsesNormalizedVerification() {
        RecordingLookup lookup = new RecordingLookup();
        Hit amount = Hit.field("amount", lookup, Map.of("class", "android.widget.EditText"));
        amount.shown = "100 Unit";
        lookup.handler = by -> accessibility(by) ? List.of(amount) : List.of();
        SmartLocator field = SmartLocator.accessibleThenText("Amount", "Amount", "Amount");

        try {
            field.type(session(lookup, 300), "100");
            Assert.fail("Exact verification must reject a formatted amount");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not shown"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("100"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("Unit"), expected.getMessage());
        }

        field.verifyNormalized().type(session(lookup, 300), "100");
        Assert.assertEquals(amount.typed, "100");

        Hit grouped = Hit.field("grouped", lookup, Map.of("class", "android.widget.EditText"));
        grouped.shown = "1 000";
        lookup.handler = by -> accessibility(by) ? List.of(grouped) : List.of();
        SmartLocator.accessibleThenText("Grouped", "Grouped", "Grouped")
                .verifyNormalized()
                .type(session(lookup, 300), "1000");
        Assert.assertEquals(grouped.typed, "1000");
    }

    @Test(timeOut = 5000)
    public void unreadableValueIsNotTreatedAsVerified() {
        RecordingLookup lookup = new RecordingLookup();
        Hit masked = Hit.field("masked", lookup, Map.of("class", "android.widget.EditText"));
        masked.shown = "****";
        lookup.handler = by -> accessibility(by) ? List.of(masked) : List.of();
        SmartLocator field = SmartLocator.accessibleThenText("Note", "Note", "Note");

        try {
            field.type(session(lookup, 300), "Ama");
            Assert.fail("A mask must not count as the entered text");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not exposed"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("Ama"), expected.getMessage());
        }
        Assert.assertTrue(masked.textReads > 0);

        Hit blank = Hit.field("blank", lookup, Map.of("class", "android.widget.EditText"));
        blank.shown = "";
        lookup.handler = by -> accessibility(by) ? List.of(blank) : List.of();
        blank.textReads = 0;
        field.withoutVerification().type(session(lookup, 300), "Ama");
        Assert.assertEquals(blank.typed, "Ama");
        Assert.assertEquals(blank.textReads, 0);
    }

    @Test(timeOut = 5000)
    public void nonSecureDigitEntryChecksAnExposedValue() {
        RecordingLookup lookup = new RecordingLookup();
        Hit amount = Hit.field("amount", lookup, Map.of("class", "android.widget.EditText"));
        amount.shown = "100 Unit";
        lookup.handler = by -> accessibility(by) ? List.of(amount) : List.of();
        List<String> entered = new ArrayList<>();
        SmartLocator field = SmartLocator.accessibleThenText("Amount", "Amount", "Amount");

        field.enterDigits(session(lookup, 300, entered::add), "100");
        Assert.assertEquals(entered, List.of("100"));
        Assert.assertTrue(amount.textReads > 0);
        Assert.assertNull(amount.typed);

        Hit wrong = Hit.field("wrong", lookup, Map.of("class", "android.widget.EditText"));
        wrong.shown = "99 Unit";
        lookup.handler = by -> accessibility(by) ? List.of(wrong) : List.of();
        try {
            field.enterDigits(session(lookup, 300, entered::add), "100");
            Assert.fail("Exposed digits that differ must fail");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not shown"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("100"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("99"), expected.getMessage());
        }

        Hit hidden = Hit.field("hidden-value", lookup, Map.of("class", "android.widget.EditText"));
        hidden.shown = "";
        lookup.handler = by -> accessibility(by) ? List.of(hidden) : List.of();
        int before = entered.size();
        field.enterDigits(session(lookup, 300, entered::add), "100");
        Assert.assertEquals(entered.size(), before + 1);
        Assert.assertTrue(hidden.textReads > 0);
    }

    @Test(timeOut = 5000)
    public void secureDigitEntryDoesNotReadOrLogTheValue() {
        RecordingLookup lookup = new RecordingLookup();
        Hit pin = Hit.field("pin", lookup, Map.of(
                "class", "android.widget.EditText",
                "password", "true"));
        pin.shown = "1234";
        lookup.handler = by -> accessibility(by) ? List.of(pin) : List.of();
        List<String> entered = new ArrayList<>();

        SmartLocator.accessibleThenText("PIN", "PIN", "PIN")
                .enterDigits(session(lookup, 300, entered::add), "1234");

        Assert.assertEquals(pin.textReads, 0);
        Assert.assertEquals(pin.clicks, 1);
        Assert.assertNull(pin.typed);
        Assert.assertEquals(pin.clearCount, 0);
        Assert.assertEquals(entered, List.of("1234"));
    }

    @Test(timeOut = 5000)
    public void locatorFailureHidesSupplierExceptionText() {
        SmartLocator locator = SmartLocator.fromSource("Recipient", () -> {
            throw new IllegalStateException("55500001");
        });
        RecordingLookup lookup = new RecordingLookup();
        try {
            locator.click(session(lookup, 80));
            Assert.fail("A broken locator list must fail the step");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("unavailable"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("55500001"), expected.getMessage());
        }
    }

    @Test(timeOut = 5000)
    public void unsupportedEditableAttributeFallsThroughToTheNativeClass() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = Hit.field("amount", lookup, Map.of("class", "android.widget.EditText"));
        field.rejectedAttribute = "editable";
        lookup.handler = by -> accessibility(by) ? List.of(field) : List.of();

        SmartLocator.accessibleThenText("Amount", "Amount", null).type(session(lookup, 300), "100");

        Assert.assertEquals(field.typed, "100");
        Assert.assertTrue(field.attributeNames.contains("editable"), field.attributeNames.toString());
    }

    @Test(timeOut = 5000)
    public void unsupportedEditableAttributeDoesNotMakeAViewEditable() {
        RecordingLookup lookup = new RecordingLookup();
        Hit label = Hit.field("search", lookup, Map.of("class", "android.view.View"));
        label.rejectedAttribute = "editable";
        lookup.handler = by -> accessibility(by) ? List.of(label) : List.of();

        try {
            SmartLocator.accessibleThenText("Search field", "Search", null)
                    .type(session(lookup, 160), "Sample");
            Assert.fail("A view with no editable attribute must not receive text");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not editable"), expected.getMessage());
        }
        Assert.assertNull(label.typed);
    }

    @Test(timeOut = 5000)
    public void unrelatedUnsupportedCommandIsNotSwallowed() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = Hit.field("pin", lookup, Map.of("class", "android.widget.EditText"));
        field.rejectedAttribute = "password";
        field.rejectedMessage = "session not created";
        lookup.handler = by -> accessibility(by) ? List.of(field) : List.of();

        try {
            SmartLocator.accessibleThenText("PIN", "PIN", null).type(session(lookup, 300), "1234");
            Assert.fail("An unrelated driver error must not be treated as a missing attribute");
        } catch (UnsupportedCommandException expected) {
            Assert.assertTrue(expected.getMessage().contains("session not created"), expected.getMessage());
        }
        Assert.assertNull(field.typed);
    }

    @Test(timeOut = 5000)
    public void unknownPasswordAttributeIsNotTreatedAsAbsent() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = Hit.field("pin", lookup, Map.of("class", "android.widget.EditText"));
        field.rejectedAttribute = "password";
        field.rejectedMessage = "'password' attribute is unknown for the element.";
        lookup.handler = by -> accessibility(by) ? List.of(field) : List.of();

        try {
            SmartLocator.accessibleThenText("PIN", "PIN", null).type(session(lookup, 300), "1234");
            Assert.fail("A missing password attribute must not allow sendKeys");
        } catch (UnsupportedCommandException expected) {
            Assert.assertTrue(expected.getMessage().contains("password"), expected.getMessage());
        }
        Assert.assertNull(field.typed);
    }

    @Test(timeOut = 5000)
    public void androidPriorityUsesResourceIdBeforeTextAndSkipsDuplicateContentDesc() {
        RecordingLookup lookup = new RecordingLookup();
        lookup.handler = by -> List.of();
        By description = PlatformLocator.android("new UiSelector().description(\"Search\")");
        By scoped = PlatformLocator.android(
                "new UiSelector().className(\"android.view.View\").description(\"Search\")");
        By xpath = By.xpath("//android.view.View[@resource-id='section_field_search']");
        SmartLocator field = SmartLocator.accessibleThenText(
                "Search field", "Search", "Search", xpath, description,
                PlatformLocator.id("section_field_search"), scoped);
        try {
            field.click(session(lookup, 180));
            Assert.fail("Every candidate misses");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("cycle"), expected.getMessage());
        }
        Assert.assertTrue(lookup.order.size() >= 5, lookup.order.toString());
        Assert.assertTrue(accessibility(lookup.order.get(0)), lookup.order.get(0));
        Assert.assertTrue(lookup.order.get(1).contains("section_field_search")
                && !lookup.order.get(1).toLowerCase().contains("xpath"), lookup.order.get(1));
        Assert.assertTrue(lookup.order.get(2).contains("className"), lookup.order.get(2));
        Assert.assertTrue(textLocator(lookup.order.get(3)), lookup.order.get(3));
        Assert.assertTrue(lookup.order.get(4).toLowerCase().contains("xpath"), lookup.order.get(4));
        Assert.assertTrue(lookup.order.subList(0, 5).stream()
                .noneMatch(value -> value.contains(".description(\"Search\")") && !value.contains("className")),
                lookup.order.toString());
    }

    @Test(timeOut = 5000)
    public void resourceIdResolvesWhenAccessibilityIdIsAmbiguous() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = Hit.clickable("search-field", lookup);
        lookup.handler = by -> {
            if (accessibility(by)) {
                return List.of(Hit.clickable("back", lookup), Hit.clickable("icon", lookup));
            }
            if (String.valueOf(by).contains("section_field_search")) {
                return List.of(field);
            }
            return List.of();
        };

        SmartLocator.verified("Search field", "Search", "section_field_search", null, null, null)
                .click(session(lookup, 300));

        Assert.assertEquals(field.clicks, 1);
        Assert.assertTrue(lookup.order.stream().noneMatch(SmartLocatorTest::textLocator), lookup.order.toString());
    }

    @Test(timeOut = 5000)
    public void iosUsesLabelsAndOmitsAndroidResourceIds() {
        System.setProperty("platform", "ios");
        RecordingLookup lookup = new RecordingLookup();
        Hit field = Hit.clickable("search", lookup);
        lookup.handler = by -> String.valueOf(by).contains("name ==") ? List.of(field) : List.of();
        try {
            SmartLocator.verified("Search field", "Search", "section_field_search", null, "Search", null)
                    .click(session(lookup, 300));
        } finally {
            System.setProperty("platform", "android");
        }
        Assert.assertEquals(field.clicks, 1);
        Assert.assertTrue(accessibility(lookup.order.get(0)), lookup.order.toString());
        Assert.assertTrue(lookup.order.get(1).contains("name =="), lookup.order.toString());
        Assert.assertTrue(lookup.order.stream().noneMatch(value -> value.contains("section_field_search")),
                lookup.order.toString());
    }

    @Test(timeOut = 5000)
    public void valueWithLabelAcceptsEnteredValueAndKnownSuffix() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = described(lookup, "Sample, Search");
        lookup.handler = by -> List.of(field);

        searchField().asEditableField().verifyValueWithLabel("Search").type(session(lookup, 300), "Sample");

        Assert.assertEquals(field.typed, "Sample");
        Assert.assertEquals(field.textReads, 0);
        Assert.assertTrue(field.attributeNames.contains("content-desc"), field.attributeNames.toString());
    }

    @Test(timeOut = 5000)
    public void valueWithLabelAcceptsAValueThatItselfContainsTheDelimiter() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = described(lookup, "A, B, Search");
        lookup.handler = by -> List.of(field);

        searchField().verifyValueWithLabel("Search").asEditableField().type(session(lookup, 300), "A, B");

        Assert.assertEquals(field.typed, "A, B");
        Assert.assertEquals(field.textReads, 0);
    }

    @Test(timeOut = 5000)
    public void valueWithLabelRejectsMalformedDescriptions() {
        String[] descriptions = {
                "Sample,Search",
                "Sample Search",
                "Sample,  Search",
                ", Search",
                "Search",
                "Mi, Search",
                "Sample, search",
                "Sample, Find",
                "Search, Sample",
                "xxSample, Search",
                "Sample, Search ",
                null
        };
        for (String description : descriptions) {
            RecordingLookup lookup = new RecordingLookup();
            Hit field = described(lookup, description);
            lookup.handler = by -> List.of(field);
            try {
                searchField().asEditableField().verifyValueWithLabel("Search")
                        .type(session(lookup, 300), "Sample");
                Assert.fail("Description must be rejected: " + description);
            } catch (StepFailureException expected) {
                Assert.assertTrue(expected.getMessage().contains("not shown")
                        || expected.getMessage().contains("not exposed"), expected.getMessage());
            }
        }
    }

    @Test(timeOut = 5000)
    public void exactAndNormalizedVerificationDoNotAcceptTheCombinedDescription() {
        RecordingLookup exactLookup = new RecordingLookup();
        Hit exact = described(exactLookup, "Sample, Search");
        exact.shown = "Sample, Search";
        exactLookup.handler = by -> List.of(exact);
        try {
            searchField().asEditableField().type(session(exactLookup, 300), "Sample");
            Assert.fail("Exact verification must keep requiring an equal value");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("Sample, Search"), expected.getMessage());
        }

        RecordingLookup normalizedLookup = new RecordingLookup();
        Hit normalized = described(normalizedLookup, "Sample, Search");
        normalized.shown = "Sample, Search";
        normalizedLookup.handler = by -> List.of(normalized);
        try {
            searchField().asEditableField().verifyNormalized().type(session(normalizedLookup, 300), "Sample");
            Assert.fail("Normalized verification must not accept a combined content-desc");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("not shown")
                    || expected.getMessage().contains("not exposed"), expected.getMessage());
        }
    }

    @Test(timeOut = 5000)
    public void valueWithLabelHidesMismatchedDigits() {
        RecordingLookup lookup = new RecordingLookup();
        Hit field = described(lookup, "9999, Search");
        lookup.handler = by -> List.of(field);
        try {
            searchField().asEditableField().verifyValueWithLabel("Search").type(session(lookup, 300), "1000");
            Assert.fail("A different value must fail");
        } catch (StepFailureException expected) {
            Assert.assertFalse(expected.getMessage().contains("1000"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("9999"), expected.getMessage());
            Assert.assertTrue(expected.getMessage().contains("digits"), expected.getMessage());
        }
    }

    @Test(timeOut = 5000)
    public void valueWithLabelDoesNotSendKeysOrReadASecureField() {
        RecordingLookup lookup = new RecordingLookup();
        Hit pin = Hit.field("pin", lookup, Map.of(
                "class", "android.widget.EditText",
                "password", "true",
                "content-desc", "1234, PIN"));
        lookup.handler = by -> List.of(pin);
        try {
            SmartLocator.accessibleThenText("PIN", "PIN", null)
                    .asEditableField()
                    .verifyValueWithLabel("PIN")
                    .type(session(lookup, 300), "1234");
            Assert.fail("A secure field must not accept sendKeys");
        } catch (StepFailureException expected) {
            Assert.assertTrue(expected.getMessage().contains("sendKeys"), expected.getMessage());
            Assert.assertFalse(expected.getMessage().contains("1234"), expected.getMessage());
        }
        Assert.assertNull(pin.typed);
        Assert.assertEquals(pin.clearCount, 0);
        Assert.assertFalse(pin.attributeNames.contains("content-desc"), pin.attributeNames.toString());

        List<String> entered = new ArrayList<>();
        SmartLocator.accessibleThenText("PIN", "PIN", null)
                .verifyValueWithLabel("PIN")
                .enterDigits(session(lookup, 300, entered::add), "1234");
        Assert.assertEquals(pin.textReads, 0);
        Assert.assertEquals(pin.clicks, 1);
        Assert.assertNull(pin.typed);
        Assert.assertFalse(pin.attributeNames.contains("content-desc"), pin.attributeNames.toString());
    }

    @Test(timeOut = 5000)
    public void valueWithLabelRequiresAPlainLabel() {
        try {
            searchField().verifyValueWithLabel(" ");
            Assert.fail("A blank label must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("label"), expected.getMessage());
        }
        try {
            searchField().verifyValueWithLabel("Search, Box");
            Assert.fail("A label containing the delimiter must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("label"), expected.getMessage());
        }
    }

    private static SmartLocator searchField() {
        return SmartLocator.verified("Search field", null, "section_field_search", null, null, null);
    }

    private static Hit described(RecordingLookup lookup, String contentDesc) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("class", "android.view.View");
        if (contentDesc != null) {
            attributes.put("content-desc", contentDesc);
        }
        return Hit.field("search", lookup, attributes);
    }

    private static SmartLocator continueButton() {
        return SmartLocator.accessibleThenText("Continue", "Continue", "Continue");
    }

    private static SmartLocator.ResolutionSession session(RecordingLookup lookup, long timeoutMs) {
        return SmartLocator.ResolutionSession.of(lookup, Duration.ofMillis(timeoutMs), Duration.ofMillis(20));
    }

    private static SmartLocator.ResolutionSession session(RecordingLookup lookup, long timeoutMs,
                                                          SmartLocator.DigitEntry digits) {
        return SmartLocator.ResolutionSession.of(
                lookup, Duration.ofMillis(timeoutMs), Duration.ofMillis(20), digits);
    }

    private static boolean accessibility(String value) {
        return value.contains("accessibilityId");
    }

    private static boolean accessibility(By locator) {
        return accessibility(String.valueOf(locator));
    }

    private static boolean textLocator(String value) {
        return value.contains("text(");
    }

    private static boolean textLocator(By locator) {
        return textLocator(String.valueOf(locator));
    }

    private static long count(List<String> values, String token) {
        return values.stream().filter(value -> value.contains(token)).count();
    }

    private static final class RecordingLookup implements SmartLocator.Lookup {
        private Function<By, List<SmartLocator.CandidateHit>> handler = by -> List.of();
        private Function<SmartLocator.CandidateHit, List<SmartLocator.CandidateHit>> withinHandler = parent -> List.of();
        private final List<String> order = new ArrayList<>();
        private final List<Long> times = new ArrayList<>();
        private final List<String> containers = new ArrayList<>();
        private int calls;
        private int findsAtInteraction = -1;
        private int scopeDepth;
        private int scopeEntries;

        @Override
        public void runScoped(Runnable action) {
            scopeEntries++;
            scopeDepth++;
            try {
                action.run();
            } finally {
                scopeDepth--;
            }
        }

        @Override
        public List<SmartLocator.CandidateHit> find(By locator) {
            if (scopeDepth != 1) {
                throw new AssertionError("Lookup ran outside the single candidate scope");
            }
            calls++;
            order.add(String.valueOf(locator));
            times.add(System.nanoTime());
            return handler.apply(locator);
        }

        @Override
        public List<SmartLocator.CandidateHit> findWithin(SmartLocator.CandidateHit parent, By locator) {
            if (scopeDepth != 1) {
                throw new AssertionError("Lookup ran outside the single candidate scope");
            }
            containers.add(parent instanceof Hit ? ((Hit) parent).id : String.valueOf(parent));
            return withinHandler.apply(parent);
        }
    }

    private static final class Hit implements SmartLocator.CandidateHit {
        private final String id;
        private final RecordingLookup lookup;
        private boolean displayed;
        private boolean enabled;
        private final Map<String, String> attributes;
        private final List<String> attributeNames = new ArrayList<>();
        private int clicks;
        private int clearCount;
        private int textReads;
        private String typed;
        private String shown;
        private boolean failClickOnce;
        private String rejectedAttribute;
        private String rejectedMessage = "'editable' attribute is unknown for the element.";

        private Hit(String id, RecordingLookup lookup, boolean displayed, boolean enabled,
                    Map<String, String> attributes) {
            this.id = id;
            this.lookup = lookup;
            this.displayed = displayed;
            this.enabled = enabled;
            this.attributes = attributes;
        }

        private static Hit clickable(String id, RecordingLookup lookup) {
            return new Hit(id, lookup, true, true, Map.of());
        }

        private static Hit field(String id, RecordingLookup lookup, Map<String, String> attributes) {
            return new Hit(id, lookup, true, true, attributes);
        }

        @Override
        public boolean displayed() {
            return displayed;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String attribute(String name) {
            attributeNames.add(name);
            if (name.equals(rejectedAttribute)) {
                throw new UnsupportedCommandException(rejectedMessage);
            }
            return attributes.get(name);
        }

        @Override
        public String displayedText() {
            textReads++;
            return shown != null ? shown : typed;
        }

        @Override
        public void click() {
            if (failClickOnce) {
                failClickOnce = false;
                throw new StaleElementReferenceException(id);
            }
            markInteraction();
            clicks++;
        }

        @Override
        public void type(String text) {
            markInteraction();
            this.typed = text;
        }

        @Override
        public void clear() {
            markInteraction();
            clearCount++;
            this.typed = "";
        }

        @Override
        public WebElement element() {
            return null;
        }

        private void markInteraction() {
            if (lookup != null) {
                lookup.findsAtInteraction = lookup.calls;
            }
        }
    }
}
