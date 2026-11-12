package com.qaframework.exceptions;

/**
 * Fails the current test when a step cannot find, see, or use its target.
 */
public class StepFailureException extends AssertionError {

    public StepFailureException(String stepName, String locator, String reason, Throwable cause) {
        super(String.format("Step '%s' failed. Locator: %s. %s", stepName, locator, reason), cause);
    }
}
