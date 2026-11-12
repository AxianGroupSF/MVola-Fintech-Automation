package com.qaframework.drivers;

import com.qaframework.config.ConfigReader;
import com.qaframework.utils.LoggerManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resolves connected Android devices via adb from {@code androidSdkPath}.
 */
public final class AndroidDeviceHelper {

    private static final Logger log = LoggerManager.getLogger(AndroidDeviceHelper.class);
    private static final int AWAKE_POLL_MS = 200;
    private static final int AWAKE_ATTEMPTS = 10;

    private static volatile boolean stayOnApplied;
    private static volatile String savedScreenTimeout;

    private AndroidDeviceHelper() {
    }

    /**
     * Keeps the screen on while the phone is plugged in, and wakes a lock screen before the next test.
     * A phone unlock PIN is typed only when the lock screen is actually showing.
     */
    public static void keepScreenAwake() {
        String udid = resolveUdid();
        if (udid.isBlank()) {
            log.warn("No Android device; screen keep-awake skipped");
            return;
        }
        applyStayOn(udid);
        if (isScreenOff(udid)) {
            runShell(udid, "input", "keyevent", "224");
            waitUntilAwake(udid);
        }
        if (!isKeyguardShowing(udid)) {
            log.info("Android screen is awake on {}", udid);
            return;
        }
        runShell(udid, "wm", "dismiss-keyguard");
        if (isKeyguardShowing(udid)) {
            unlockWithPin(udid);
        }
        if (isKeyguardShowing(udid)) {
            log.warn("Phone lock screen is still showing on {}. Set deviceLockPin in config.properties "
                    + "to the PIN used to unlock the phone.", udid);
            return;
        }
        log.info("Android lock screen dismissed on {}", udid);
    }

    /** Restores the screen timeout captured before {@link #keepScreenAwake()}. */
    public static void restoreScreenSettings() {
        if (!stayOnApplied) {
            return;
        }
        String udid = resolveUdid();
        if (udid.isBlank()) {
            return;
        }
        if (savedScreenTimeout != null && savedScreenTimeout.chars().allMatch(Character::isDigit)) {
            runShell(udid, "settings", "put", "system", "screen_off_timeout", savedScreenTimeout);
        }
        runShell(udid, "svc", "power", "stayon", "false");
        stayOnApplied = false;
        log.info("Restored Android screen timeout on {}", udid);
    }

    public static void requireConnectedDevice() {
        List<String> devices = listReadyDevices();
        if (devices.isEmpty()) {
            throw new RuntimeException(
                    "No Android device in 'device' state. Connect the phone, enable USB debugging, "
                            + "accept the RSA prompt, then run: "
                            + adbExecutable().getAbsolutePath() + " devices");
        }
        log.info("Android device(s) ready: {}", devices);
    }

    /**
     * True when {@code packageName} is already installed on the resolved device.
     * False when the package is absent, or when no device is available to ask.
     */
    public static boolean isPackageInstalled(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return false;
        }
        String udid = resolveUdid();
        if (udid.isBlank()) {
            log.warn("No Android device; cannot check whether {} is installed", packageName);
            return false;
        }
        ShellResult result = runShellResult(udid, "pm", "path", packageName.trim());
        boolean installed = result.exitCode == 0 && result.output.contains("package:");
        log.info("Package {} {} on {}", packageName, installed ? "is installed" : "is not installed", udid);
        return installed;
    }

    public static String resolveUdid() {
        String configured = ConfigReader.get("androidUdid");
        if (!configured.isBlank()) {
            return configured.trim();
        }
        List<String> devices = listReadyDevices();
        if (devices.size() == 1) {
            return devices.get(0);
        }
        if (devices.isEmpty()) {
            return "";
        }
        log.warn("Multiple Android devices connected ({}). Set androidUdid in config.properties.", devices);
        return devices.get(0);
    }

    public static List<String> listReadyDevices() {
        List<String> ready = new ArrayList<>();
        File adb = adbExecutable();
        if (!adb.isFile()) {
            log.warn("adb not found at {}", adb.getAbsolutePath());
            return ready;
        }
        try {
            Process process = new ProcessBuilder(adb.getAbsolutePath(), "devices")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return ready;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("List of devices")) {
                        continue;
                    }
                    // Wireless serials can contain spaces, e.g. "adb-XXX (2)._adb-tls-connect._tcp".
                    // Prefer tab (adb default); otherwise take status as the last token.
                    String serial;
                    String state;
                    int tab = line.indexOf('\t');
                    if (tab > 0) {
                        serial = line.substring(0, tab).trim();
                        state = line.substring(tab + 1).trim().split("\\s+")[0];
                    } else {
                        int lastSpace = line.lastIndexOf(' ');
                        if (lastSpace <= 0) {
                            continue;
                        }
                        serial = line.substring(0, lastSpace).trim();
                        state = line.substring(lastSpace + 1).trim();
                    }
                    if ("device".equals(state) && !serial.isEmpty()) {
                        ready.add(serial);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not list adb devices: {}", e.getMessage());
        }
        return ready;
    }

    private static void applyStayOn(String udid) {
        if (stayOnApplied) {
            return;
        }
        String current = runShell(udid, "settings", "get", "system", "screen_off_timeout").trim();
        if (current.chars().allMatch(Character::isDigit) && !current.isEmpty()) {
            savedScreenTimeout = current;
        }
        ShellResult stayOn = runShellResult(udid, "svc", "power", "stayon", "true");
        ShellResult timeout = runShellResult(
                udid, "settings", "put", "system", "screen_off_timeout", String.valueOf(Integer.MAX_VALUE));
        stayOnApplied = stayOn.exitCode == 0 && timeout.exitCode == 0;
        if (stayOnApplied) {
            log.info("Screen stays on while {} is plugged in", udid);
        } else {
            log.warn("Could not keep the screen on for {}: {}", udid, stayOn.output);
        }
    }

    private static void unlockWithPin(String udid) {
        String pin = ConfigReader.get("deviceLockPin", "").trim();
        if (pin.isEmpty() || !pin.chars().allMatch(Character::isDigit)) {
            return;
        }
        int[] size = screenSize(udid);
        if (size != null) {
            int x = size[0] / 2;
            int fromY = (int) (size[1] * 0.82);
            int toY = (int) (size[1] * 0.28);
            runShell(udid, "input", "swipe",
                    Integer.toString(x), Integer.toString(fromY),
                    Integer.toString(x), Integer.toString(toY), "300");
            pause(AWAKE_POLL_MS * 2);
        }
        log.info("Unlocking phone screen with configured device PIN");
        for (int i = 0; i < pin.length(); i++) {
            runShell(udid, "input", "keyevent", Integer.toString(7 + (pin.charAt(i) - '0')));
        }
        runShell(udid, "input", "keyevent", "66");
        waitUntilAwake(udid);
    }

    private static boolean isScreenOff(String udid) {
        String power = runShell(udid, "dumpsys power | grep -E 'mWakefulness=|Display Power:'");
        return power.contains("mWakefulness=Asleep")
                || power.contains("mWakefulness=Dozing")
                || power.contains("Display Power: state=OFF");
    }

    private static boolean isKeyguardShowing(String udid) {
        String window = runShell(udid,
                "dumpsys window | grep -E 'mDreamingLockscreen=|isKeyguardShowing=|mKeyguardShowing=|mKeyguardShown='");
        return window.contains("mDreamingLockscreen=true")
                || window.contains("isKeyguardShowing=true")
                || window.contains("mKeyguardShowing=true")
                || window.contains("mKeyguardShown=true");
    }

    private static int[] screenSize(String udid) {
        String output = runShell(udid, "wm", "size");
        int marker = output.indexOf("Physical size:");
        if (marker < 0) {
            return null;
        }
        String size = output.substring(marker + "Physical size:".length()).trim();
        int lineEnd = size.indexOf('\n');
        if (lineEnd >= 0) {
            size = size.substring(0, lineEnd).trim();
        }
        String[] parts = size.split("x");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void waitUntilAwake(String udid) {
        for (int attempt = 0; attempt < AWAKE_ATTEMPTS; attempt++) {
            if (!isScreenOff(udid)) {
                return;
            }
            pause(AWAKE_POLL_MS);
        }
    }

    private static void pause(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String runShell(String udid, String... shellArgs) {
        return runShellResult(udid, shellArgs).output;
    }

    private static ShellResult runShellResult(String udid, String... shellArgs) {
        File adb = adbExecutable();
        List<String> command = new ArrayList<>();
        command.add(adb.getAbsolutePath());
        command.add("-s");
        command.add(udid);
        command.add("shell");
        Collections.addAll(command, shellArgs);
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ShellResult(1, "adb timed out");
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }
            return new ShellResult(process.exitValue(), output.toString());
        } catch (Exception e) {
            log.warn("adb shell failed: {}", e.getMessage());
            return new ShellResult(1, e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static final class ShellResult {
        private final int exitCode;
        private final String output;

        private ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private static File adbExecutable() {
        String sdk = ConfigReader.getAndroidSdkPath();
        if (sdk.isBlank()) {
            return new File("adb");
        }
        return new File(sdk, "platform-tools" + File.separator + "adb.exe");
    }
}
