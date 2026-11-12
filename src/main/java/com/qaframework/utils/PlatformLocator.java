package com.qaframework.utils;

import com.qaframework.config.ConfigReader;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/**
 * Helper for defining platform-specific locators in page objects.
 */
public final class PlatformLocator {

    private PlatformLocator() {
    }

    public static By android(String uiAutomator) {
        return AppiumBy.androidUIAutomator(uiAutomator);
    }

    public static By ios(String iOSClassChain) {
        return AppiumBy.iOSClassChain(iOSClassChain);
    }

    public static By accessibilityId(String id) {
        return AppiumBy.accessibilityId(id);
    }

    public static By id(String id) {
        return AppiumBy.id(id);
    }

    /**
     * Exact Android {@code resource-id}. This is the form exposed by the Flutter release build.
     */
    public static By resourceId(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("Resource id is required");
        }
        return android("new UiSelector().resourceId(\"" + escapeQuoted(resourceId) + "\")");
    }

    public static By text(String value) {
        rejectBrokenText(value);
        return AppiumBy.androidUIAutomator("new UiSelector().text(\"" + escapeQuoted(value) + "\")");
    }

    /**
     * Exact visible text for the platform selected when the locator is evaluated.
     * Android uses {@code UiSelector.text}. iOS matches {@code name}, {@code label}, or {@code value}.
     * Partial text is not used.
     */
    public static By exactText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Exact text is required");
        }
        rejectBrokenText(value);
        if (ConfigReader.isAndroid()) {
            return text(value);
        }
        String quoted = escapeQuoted(value);
        return AppiumBy.iOSNsPredicateString(
                "name == \"" + quoted + "\" OR label == \"" + quoted + "\" OR value == \"" + quoted + "\"");
    }

    private static void rejectBrokenText(String value) {
        if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
            throw new IllegalArgumentException("Exact text cannot contain a new line");
        }
    }

    /** Escapes backslash first, then quotes, so the selector string is not closed early. */
    private static String escapeQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Returns Android or iOS locator based on current platform from config.
     */
    public static By forPlatform(By androidLocator, By iosLocator) {
        return ConfigReader.isAndroid() ? androidLocator : iosLocator;
    }
}
