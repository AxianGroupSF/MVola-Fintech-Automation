package com.qaframework.drivers;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.ios.IOSDriver;

/**
 * Thread-safe driver manager for parallel test execution.
 */
public final class DriverManager {

    private static final ThreadLocal<AppiumDriver> driver = new ThreadLocal<>();
    /** One session for the suite, including when TestNG switches threads between cases. */
    private static volatile AppiumDriver suiteDriver;

    private DriverManager() {
    }

    public static void setDriver(AppiumDriver appiumDriver) {
        suiteDriver = appiumDriver;
        driver.set(appiumDriver);
    }

    @SuppressWarnings("unchecked")
    public static <T extends AppiumDriver> T getDriver() {
        AppiumDriver current = driver.get();
        if (current == null) {
            current = suiteDriver;
            if (current != null) {
                driver.set(current);
            }
        }
        return (T) current;
    }

    public static AndroidDriver getAndroidDriver() {
        return (AndroidDriver) getDriver();
    }

    public static IOSDriver getIOSDriver() {
        return (IOSDriver) getDriver();
    }

    public static boolean hasDriver() {
        return getDriver() != null;
    }

    public static void quitDriver() {
        AppiumDriver currentDriver = getDriver();
        suiteDriver = null;
        driver.remove();
        if (currentDriver != null) {
            currentDriver.quit();
        }
    }
}
