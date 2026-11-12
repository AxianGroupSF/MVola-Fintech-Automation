package com.qaframework.listeners;

import com.qaframework.reports.RunReportManager;
import com.qaframework.utils.LoggerManager;
import com.qaframework.utils.ScreenshotUtils;
import org.apache.logging.log4j.Logger;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

/**
 * TestNG listener for logging and run.json collection (Dev/QA HTML via reporter/reporting).
 */
public class TestListener implements ITestListener {

    private static final Logger log = LoggerManager.getLogger(TestListener.class);

    @Override
    public void onStart(ITestContext context) {
        log.info("========== Test Suite Started: {} ==========", context.getName());
    }

    @Override
    public void onFinish(ITestContext context) {
        log.info("========== Test Suite Finished: {} ==========", context.getName());
    }

    @Override
    public void onTestStart(ITestResult result) {
        String methodName = result.getMethod().getMethodName();
        String description = result.getMethod().getDescription();
        String reportName = (description == null || description.isBlank())
                ? methodName
                : description.trim();

        log.info("---------- Test Started: {} ({}) ----------", reportName, methodName);
        ScreenshotUtils.clearLastScreenshot();
        RunReportManager.onTestStart(result);
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        log.info("Test PASSED: {}", result.getMethod().getMethodName());
        RunReportManager.onTestSuccess(result);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        String testName = result.getMethod().getMethodName();
        Throwable throwable = result.getThrowable();
        String failureReason = throwable != null ? throwable.getMessage() : "Unknown failure";

        log.error("Test FAILED: {} - Reason: {}", testName, failureReason, throwable);
        RunReportManager.onTestFailure(result);
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        String testName = result.getMethod().getMethodName();
        log.warn("Test SKIPPED: {}", testName);
        RunReportManager.onTestSkipped(result);
    }
}
