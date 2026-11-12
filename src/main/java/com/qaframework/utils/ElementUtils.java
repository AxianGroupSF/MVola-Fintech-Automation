package com.qaframework.utils;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

/**
 * Thin wrapper around MobileActions for teams preferring ElementUtils naming.
 */
public final class ElementUtils {

    private ElementUtils() {
    }

    public static void click(By locator) {
        MobileActions.click(locator);
    }

    public static void click(WebElement element) {
        MobileActions.click(element);
    }

    public static void sendKeys(By locator, String text) {
        MobileActions.sendKeys(locator, text);
    }

    public static String getText(By locator) {
        return MobileActions.getText(locator);
    }

    public static boolean isDisplayed(By locator) {
        return MobileActions.isDisplayed(locator);
    }

    public static void clear(By locator) {
        MobileActions.clearField(locator);
    }

    public static void click(SmartLocator locator) {
        locator.click();
    }

    public static void sendKeys(SmartLocator locator, String text) {
        locator.type(text);
    }

    public static void enterDigits(SmartLocator locator, String digits) {
        locator.enterDigits(digits);
    }

    public static void clear(SmartLocator locator) {
        locator.clear();
    }

    public static boolean isDisplayed(SmartLocator locator) {
        return locator.isVisible();
    }

    public static WebElement findElement(SmartLocator locator) {
        return locator.findElement();
    }

    public static WebElement waitForElement(SmartLocator locator) {
        return locator.waitForElement();
    }

    public static void hideKeyboard() {
        MobileActions.hideKeyboard();
    }

    public static String takeScreenshot(String name) {
        return MobileActions.takeScreenshot(name);
    }
}
