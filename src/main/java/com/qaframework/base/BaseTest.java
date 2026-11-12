package com.qaframework.base;

import com.qaframework.config.ConfigReader;
import com.qaframework.drivers.AndroidDeviceHelper;
import com.qaframework.drivers.CapabilityManager;
import com.qaframework.drivers.DriverManager;
import com.qaframework.reports.RunReportManager;
import com.qaframework.server.AppiumServerManager;
import com.qaframework.utils.LoggerManager;
import com.qaframework.utils.MobileActions;
import com.qaframework.utils.ScreenshotUtils;
import io.appium.java_client.AppiumDriver;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.testng.ITestContext;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Listeners;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

import com.qaframework.listeners.TestListener;

import java.net.URL;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base test class - initializes Appium driver, server, and reports.
 * Extend this class for all test classes.
 */
@Listeners(TestListener.class)
public abstract class BaseTest {

    private static final Logger log = LoggerManager.getLogger(BaseTest.class);
    private static final AtomicInteger serverRefCount = new AtomicInteger(0);
    private static final Object serverLock = new Object();

    @BeforeSuite(alwaysRun = true)
    public void suiteSetup() {
        RunReportManager.initRun();
        if (ConfigReader.isAndroid()) {
            AndroidDeviceHelper.requireConnectedDevice();
            AndroidDeviceHelper.keepScreenAwake();
        }
        startAppiumServerIfNeeded();
    }

    @AfterSuite(alwaysRun = true)
    public void suiteTeardown() {
        if (ConfigReader.isAndroid()) {
            AndroidDeviceHelper.restoreScreenSettings();
        }
        stopAppiumServerIfNeeded();
        RunReportManager.finishAndBuild();
    }

    @BeforeMethod(alwaysRun = true)
    @Parameters({"platform"})
    public void setUp(@Optional String platform, ITestContext context) {
        resolvePlatform(platform, context);
        log.info("Starting test on platform: {}", ConfigReader.getPlatformName());
        if (ConfigReader.isAndroid()) {
            AndroidDeviceHelper.keepScreenAwake();
        }

        DesiredCapabilities capabilities = CapabilityManager.getCapabilities();
        String serverUrl = AppiumServerManager.getServerUrl();

        try {
            if (DriverManager.hasDriver()) {
                // Keep the current app state between @Test methods (no activateApp relaunch).
                log.info("Reusing Appium session for next test");
                return;
            }
            AppiumDriver driver = createDriver(serverUrl, capabilities);
            driver.manage().timeouts().implicitlyWait(
                    Duration.ofSeconds(ConfigReader.getInt("implicitWait", 10)));
            DriverManager.setDriver(driver);
            MobileActions.launchApplication();
            log.info("Driver initialized successfully for {}", ConfigReader.getPlatformName());
        } catch (Exception e) {
            log.error("Failed to initialize driver: {}", e.getMessage(), e);
            throw new RuntimeException("Driver initialization failed", e);
        }
    }

    @AfterMethod(alwaysRun = true)
    public void afterMethod(ITestResult result) {
        if (!DriverManager.hasDriver()) {
            return;
        }
        String testName = result.getMethod().getMethodName();
        try {
            if (result.getStatus() == ITestResult.SUCCESS) {
                ScreenshotUtils.captureOnPass(testName);
            } else if (result.getStatus() == ITestResult.FAILURE) {
                ScreenshotUtils.captureOnFailure(testName, result.getThrowable());
            }
        } catch (RuntimeException e) {
            log.warn("Teardown screenshot skipped (session may be dead): {}", e.getMessage());
        }
    }

    private AppiumDriver createDriver(String serverUrl, DesiredCapabilities capabilities) throws Exception {
        URL url = new URL(serverUrl);
        return new AppiumDriver(url, capabilities);
    }

    private void resolvePlatform(String platformParam, ITestContext context) {
        if (platformParam != null && !platformParam.isBlank()) {
            ConfigReader.setPlatformName(platformParam);
        } else if (context.getCurrentXmlTest().getParameter("platform") != null) {
            ConfigReader.setPlatformName(context.getCurrentXmlTest().getParameter("platform"));
        }
    }

    private void startAppiumServerIfNeeded() {
        synchronized (serverLock) {
            if (serverRefCount.incrementAndGet() == 1) {
                log.info("Starting Appium server");
                AppiumServerManager.startServer();
            }
        }
    }

    private void stopAppiumServerIfNeeded() {
        synchronized (serverLock) {
            if (serverRefCount.decrementAndGet() == 0) {
                if (DriverManager.hasDriver()) {
                    try {
                        log.info("Closing app at end of suite");
                        MobileActions.terminateApplication();
                    } catch (RuntimeException e) {
                        log.warn("App terminate skipped: {}", e.getMessage());
                    }
                    log.info("Quitting driver at end of suite");
                    DriverManager.quitDriver();
                }
                log.info("Stopping Appium server");
                AppiumServerManager.stopServer();
            }
        }
    }
}
