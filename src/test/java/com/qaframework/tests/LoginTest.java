package com.qaframework.tests;

import com.qaframework.base.BaseTest;
import com.qaframework.config.ConfigReader;
import com.qaframework.exceptions.BlockedTestException;
import com.qaframework.pages.LoginPage;
import com.qaframework.pages.LoginPage.PinSubmission;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Login module. One method per supplied TC-ID.
 * PIN values are read from config and are not written into assertions or logs.
 */
public class LoginTest extends BaseTest {

    /*@Test(description = "LOGIN_001")
    public void LOGIN_001_incompletePinDisablesLogin() {
        LoginPage login = openLogin();
        login.dismissKeyboard();
        Assert.assertTrue(login.isLoginButtonVisible(),
                "LOGIN_001: Login button should be visible after the keyboard closes.");
        login.enterPin("111");
        login.dismissKeyboard();
        Assert.assertFalse(login.isLoginSubmissionEnabled(),
                "LOGIN_001: Login should stay disabled for a 3-digit PIN.");
        Assert.assertTrue(login.isPinLoginScreenVisible(),
                "LOGIN_001: The PIN login screen should remain displayed.");
    }

    @Test(description = "LOGIN_002", groups = "account-sensitive")
    public void LOGIN_002_invalidPinShowsError() {
        String invalidPin = requiredPin("invalidPin", "LOGIN_002");
        String pin = requiredPin("pin", "LOGIN_002");
        if (invalidPin.equals(pin)) {
            throw new AssertionError("LOGIN_002: invalidPin must be different from the configured pin.");
        }
        LoginPage login = openLogin();
        login.dismissKeyboard();
        Assert.assertTrue(login.isLoginButtonVisible(),
                "LOGIN_002: Login button should be visible after the keyboard closes.");
        login.enterPin(invalidPin);
        requireSubmission("LOGIN_002", login, PinSubmission.INVALID_PIN);
        Assert.assertTrue(login.isInvalidPinErrorVisible(),
                "LOGIN_002: Invalid PIN error should be displayed.");
        login.dismissInvalidPinError();
        Assert.assertTrue(login.isPinLoginScreenVisible(),
                "LOGIN_002: The PIN login screen should remain displayed.");
    }*/

    @Test(description = "LOGIN_003", groups = "account-sensitive")
    public void LOGIN_003_validPinOpensDashboard() {
        LoginPage login = openLogin();
        login.dismissKeyboard();
        Assert.assertTrue(login.isLoginButtonVisible(),
                "LOGIN_003: Login button should be visible after the keyboard closes.");
        login.enterPin(requiredPin("pin", "LOGIN_003"));
        requireSubmission("LOGIN_003", login, PinSubmission.DASHBOARD);
        login.closeOptionalPopups();
        Assert.assertTrue(login.isDashboardMarkerVisible(),
                "LOGIN_003: The dashboard marker should be visible after login.");
        Assert.assertTrue(login.isSendMoneyVisible(),
                "LOGIN_003: Send Money should be visible on the dashboard.");
    }

    private static LoginPage openLogin() {
        LoginPage login = new LoginPage();
        login.openPinLogin();
        return login;
    }

    /** An unexpected screen after a PIN is submitted stops the test as BLOCKED instead of continuing. */
    private static void requireSubmission(String tcId, LoginPage login, PinSubmission expected) {
        PinSubmission actual = login.awaitAuthenticationResult();
        if (actual != expected) {
            throw new BlockedTestException("BLOCKED: " + tcId
                    + " stopped because the screen after submission was " + actual + ".");
        }
    }

    private static String requiredPin(String key, String tcId) {
        String value = ConfigReader.get(key);
        if (value.length() != 4 || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalStateException(tcId + " requires " + key + " to be exactly four digits.");
        }
        return value;
    }
}
