package com.qaframework.utils;

import com.qaframework.reports.RunReportManager;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Every step is saved under the run folder images directory. Steps are listed once in
 * run.json (no intermediate image attachments). A passing test attaches only the
 * completed use-case shot; a failing test attaches only the failure-step shot.
 */
public final class ScreenshotUtils {

    private static final ThreadLocal<String> finalUseCasePath = new ThreadLocal<>();
    private static final ThreadLocal<String> latestStepPath = new ThreadLocal<>();
    private static final ThreadLocal<String> failureStepPath = new ThreadLocal<>();
    private static final ThreadLocal<String> lastLoggedStep = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, String>> savedByStep = ThreadLocal.withInitial(HashMap::new);

    private ScreenshotUtils() {
    }

    public static void clearLastScreenshot() {
        finalUseCasePath.remove();
        latestStepPath.remove();
        failureStepPath.remove();
        lastLoggedStep.remove();
        savedByStep.remove();
    }

    /**
     * Saves the step under the images folder and records the step name once.
     * Does not attach the image, and does not replace the completed use-case shot.
     */
    public static void captureStep(String stepName) {
        String path = saveOnce(stepName);
        logStepOnce(stepName);
        if (path != null && !path.isBlank() && !isReturnHomeStep(stepName)) {
            latestStepPath.set(path);
        }
    }

    /**
     * Completed use-case screen (receipt or final confirmation). Saved to images and
     * used as the single pass attachment. Call this before navigating back to the dashboard.
     */
    public static String capture(String stepName) {
        String path = saveOnce(stepName);
        logStepOnce(stepName);
        if (path != null && !path.isBlank()) {
            finalUseCasePath.set(path);
        }
        return path;
    }

    /** Failure screen for this step. Saved to images and used as the single fail attachment. */
    public static void captureFailureStep(String stepName) {
        String path = save(stepName);
        if (path != null && !path.isBlank()) {
            failureStepPath.set(path);
        }
    }

    public static String captureOnPass(String testName) {
        String path = finalUseCasePath.get();
        if (path == null || path.isBlank()) {
            path = latestStepPath.get();
        }
        if (path == null || path.isBlank()) {
            path = save(testName + "_PASS");
        }
        if (path != null && !path.isBlank()) {
            RunReportManager.setEvidenceScreenshot(path, "pass");
        }
        return path;
    }

    public static String captureOnFailure(String testName, Throwable failure) {
        String path = failureStepPath.get();
        if (path == null || path.isBlank()) {
            path = save(testName + "_FAIL");
        }
        if (path != null && !path.isBlank()) {
            RunReportManager.setEvidenceScreenshot(path, "failure");
        }
        return path;
    }

    /** Home screen reached only so the next use case can start. Not the pass attachment. */
    private static boolean isReturnHomeStep(String stepName) {
        if (stepName == null) {
            return false;
        }
        String name = stepName.toLowerCase();
        return name.equals("dashboard") || name.startsWith("dashboard after");
    }

    private static String save(String stepName) {
        return MobileActions.takeScreenshot(stepName);
    }

    /**
     * One file per step name for the current test. A later save of the same name
     * (step check, then final report shot) reuses the file already written.
     */
    private static String saveOnce(String stepName) {
        if (stepName == null || stepName.isBlank()) {
            return save(stepName);
        }
        Map<String, String> saved = savedByStep.get();
        String existing = saved.get(stepName);
        if (existing != null && new File(existing).isFile()) {
            return existing;
        }
        String path = save(stepName);
        if (path != null && !path.isBlank()) {
            saved.put(stepName, path);
        }
        return path;
    }

    private static void logStepOnce(String stepName) {
        if (stepName == null || stepName.isBlank()) {
            return;
        }
        if (stepName.equals(lastLoggedStep.get())) {
            return;
        }
        lastLoggedStep.set(stepName);
        RunReportManager.addStep(stepName);
    }
}
