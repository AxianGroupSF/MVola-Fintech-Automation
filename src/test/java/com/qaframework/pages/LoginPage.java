package com.qaframework.pages;

import com.qaframework.config.ConfigReader;
import com.qaframework.drivers.AndroidDeviceHelper;
import com.qaframework.exceptions.BlockedTestException;
import com.qaframework.exceptions.StepFailureException;
import com.qaframework.pages.base.BasePage;
import com.qaframework.utils.LoggerManager;
import com.qaframework.utils.SmartLocator;
import com.qaframework.utils.WaitUtils;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PIN login, logout, and the dashboard state that shows login succeeded.
 * Locators come from the release build on the connected Android device.
 */
public class LoginPage extends BasePage {

    private static final Logger log = LoggerManager.getLogger(LoginPage.class);

    private final SmartLocator pinLoginScreen = SmartLocator.verified(
            "PIN login screen", null, "semantics_login_screen", null, null, null);
    private final SmartLocator pinField = SmartLocator.verified(
            "PIN field", null, "semantics_pin_field", null, null, null);
    private final SmartLocator loginButton = SmartLocator.verified(
            "Login button", null, "semantics_login_button", null, null, null);
    private final SmartLocator invalidPinMessage = SmartLocator.verified(
            "Invalid PIN message",
            "Invalid MPIN, please correct and try again",
            null, null, null, null);
    private final SmartLocator invalidPinClose = SmartLocator.verified(
            "Invalid PIN dismiss", "Try Again", "semantics_invalid_pin_popup_try_again", null, null, null);
    private final SmartLocator dashboard = SmartLocator.verified(
            "Dashboard", "Main dashboard", "semantics_dashboard_v4", null, null, null);
    private final SmartLocator sendMoney = SmartLocator.verified(
            "Send Money", "dashboard_item_Send_Money", null, null, null, null);
    private static final String EVENT_BANNER_ID = "semantics_event_banner_bottom_sheet";
    /** Safety cap on how many popups are closed in a row. */
    private static final int MAX_POPUPS = 3;

    private final SmartLocator eventBannerClose = SmartLocator.verified(
            "Event banner close", "Close", "semantics_event_banner_close", null, null, null);
    private final SmartLocator sideDrawerButton = SmartLocator.verified(
            "Side drawer menu", "Side drawer menu", null, null, null, null);
    private final SmartLocator logout = SmartLocator.verified(
            "Logout", "Logout", null, null, null, null);

    /**
     * Full content-desc sentences read from the account-blocked dialog.
     * "Forgotten secret code" and "Code secret oublié" also appear on the normal login screen,
     * so those button labels are not used to detect a block.
     */
    private static final String BLOCKED_MESSAGE_EN =
            "Dear Customer! Your account is blocked due to multiple invalid login attempts. "
                    + "Please reset your PIN to continue using the app,";
    private static final String BLOCKED_MESSAGE_FR =
            "Cher client! Votre compte est bloqué en raison de plusieurs tentatives de connexion invalides. "
                    + "Veuillez réinitialiser votre code secret pour continuer à utiliser l'application.";

    public enum PinSubmission {
        INVALID_PIN,
        DASHBOARD,
        ACCOUNT_BLOCKED,
        UNKNOWN
    }

    public void openPinLogin() {
        String source = awaitPageSource(this::canNavigate);
        if (source.isEmpty()) {
            throw new BlockedTestException(
                    "BLOCKED: The PIN login screen was not verified, so no PIN was entered.");
        }
        stopIfAccountBlocked(source);
        if (source.contains("semantics_login_screen")) {
            requireFocusedEmptyPinField();
            return;
        }
        if (source.contains(EVENT_BANNER_ID)) {
            click(eventBannerClose);
            logoutFromDashboard();
        } else if (source.contains("semantics_dashboard_v4") || source.contains("Main dashboard")) {
            logoutFromDashboard();
        } else if (source.contains("semantics_invalid_pin_popup")) {
            dismissInvalidPinError();
        } else if (source.contains("Are you sure you want to logout")) {
            confirmLogout();
        } else if (source.contains("semantics_side_drawer")) {
            chooseLogout();
            confirmLogout();
        } else {
            throw new BlockedTestException(
                    "BLOCKED: The PIN login screen was not verified, so no PIN was entered.");
        }
        String afterNavigation = awaitPageSource(page -> page.contains("semantics_login_screen")
                || classify(page) == PinSubmission.ACCOUNT_BLOCKED);
        stopIfAccountBlocked(afterNavigation);
        if (!afterNavigation.contains("semantics_login_screen")) {
            throw new BlockedTestException(
                    "BLOCKED: The PIN login screen was not verified after navigation, so no PIN was entered.");
        }
        requireFocusedEmptyPinField();
    }

    public boolean isPinLoginScreenVisible() {
        return isDisplayed(pinLoginScreen);
    }

    public boolean isLoginButtonVisible() {
        return isDisplayed(loginButton);
    }

    /**
     * The release build keeps {@code enabled=true} and reports a disabled Login action as
     * {@code clickable=false} on {@code semantics_login_button}.
     */
    public boolean isLoginSubmissionEnabled() {
        WebElement button = waitForElementVisible(loginButton);
        return Boolean.parseBoolean(getAttribute(button, "clickable"));
    }

    /**
     * Keeps the verified adb keyevent path. {@code MobileActions.typeDigits} and
     * {@code deleteDigits} use {@code mobile: pressKey}, which does not fill this Flutter field.
     * The field must be focused and empty first. A four-digit PIN submits automatically on the last digit.
     */
    public void enterPin(String digits) {
        if (digits == null || digits.isEmpty() || digits.chars().anyMatch(ch -> !Character.isDigit(ch))) {
            throw new IllegalArgumentException("PIN entry requires digits");
        }
        requireFocusedEmptyPinField();
        if (digits.length() < 4) {
            runAdbInput(keyEvents(digits));
            requirePinLength(digits.length());
            return;
        }
        if (digits.length() != 4) {
            throw new BlockedTestException(
                    "BLOCKED: PIN submission requires exactly four digits, so nothing was submitted.");
        }
        runAdbInput(keyEvents(digits.substring(0, 3)));
        requirePinLength(3);
        runAdbInput(keyEvents(digits.substring(3)));
    }

    /**
     * Polls until the authentication result is visible, using the one configured explicit wait.
     */
    public PinSubmission awaitAuthenticationResult() {
        String source = awaitPageSource(page -> classify(page) != PinSubmission.UNKNOWN);
        PinSubmission result = source.isEmpty() ? PinSubmission.UNKNOWN : classify(source);
        if (result == PinSubmission.UNKNOWN) {
            // A skipped test gets no automatic screenshot, so keep the unexpected screen as evidence.
            takeScreenshot("login_result_unknown");
        }
        return result;
    }

    /**
     * The launch screenshot can be the splash logo. One explicit wait covers that transition.
     */
    private String awaitPageSource(java.util.function.Predicate<String> ready) {
        return awaitPageSource(ready, ConfigReader.getInt("explicitWait", 20));
    }

    private String awaitPageSource(java.util.function.Predicate<String> ready, int timeoutSeconds) {
        String[] seen = {""};
        ExpectedCondition<Boolean> readySource = driver -> {
            try {
                String source = driver.getPageSource();
                source = source == null ? "" : source;
                if (!ready.test(source)) {
                    return false;
                }
                seen[0] = source;
                return true;
            } catch (RuntimeException unread) {
                return false;
            }
        };
        WaitUtils.waitForCondition(readySource, timeoutSeconds);
        return seen[0];
    }

    private boolean canNavigate(String source) {
        return source.contains("semantics_login_screen")
                || source.contains("semantics_dashboard_v4")
                || source.contains("Main dashboard")
                || source.contains("semantics_invalid_pin_popup")
                || source.contains("Are you sure you want to logout")
                || source.contains("semantics_side_drawer")
                || classify(source) == PinSubmission.ACCOUNT_BLOCKED;
    }

    /**
     * Back-office popups are optional and can appear a few seconds after the dashboard. Waits up to
     * {@code popupWait} seconds for the event banner, closes it with its cross icon, and looks again so a
     * second popup is handled too (at most {@link #MAX_POPUPS}). No popup is not an error.
     * Returns how many were closed. Login success is proven by the dashboard checks, not by this.
     */
    public int closeOptionalPopups() {
        int popupWait = ConfigReader.getInt("popupWait", 5);
        int closed = 0;
        while (closed < MAX_POPUPS
                && !awaitPageSource(page -> page.contains(EVENT_BANNER_ID), popupWait).isEmpty()) {
            click(eventBannerClose);
            closed++;
            if (awaitPageSource(page -> !page.contains(EVENT_BANNER_ID)).isEmpty()) {
                throw new StepFailureException("Close popup", "Event banner close",
                        "The event banner was still shown after its cross icon was clicked.", null);
            }
            log.info("Closed event banner {}", closed);
        }
        return closed;
    }

    public boolean isDashboardMarkerVisible() {
        return isDisplayed(dashboard);
    }

    public boolean isSendMoneyVisible() {
        return isDisplayed(sendMoney);
    }

    public void dismissKeyboard() {
        hideKeyboard();
    }

    public boolean isInvalidPinErrorVisible() {
        return isDisplayed(invalidPinMessage);
    }

    public void dismissInvalidPinError() {
        click(invalidPinClose);
    }

    private void logoutFromDashboard() {
        click(sideDrawerButton);
        chooseLogout();
        confirmLogout();
    }

    private void chooseLogout() {
        click(logout);
    }

    private void confirmLogout() {
        if (!screenSnapshot().contains("Are you sure you want to logout")) {
            throw new StepFailureException("Confirm logout", "Logout confirmation",
                    "The logout confirmation was not visible.", null);
        }
        click(logout);
    }

    private void requireFocusedEmptyPinField() {
        WebElement field = waitForElementVisible(pinField);
        if (!focusedAndEmpty(field)) {
            String shown = attribute(field, "text");
            if (shown != null && !shown.isBlank()) {
                if (!shown.chars().allMatch(Character::isDigit)) {
                    throw new BlockedTestException(
                            "BLOCKED: The PIN field was not verified empty, so no PIN was entered.");
                }
                List<String> deletes = new ArrayList<>();
                deletes.add("keyevent");
                for (int i = 0; i < shown.length(); i++) {
                    deletes.add("67");
                }
                runAdbInput(deletes);
                field = waitForElementVisible(pinField);
            }
        }
        if (!focusedAndEmpty(field)) {
            String shown = attribute(field, "text");
            if (shown == null || shown.isBlank()) {
                relaunchLoginScreen();
                field = waitForElementVisible(pinField);
            }
        }
        if (!focusedAndEmpty(field)) {
            throw new BlockedTestException(
                    "BLOCKED: The PIN field was not verified focused and empty. "
                            + "The app was not restarted and no PIN was entered.");
        }
        log.info("PIN field is focused and empty");
    }

    /**
     * adb key events reach the PIN boxes only while the field is focused, and a tap on the field breaks
     * that path. After the invalid PIN dialog closes the empty field stays unfocused, so the login screen
     * is relaunched, which autofocuses it. No PIN is entered here.
     */
    private void relaunchLoginScreen() {
        log.info("PIN field is empty but not focused; relaunching the login screen");
        Map<String, Object> params = new HashMap<>();
        params.put("component", ConfigReader.get("androidAppPackage") + "/"
                + ConfigReader.get("androidAppActivity"));
        params.put("action", "android.intent.action.MAIN");
        params.put("categories", "android.intent.category.LAUNCHER");
        params.put("flags", "0x10000000");
        params.put("stop", true);
        driver.executeScript("mobile: startActivity", params);
        stopIfAccountBlocked(awaitPageSource(page -> page.contains("semantics_login_screen")
                || classify(page) == PinSubmission.ACCOUNT_BLOCKED));
    }

    private static String attribute(WebElement field, String name) {
        try {
            return field.getAttribute(name);
        } catch (RuntimeException unverified) {
            throw new BlockedTestException(
                    "BLOCKED: The PIN field state could not be read, so no PIN was entered.");
        }
    }

    private static boolean focusedAndEmpty(WebElement field) {
        try {
            String focused = field.getAttribute("focused");
            String shown = field.getAttribute("text");
            return "true".equalsIgnoreCase(focused) && (shown == null || shown.isBlank());
        } catch (RuntimeException unverified) {
            return false;
        }
    }

    private void requirePinLength(int expectedLength) {
        int[] seenLength = {-1};
        String[] seenFocus = {"unread"};
        boolean lengthShown = WaitUtils.waitForCondition(driver -> {
            try {
                WebElement field = waitForElementVisible(pinField);
                String shown = field.getAttribute("text");
                seenLength[0] = shown == null ? 0 : shown.length();
                seenFocus[0] = String.valueOf(field.getAttribute("focused"));
                return seenLength[0] == expectedLength;
            } catch (RuntimeException unread) {
                return false;
            }
        }, ConfigReader.getInt("explicitWait", 20));
        if (!lengthShown) {
            takeScreenshot("PIN_entry_blocked");
            throw new BlockedTestException("BLOCKED: The PIN field showed " + seenLength[0] + " of "
                    + expectedLength + " digit(s) (focused=" + seenFocus[0]
                    + "), so PIN entry stopped before the last digit. No PIN was submitted.");
        }
    }

    private List<String> keyEvents(String digits) {
        List<String> keyEvents = new ArrayList<>();
        keyEvents.add("keyevent");
        for (int i = 0; i < digits.length(); i++) {
            keyEvents.add(Integer.toString(7 + (digits.charAt(i) - '0')));
        }
        return keyEvents;
    }

    private String screenSnapshot() {
        try {
            return WaitUtils.withoutImplicitWait(() -> {
                String source = driver.getPageSource();
                return source == null ? "" : source;
            });
        } catch (RuntimeException e) {
            throw new BlockedTestException(
                    "BLOCKED: The current screen could not be read, so no PIN was entered.");
        }
    }

    private void stopIfAccountBlocked(String source) {
        if (classify(source) == PinSubmission.ACCOUNT_BLOCKED) {
            throw new BlockedTestException(
                    "BLOCKED: The account-blocked dialog is showing. PIN reset was not opened and no PIN was entered.");
        }
    }

    private PinSubmission classify(String source) {
        if (source.contains(BLOCKED_MESSAGE_EN) || source.contains(BLOCKED_MESSAGE_FR)) {
            return PinSubmission.ACCOUNT_BLOCKED;
        }
        if (source.contains("Invalid MPIN, please correct and try again")
                || source.contains("semantics_invalid_pin_popup")) {
            return PinSubmission.INVALID_PIN;
        }
        // While a popup sheet is open the dashboard nodes are not in the hierarchy. The banner only shows
        // that the app is past the PIN screen. Success is verified by the dashboard checks after popups close.
        if (source.contains("semantics_dashboard_v4") || source.contains("Main dashboard")
                || source.contains(EVENT_BANNER_ID)) {
            return PinSubmission.DASHBOARD;
        }
        return PinSubmission.UNKNOWN;
    }

    private void runAdbInput(List<String> inputArgs) {
        String udid = AndroidDeviceHelper.resolveUdid();
        if (udid == null || udid.isBlank()) {
            throw new StepFailureException("PIN entry", "adb", "No Android device is connected.", null);
        }
        File adb = new File(ConfigReader.getAndroidSdkPath(),
                "platform-tools" + File.separator + "adb.exe");
        List<String> command = new ArrayList<>();
        command.add(adb.getAbsolutePath());
        command.add("-s");
        command.add(udid);
        command.add("shell");
        command.add("input");
        command.addAll(inputArgs);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new StepFailureException("PIN entry", "adb", "PIN entry timed out.", null);
            }
            if (process.exitValue() != 0) {
                throw new StepFailureException("PIN entry", "adb", "PIN entry was rejected.", null);
            }
        } catch (StepFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new StepFailureException("PIN entry", "adb", "PIN entry could not be sent.", e);
        }
    }
}
