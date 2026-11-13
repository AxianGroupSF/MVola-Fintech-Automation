package com.qaframework.exceptions;

import org.testng.SkipException;

/**
 * Stops a test before or after an unsafe account-sensitive action.
 * TestNG reports this as skipped. The message starts with BLOCKED.
 */
public class BlockedTestException extends SkipException {

    public BlockedTestException(String message) {
        super(message != null && message.startsWith("BLOCKED:") ? message : "BLOCKED: " + message);
    }
}
