package com.qaframework.utils;

import com.qaframework.config.ConfigReader;
import com.qaframework.drivers.DriverManager;
import io.appium.java_client.AppiumDriver;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Reusable explicit and fluent wait utilities.
 */
public final class WaitUtils {

    private static final org.apache.logging.log4j.Logger log = LoggerManager.getLogger(WaitUtils.class);

    private WaitUtils() {
    }

    public static WebDriverWait getExplicitWait() {
        return getExplicitWait(ConfigReader.getInt("explicitWait", 20));
    }

    public static WebDriverWait getExplicitWait(int timeoutSeconds) {
        return new WebDriverWait(getDriver(), Duration.ofSeconds(timeoutSeconds));
    }

    public static FluentWait<AppiumDriver> getFluentWait() {
        int timeout = ConfigReader.getInt("fluentWaitTimeout", 30);
        int polling = ConfigReader.getInt("fluentWaitPolling", 500);

        return new FluentWait<>(getDriver())
                .withTimeout(Duration.ofSeconds(timeout))
                .pollingEvery(Duration.ofMillis(polling))
                .ignoring(NoSuchElementException.class)
                .ignoring(StaleElementReferenceException.class);
    }

    public static WebElement waitForVisibility(By locator) {
        log.debug("Waiting for visibility: {}", locator);
        return await(() -> getExplicitWait().until(ExpectedConditions.visibilityOfElementLocated(locator)));
    }

    public static WebElement waitForVisibility(WebElement element) {
        return await(() -> getExplicitWait().until(ExpectedConditions.visibilityOf(element)));
    }

    public static WebElement waitForClickability(By locator) {
        log.debug("Waiting for clickability: {}", locator);
        return await(() -> getExplicitWait().until(ExpectedConditions.elementToBeClickable(locator)));
    }

    public static WebElement waitForClickability(WebElement element) {
        return await(() -> getExplicitWait().until(ExpectedConditions.elementToBeClickable(element)));
    }

    public static WebElement waitForPresence(By locator) {
        log.debug("Waiting for presence: {}", locator);
        return await(() -> getExplicitWait().until(ExpectedConditions.presenceOfElementLocated(locator)));
    }

    /**
     * Runs one lookup cycle with implicit wait suspended. Callers batch every find inside this
     * scope so the timeout is not toggled once per candidate.
     */
    public static <T> T withoutImplicitWait(Supplier<T> probe) {
        return await(probe);
    }

    public static boolean waitForInvisibility(By locator) {
        log.debug("Waiting for invisibility: {}", locator);
        return await(() -> getExplicitWait().until(ExpectedConditions.invisibilityOfElementLocated(locator)));
    }

    public static void waitForAttribute(By locator, String attribute, String expected) {
        log.debug("Waiting for {} {}={}", locator, attribute, expected);
        await(() -> getExplicitWait().until(driver -> {
            String actual = driver.findElement(locator).getAttribute(attribute);
            return expected.equalsIgnoreCase(actual == null ? "" : actual);
        }));
    }

    public static boolean waitForInvisibility(WebElement element) {
        return await(() -> getExplicitWait().until(ExpectedConditions.invisibilityOf(element)));
    }

    public static Alert waitForAlert() {
        log.debug("Waiting for alert");
        return await(() -> getExplicitWait().until(ExpectedConditions.alertIsPresent()));
    }

    public static boolean waitForCondition(ExpectedCondition<Boolean> condition, int timeoutSeconds) {
        try {
            return await(() -> getExplicitWait(timeoutSeconds).until(condition));
        } catch (TimeoutException e) {
            return false;
        }
    }

    public static <T> T waitWithFluent(Function<AppiumDriver, T> condition) {
        return await(() -> getFluentWait().until(condition));
    }

    /**
     * Explicit waits poll with {@code findElement}. An implicit wait makes every miss block for
     * {@code implicitWait} seconds, so one explicit timeout is served several times.
     * Suspend it for the probe and restore whatever the caller had set.
     */
    private static <T> T await(Supplier<T> probe) {
        AppiumDriver driver = getDriver();
        Duration previous = driver.manage().timeouts().getImplicitWaitTimeout();
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);
        try {
            return probe.get();
        } finally {
            driver.manage().timeouts().implicitlyWait(previous);
        }
    }

    public static void hardWait(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted during hard wait", e);
        }
    }

    private static AppiumDriver getDriver() {
        return DriverManager.getDriver();
    }
}
