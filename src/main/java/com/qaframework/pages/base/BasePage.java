package com.qaframework.pages.base;

import com.qaframework.drivers.DriverManager;
import com.qaframework.exceptions.StepFailureException;
import com.qaframework.utils.MobileActions;
import com.qaframework.utils.ScreenshotUtils;
import com.qaframework.utils.SmartLocator;
import com.qaframework.utils.WaitUtils;
import io.appium.java_client.AppiumDriver;
import io.appium.java_client.pagefactory.AppiumFieldDecorator;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.PageFactory;

import java.time.Duration;

/**
 * Base class for all Page Object classes.
 * Provides reusable interaction methods delegating to framework utilities.
 */
public abstract class BasePage {

    protected AppiumDriver driver;

    protected BasePage() {
        this.driver = DriverManager.getDriver();
        PageFactory.initElements(new AppiumFieldDecorator(driver, Duration.ofSeconds(10)), this);
    }

    protected BasePage(AppiumDriver driver) {
        this.driver = driver;
        PageFactory.initElements(new AppiumFieldDecorator(driver, Duration.ofSeconds(10)), this);
    }

    // --- Click actions ---

    protected void click(By locator) {
        MobileActions.click(locator);
    }

    protected void click(WebElement element) {
        MobileActions.click(element);
    }

    protected void click(String step, By locator) {
        try {
            MobileActions.click(locator);
            ScreenshotUtils.captureStep(step);
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, String.valueOf(locator), e.getMessage(), e);
        }
    }

    protected void click(String step, By locator, By expectedNext) {
        try {
            MobileActions.click(locator);
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, String.valueOf(locator), e.getMessage(), e);
        }
        verifyDisplayed(step, expectedNext);
    }

    protected void click(SmartLocator locator) {
        locator.click();
    }

    protected void click(String step, SmartLocator locator) {
        try {
            locator.click();
            ScreenshotUtils.captureStep(step);
        } catch (StepFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, String.valueOf(locator), e.getMessage(), e);
        }
    }

    protected void click(String step, SmartLocator locator, By expectedNext) {
        try {
            locator.click();
        } catch (StepFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, String.valueOf(locator), e.getMessage(), e);
        }
        verifyDisplayed(step, expectedNext);
    }

    protected void click(String step, SmartLocator locator, SmartLocator expectedNext) {
        try {
            locator.click();
        } catch (StepFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, String.valueOf(locator), e.getMessage(), e);
        }
        verifyDisplayed(step, expectedNext);
    }

    protected void verifyDisplayed(String step, By locator) {
        try {
            WaitUtils.waitForVisibility(locator);
            ScreenshotUtils.captureStep(step);
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, String.valueOf(locator), e.getMessage(), e);
        }
    }

    protected void verifyDisplayed(String step, SmartLocator locator) {
        try {
            locator.waitForElement();
            ScreenshotUtils.captureStep(step);
        } catch (StepFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, String.valueOf(locator), e.getMessage(), e);
        }
    }

    protected void verifyDisabled(String step, By locator) {
        try {
            WaitUtils.waitForVisibility(locator);
            String enabled = getAttribute(locator, "enabled");
            if (!"false".equalsIgnoreCase(enabled)) {
                ScreenshotUtils.captureFailureStep(step);
                throw new StepFailureException(step, String.valueOf(locator),
                        "Expected the element to be disabled, but enabled was '" + enabled + "'.", null);
            }
            ScreenshotUtils.captureStep(step);
        } catch (StepFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, String.valueOf(locator), e.getMessage(), e);
        }
    }

    protected void typeDigits(String step, String digits) {
        try {
            MobileActions.typeDigits(digits);
            ScreenshotUtils.captureStep(step);
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, "focused field", e.getMessage(), e);
        }
    }

    protected void deleteDigits(int count) {
        MobileActions.deleteDigits(count);
    }

    protected void verifyEnabled(String step, By locator) {
        try {
            WaitUtils.waitForAttribute(locator, "enabled", "true");
            ScreenshotUtils.captureStep(step);
        } catch (RuntimeException e) {
            ScreenshotUtils.captureFailureStep(step);
            throw new StepFailureException(step, String.valueOf(locator), e.getMessage(), e);
        }
    }

    /** Recovery only — normal launch runs once when the driver is created in {@link com.qaframework.base.BaseTest}. */
    protected void launchApp() {
        MobileActions.launchApplication();
    }

    // --- Input actions ---

    protected void sendKeys(By locator, String text) {
        MobileActions.sendKeys(locator, text);
    }

    protected void sendKeys(WebElement element, String text) {
        MobileActions.sendKeys(element, text);
    }

    protected void sendKeys(SmartLocator locator, String text) {
        locator.type(text);
    }

    /** Focuses a verified field and types digits with the existing key-event path. Does not clear or sendKeys. */
    protected void enterDigits(SmartLocator locator, String digits) {
        locator.enterDigits(digits);
    }

    protected void clearText(By locator) {
        MobileActions.clearField(locator);
    }

    protected void clearText(WebElement element) {
        MobileActions.clearField(element);
    }

    protected void clearText(SmartLocator locator) {
        locator.clear();
    }

    // --- Read actions ---

    protected String getText(By locator) {
        return MobileActions.getText(locator);
    }

    protected String getText(WebElement element) {
        return MobileActions.getText(element);
    }

    protected String getAttribute(By locator, String attribute) {
        return MobileActions.getAttribute(locator, attribute);
    }

    protected String getAttribute(WebElement element, String attribute) {
        return element.getAttribute(attribute);
    }

    // --- Visibility ---

    protected boolean isDisplayed(By locator) {
        return MobileActions.isDisplayed(locator);
    }

    protected boolean isDisplayed(WebElement element) {
        return MobileActions.isDisplayed(element);
    }

    protected boolean isDisplayed(SmartLocator locator) {
        return locator.isVisible();
    }

    // --- Wait methods ---

    protected WebElement waitForElementVisible(By locator) {
        return WaitUtils.waitForVisibility(locator);
    }

    protected WebElement waitForElementVisible(WebElement element) {
        return WaitUtils.waitForVisibility(element);
    }

    protected WebElement waitForElementVisible(SmartLocator locator) {
        return locator.waitForElement();
    }

    protected WebElement waitForElementClickable(By locator) {
        return WaitUtils.waitForClickability(locator);
    }

    protected WebElement waitForElementClickable(WebElement element) {
        return WaitUtils.waitForClickability(element);
    }

    protected WebElement waitForElementPresent(By locator) {
        return WaitUtils.waitForPresence(locator);
    }

    protected boolean waitForElementInvisible(By locator) {
        return WaitUtils.waitForInvisibility(locator);
    }

    // --- Mobile gestures ---

    protected void hideKeyboard() {
        MobileActions.hideKeyboard();
    }

    protected void scrollToText(String text) {
        MobileActions.scrollToText(text);
    }

    protected void scrollToAccessibilityId(String accessibilityId) {
        MobileActions.scrollToAccessibilityId(accessibilityId);
    }

    protected void scroll() {
        MobileActions.scrollIOS();
    }

    protected void swipeUp() {
        MobileActions.swipeUp();
    }

    protected void swipeDown() {
        MobileActions.swipeDown();
    }

    protected void swipeLeft() {
        MobileActions.swipeLeft();
    }

    protected void swipeRight() {
        MobileActions.swipeRight();
    }

    protected void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        MobileActions.swipe(startX, startY, endX, endY, durationMs);
    }

    protected void tap(int x, int y) {
        MobileActions.tap(x, y);
    }

    protected void tap(WebElement element) {
        MobileActions.tap(element);
    }

    protected void longPress(WebElement element, int durationMs) {
        MobileActions.longPress(element, durationMs);
    }

    protected void longPress(By locator, int durationMs) {
        MobileActions.longPress(locator, durationMs);
    }

    protected void dragAndDrop(WebElement source, WebElement target) {
        MobileActions.dragAndDrop(source, target);
    }

    protected void pressBack() {
        MobileActions.pressBack();
    }

    protected String takeScreenshot(String name) {
        return MobileActions.takeScreenshot(name);
    }
}
