package com.qaframework.utils;

import com.qaframework.config.ConfigReader;
import com.qaframework.drivers.DriverManager;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.AppiumDriver;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Pause;
import org.openqa.selenium.interactions.PointerInput;
import org.openqa.selenium.interactions.Sequence;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic mobile automation actions - click, scroll, swipe, tap, screenshots, etc.
 */
public final class MobileActions {

    private static final Logger log = LoggerManager.getLogger(MobileActions.class);
    private static String screenshotDir() {
        return ConfigReader.get("stepScreenshotDir", "reports/images");
    }

    private MobileActions() {
    }

    /**
     * Brings the configured app to the foreground without force-stopping it.
     * {@code mobile: activateApp} uses {@code FLAG_ACTIVITY_RESET_TASK_IF_NEEDED}, which
     * restarts the Android task. {@code stop: false} omits {@code am start -S}.
     */
    public static void launchApplication() {
        String appId = applicationId();
        if (appId.isBlank()) {
            log.warn("No app id configured; skipping launchApplication");
            return;
        }
        if (isInForeground(appId)) {
            log.info("App already in foreground: {}", appId);
            return;
        }
        if (ConfigReader.isAndroid()) {
            Map<String, Object> params = new HashMap<>();
            params.put("component", appId + "/" + ConfigReader.get("androidAppActivity"));
            params.put("action", "android.intent.action.MAIN");
            params.put("categories", "android.intent.category.LAUNCHER");
            params.put("flags", "0x10000000");
            params.put("stop", false);
            getDriver().executeScript("mobile: startActivity", params);
            log.info("Brought app to foreground without stopping it: {}", appId);
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("appId", appId);
        getDriver().executeScript("mobile: activateApp", params);
        log.info("Activated app: {}", appId);
    }

    /** Force-stops the app. Called once, after the suite has finished. */
    public static void terminateApplication() {
        String appId = applicationId();
        if (appId.isBlank() || !DriverManager.hasDriver()) {
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("appId", appId);
        getDriver().executeScript("mobile: terminateApp", params);
        log.info("Terminated app: {}", appId);
    }

    private static String applicationId() {
        return ConfigReader.isAndroid()
                ? ConfigReader.get("androidAppPackage")
                : ConfigReader.get("iosBundleId");
    }

    private static boolean isInForeground(String appId) {
        try {
            Object state = getDriver().executeScript("mobile: queryAppState", Map.of("appId", appId));
            return state instanceof Number && ((Number) state).intValue() == 4;
        } catch (RuntimeException e) {
            log.warn("Could not read app state for {}: {}", appId, e.getMessage());
            return false;
        }
    }

    /** One wait: clickable already means visible and enabled. Click that element, do not search again. */
    public static void click(By locator) {
        log.info("Clicking element: {}", locator);
        WaitUtils.waitForClickability(locator).click();
    }

    /** One wait for an element that was already located. */
    public static void click(WebElement element) {
        log.info("Clicking element");
        WaitUtils.waitForClickability(element).click();
    }

    /**
     * Types into the already focused field. The session is an {@link AppiumDriver}, and Flutter
     * password fields reject {@code sendKeys}. Android digits go through {@code mobile: pressKey}
     * (KEYCODE_0 is 7).
     */
    public static void typeDigits(String digits) {
        if (digits == null || !digits.chars().allMatch(Character::isDigit) || digits.isEmpty()) {
            throw new IllegalArgumentException("typeDigits requires one or more digits");
        }
        if (ConfigReader.isAndroid()) {
            log.info("Typing {} digit(s) with key events", digits.length());
            for (int i = 0; i < digits.length(); i++) {
                pressKeyCode(7 + (digits.charAt(i) - '0'));
            }
            return;
        }
        log.info("Typing {} digit(s) into the active element", digits.length());
        getDriver().switchTo().activeElement().sendKeys(digits);
    }

    public static void deleteDigits(int count) {
        if (count <= 0 || !ConfigReader.isAndroid()) {
            return;
        }
        log.info("Deleting {} digit(s) from the focused field", count);
        for (int i = 0; i < count; i++) {
            pressKeyCode(67);
        }
    }

    private static void pressKeyCode(int keycode) {
        Map<String, Object> args = new HashMap<>();
        args.put("keycode", keycode);
        getDriver().executeScript("mobile: pressKey", args);
    }

    public static void sendKeys(By locator, String text) {
        log.info("Sending keys to: {}", locator);
        WebElement element = WaitUtils.waitForVisibility(locator);
        element.clear();
        element.sendKeys(text);
    }

    public static void sendKeys(WebElement element, String text) {
        WebElement visible = WaitUtils.waitForVisibility(element);
        visible.clear();
        visible.sendKeys(text);
    }

    public static String getText(By locator) {
        return WaitUtils.waitForVisibility(locator).getText();
    }

    public static String getText(WebElement element) {
        return WaitUtils.waitForVisibility(element).getText();
    }

    public static void clearField(By locator) {
        WebElement element = WaitUtils.waitForVisibility(locator);
        element.clear();
    }

    public static void clearField(WebElement element) {
        WaitUtils.waitForVisibility(element).clear();
    }

    public static String getAttribute(By locator, String attribute) {
        return WaitUtils.waitForPresence(locator).getAttribute(attribute);
    }

    public static boolean isDisplayed(By locator) {
        try {
            return WaitUtils.waitForVisibility(locator).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isDisplayed(WebElement element) {
        try {
            return element.isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }

    public static void hideKeyboard() {
        log.info("Hiding keyboard");
        AppiumDriver driver = getDriver();
        if (!isKeyboardShown(driver)) {
            return;
        }
        try {
            driver.executeScript("mobile: hideKeyboard");
        } catch (Exception e) {
            log.warn("mobile: hideKeyboard failed: {}", e.getMessage());
        }
        if (!isKeyboardShown(driver)) {
            return;
        }
        try {
            driver.findElement(AppiumBy.accessibilityId("Done")).click();
            log.info("Closed the keyboard with Done");
        } catch (Exception e) {
            log.warn("Keyboard still shown and Done was not available: {}", e.getMessage());
        }
    }

    private static boolean isKeyboardShown(AppiumDriver driver) {
        try {
            Object shown = driver.executeScript("mobile: isKeyboardShown");
            return Boolean.TRUE.equals(shown) || "true".equalsIgnoreCase(String.valueOf(shown));
        } catch (Exception e) {
            log.warn("Could not read keyboard state: {}", e.getMessage());
            return false;
        }
    }

    public static void scrollToText(String text) {
        log.info("Scrolling to text: {}", text);
        if (ConfigReader.isAndroid()) {
            getDriver().findElement(AppiumBy.androidUIAutomator(
                    "new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView("
                            + "new UiSelector().text(\"" + text + "\"));"));
        } else {
            scrollIOS();
        }
    }

    /** Flutter buttons use content-desc; swipe when UiScrollable is not available. */
    public static void scrollToAccessibilityId(String accessibilityId) {
        log.info("Scrolling to accessibility id: {}", accessibilityId);
        if (!ConfigReader.isAndroid()) {
            scrollIOS();
            return;
        }
        try {
            getDriver().findElement(AppiumBy.androidUIAutomator(
                    "new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView("
                            + "new UiSelector().description(\"" + accessibilityId + "\"));"));
        } catch (Exception e) {
            log.warn("UiScrollable did not find '{}'; swiping up: {}", accessibilityId, e.getMessage());
            swipeUp();
        }
    }

    public static void scrollIOS() {
        Dimension size = getDriver().manage().window().getSize();
        swipe(size.getWidth() / 2, (int) (size.getHeight() * 0.8),
                size.getWidth() / 2, (int) (size.getHeight() * 0.2), 800);
    }

    public static void swipeUp() {
        swipe(getStartEndPoints("up"));
    }

    public static void swipeDown() {
        swipe(getStartEndPoints("down"));
    }

    public static void swipeLeft() {
        swipe(getStartEndPoints("left"));
    }

    public static void swipeRight() {
        swipe(getStartEndPoints("right"));
    }

    public static void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        log.debug("Swiping from ({},{}) to ({},{})", startX, startY, endX, endY);
        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence swipe = new Sequence(finger, 1);
        swipe.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), startX, startY));
        swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        swipe.addAction(finger.createPointerMove(Duration.ofMillis(durationMs),
                PointerInput.Origin.viewport(), endX, endY));
        swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        getDriver().perform(Collections.singletonList(swipe));
    }

    public static void tap(int x, int y) {
        log.info("Tapping at coordinates ({}, {})", x, y);
        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence tap = new Sequence(finger, 1);
        tap.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, y));
        tap.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        tap.addAction(new Pause(finger, Duration.ofMillis(100)));
        tap.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        getDriver().perform(Collections.singletonList(tap));
    }

    public static void tap(WebElement element) {
        Point location = element.getLocation();
        Dimension size = element.getSize();
        tap(location.getX() + size.getWidth() / 2, location.getY() + size.getHeight() / 2);
    }

    public static void longPress(WebElement element, int durationMs) {
        log.info("Long pressing element for {} ms", durationMs);
        Point location = element.getLocation();
        Dimension size = element.getSize();
        int x = location.getX() + size.getWidth() / 2;
        int y = location.getY() + size.getHeight() / 2;

        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence longPress = new Sequence(finger, 1);
        longPress.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), x, y));
        longPress.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        longPress.addAction(new Pause(finger, Duration.ofMillis(durationMs)));
        longPress.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        getDriver().perform(Collections.singletonList(longPress));
    }

    public static void longPress(By locator, int durationMs) {
        longPress(WaitUtils.waitForVisibility(locator), durationMs);
    }

    public static void dragAndDrop(WebElement source, WebElement target) {
        log.info("Dragging element to target");
        Point sourcePoint = getCenter(source);
        Point targetPoint = getCenter(target);

        PointerInput finger = new PointerInput(PointerInput.Kind.TOUCH, "finger");
        Sequence drag = new Sequence(finger, 1);
        drag.addAction(finger.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(),
                sourcePoint.getX(), sourcePoint.getY()));
        drag.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()));
        drag.addAction(finger.createPointerMove(Duration.ofMillis(1000), PointerInput.Origin.viewport(),
                targetPoint.getX(), targetPoint.getY()));
        drag.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()));
        getDriver().perform(Collections.singletonList(drag));
    }

    public static void pressBack() {
        log.info("Pressing device back button");
        getDriver().navigate().back();
    }

    public static boolean waitUntilElementDisappears(By locator) {
        return WaitUtils.waitForInvisibility(locator);
    }

    public static String takeScreenshot(String testName) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = testName.replaceAll("[^a-zA-Z0-9-_]", "_") + "_" + timestamp + ".png";
        File destFile = new File(screenshotDir(), fileName);

        try {
            destFile.getParentFile().mkdirs();
            File srcFile = getDriver().getScreenshotAs(OutputType.FILE);
            FileUtils.copyFile(srcFile, destFile);
            log.info("Screenshot saved: {}", destFile.getAbsolutePath());
            return destFile.getAbsolutePath();
        } catch (Exception e) {
            // Dead UiAutomator2 / session errors are WebDriverException, not IOException.
            log.error("Failed to capture screenshot: {}", e.getMessage());
            return null;
        }
    }

    public static String takeScreenshot() {
        return takeScreenshot("screenshot");
    }

    private static Point getCenter(WebElement element) {
        Point location = element.getLocation();
        Dimension size = element.getSize();
        return new Point(location.getX() + size.getWidth() / 2, location.getY() + size.getHeight() / 2);
    }

    private static int[] getStartEndPoints(String direction) {
        Dimension size = getDriver().manage().window().getSize();
        int width = size.getWidth();
        int height = size.getHeight();
        int midX = width / 2;
        int midY = height / 2;

        switch (direction.toLowerCase()) {
            case "up":
                return new int[]{midX, (int) (height * 0.8), midX, (int) (height * 0.2)};
            case "down":
                return new int[]{midX, (int) (height * 0.2), midX, (int) (height * 0.8)};
            case "left":
                return new int[]{(int) (width * 0.8), midY, (int) (width * 0.2), midY};
            case "right":
                return new int[]{(int) (width * 0.2), midY, (int) (width * 0.8), midY};
            default:
                throw new IllegalArgumentException("Invalid swipe direction: " + direction);
        }
    }

    private static void swipe(int[] points) {
        swipe(points[0], points[1], points[2], points[3], 800);
    }

    private static AppiumDriver getDriver() {
        return DriverManager.getDriver();
    }
}
