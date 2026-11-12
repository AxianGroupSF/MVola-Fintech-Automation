package com.qaframework.drivers;

import com.qaframework.config.ConfigReader;
import com.qaframework.utils.LoggerManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.io.File;

/**
 * Builds Appium DesiredCapabilities dynamically for Android and iOS.
 */
public final class CapabilityManager {

    private static final Logger log = LoggerManager.getLogger(CapabilityManager.class);
    private static final String PLATFORM_NAME = "platformName";
    private static final String DEVICE_NAME = "appium:deviceName";
    private static final String AUTOMATION_NAME = "appium:automationName";
    private static final String APP = "appium:app";
    private static final String NO_RESET = "appium:noReset";
    private static final String FULL_RESET = "appium:fullReset";

    private CapabilityManager() {
    }

    public static DesiredCapabilities getCapabilities() {
        String platform = ConfigReader.getPlatformName();

        if ("ios".equalsIgnoreCase(platform)) {
            return getIOSCapabilities();
        }
        return getAndroidCapabilities();
    }

    public static DesiredCapabilities getAndroidCapabilities() {
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setCapability(PLATFORM_NAME, "Android");
        caps.setCapability(DEVICE_NAME, ConfigReader.get("androidDeviceName", "Android Emulator"));
        caps.setCapability(AUTOMATION_NAME, ConfigReader.get("androidAutomationName", "UiAutomator2"));
        caps.setCapability(NO_RESET, ConfigReader.getBoolean("noReset", true));
        caps.setCapability(FULL_RESET, ConfigReader.getBoolean("fullReset", false));
        // Session start must not `am force-stop` / `am start -S`. The app stays up until suite teardown.
        caps.setCapability("appium:dontStopAppOnReset", true);
        caps.setCapability("appium:forceAppLaunch", false);
        caps.setCapability("appium:shouldTerminateApp", false);
        caps.setCapability("appium:intentFlags", "0x10000000");

        // Appium checks appium:app on this PC before it looks at the phone.
        // Use the installed package when it is already there; send the APK only when it is not.
        setAndroidApp(caps);
        setIfPresent(caps, "appium:appPackage", ConfigReader.get("androidAppPackage"));
        setIfPresent(caps, "appium:appActivity", ConfigReader.get("androidAppActivity"));
        setIfPresent(caps, "appium:appWaitActivity", ConfigReader.get("appWaitActivity"));
        setIfPresent(caps, "appium:udid", AndroidDeviceHelper.resolveUdid());
        caps.setCapability("appium:adbExecTimeout", ConfigReader.getInt("adbExecTimeout", 60000));
        // Fresh UIA2 install after uninstall / Android 16 needs longer install+launch windows.
        caps.setCapability("appium:uiautomator2ServerInstallTimeout",
                ConfigReader.getInt("uiautomator2ServerInstallTimeout", 120000));
        caps.setCapability("appium:uiautomator2ServerLaunchTimeout",
                ConfigReader.getInt("uiautomator2ServerLaunchTimeout", 120000));
        caps.setCapability("appium:newCommandTimeout",
                ConfigReader.getInt("newCommandTimeout", 300));
        caps.setCapability("appium:disableWindowAnimation", true);
        caps.setCapability("appium:skipLogcatCapture", true);

        if (ConfigReader.getBoolean("autoGrantPermissions", false)) {
            caps.setCapability("appium:autoGrantPermissions", true);
        }

        return caps;
    }

    public static DesiredCapabilities getIOSCapabilities() {
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setCapability(PLATFORM_NAME, "iOS");
        caps.setCapability(DEVICE_NAME, ConfigReader.get("iosDeviceName", "iPhone Simulator"));
        caps.setCapability(AUTOMATION_NAME, ConfigReader.get("iosAutomationName", "XCUITest"));
        caps.setCapability(NO_RESET, ConfigReader.getBoolean("noReset", true));
        caps.setCapability(FULL_RESET, ConfigReader.getBoolean("fullReset", false));

        setIfPresent(caps, APP, ConfigReader.get("iosAppPath"));
        setIfPresent(caps, "appium:bundleId", ConfigReader.get("iosBundleId"));
        setIfPresent(caps, "appium:udid", ConfigReader.get("iosUdid"));

        return caps;
    }

    private static void setAndroidApp(DesiredCapabilities caps) {
        String appPackage = ConfigReader.get("androidAppPackage");
        boolean fullReset = ConfigReader.getBoolean("fullReset", false);
        if (!fullReset && AndroidDeviceHelper.isPackageInstalled(appPackage)) {
            log.info("Using {} already installed on the device", appPackage);
            return;
        }
        String appPath = ConfigReader.get("androidAppPath");
        if (appPath == null || appPath.isBlank()) {
            throw new IllegalStateException(
                    "App " + appPackage + " is not installed and androidAppPath is empty");
        }
        File apk = new File(appPath.trim());
        if (!apk.isFile()) {
            throw new IllegalStateException(
                    "App " + appPackage + " is not installed and the APK is missing: " + apk.getAbsolutePath());
        }
        caps.setCapability(APP, apk.getAbsolutePath());
        log.info("App {} is not on the device; session will install {}", appPackage, apk.getAbsolutePath());
    }

    private static void setIfPresent(DesiredCapabilities caps, String key, String value) {
        if (value != null && !value.isBlank()) {
            caps.setCapability(key, value.trim());
        }
    }
}
