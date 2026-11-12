package com.qaframework.server;

import com.qaframework.config.ConfigReader;
import com.qaframework.utils.LoggerManager;
import io.appium.java_client.service.local.AppiumDriverLocalService;
import io.appium.java_client.service.local.AppiumServiceBuilder;
import io.appium.java_client.service.local.flags.GeneralServerFlag;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages Appium server lifecycle - start, stop, and health check.
 */
public final class AppiumServerManager {

    private static final Logger log = LoggerManager.getLogger(AppiumServerManager.class);
    private static AppiumDriverLocalService service;
    private static final Object lock = new Object();

    private AppiumServerManager() {
    }

    public static void startServer() {
        synchronized (lock) {
            String ip = ConfigReader.get("appiumServerIP", "127.0.0.1");
            int port = ConfigReader.getInt("appiumServerPort", 4723);

            if (isServerRunning(ip, port)) {
                log.info("Appium server already listening at {}:{}", ip, port);
                return;
            }

            // UiAutomator2 require() is a cold disk read. The client default is 20s, which
            // closes the process before port 4723 opens and surfaces as "connection refused".
            AppiumServiceBuilder builder = new AppiumServiceBuilder()
                    .withIPAddress(ip)
                    .usingPort(port)
                    .withArgument(GeneralServerFlag.SESSION_OVERRIDE)
                    .withArgument(GeneralServerFlag.LOG_LEVEL, "info")
                    .withTimeout(Duration.ofSeconds(120))
                    .withLogFile(serverLogFile())
                    .withEnvironment(androidServerEnvironment());

            service = AppiumDriverLocalService.buildService(builder);
            service.start();

            if (!service.isRunning()) {
                throw new RuntimeException("Failed to start Appium server at " + ip + ":" + port);
            }

            log.info("Appium server started at {}:{}", ip, port);
        }
    }

    public static void stopServer() {
        synchronized (lock) {
            if (service != null && service.isRunning()) {
                service.stop();
                log.info("Appium server stopped");
            }
        }
    }

    public static String getServerUrl() {
        String ip = ConfigReader.get("appiumServerIP", "127.0.0.1");
        int port = ConfigReader.getInt("appiumServerPort", 4723);
        return "http://" + ip + ":" + port;
    }

    public static boolean isServerRunning(String ip, int port) {
        try {
            URL url = new URL("http://" + ip + ":" + port + "/status");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            return responseCode == 200;
        } catch (IOException e) {
            return false;
        }
    }

    private static File serverLogFile() {
        File logFile = new File("target", "appium-server.log");
        File parent = logFile.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        return logFile;
    }

    private static Map<String, String> androidServerEnvironment() {
        Map<String, String> env = new HashMap<>(System.getenv());
        String sdk = ConfigReader.getAndroidSdkPath();
        if (sdk.isBlank()) {
            return env;
        }

        File sdkRoot = new File(sdk);
        if (!sdkRoot.isDirectory()) {
            log.warn("androidSdkPath is not a directory: {}", sdkRoot.getAbsolutePath());
            return env;
        }

        String sdkPath = sdkRoot.getAbsolutePath();
        env.put("ANDROID_HOME", sdkPath);
        env.put("ANDROID_SDK_ROOT", sdkPath);

        File platformTools = new File(sdkRoot, "platform-tools");
        if (platformTools.isDirectory()) {
            String pathKey = env.containsKey("Path") ? "Path" : "PATH";
            String path = env.getOrDefault(pathKey, "");
            String pt = platformTools.getAbsolutePath();
            if (!path.toLowerCase().contains(pt.toLowerCase())) {
                env.put(pathKey, pt + File.pathSeparator + path);
            }
        }

        log.info("Appium server will use ANDROID_HOME={}", sdkPath);
        return env;
    }

    public static void waitForServerReady(Duration timeout) {
        String ip = ConfigReader.get("appiumServerIP", "127.0.0.1");
        int port = ConfigReader.getInt("appiumServerPort", 4723);
        long endTime = System.currentTimeMillis() + timeout.toMillis();

        while (System.currentTimeMillis() < endTime) {
            if (isServerRunning(ip, port)) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for Appium server", e);
            }
        }
        throw new RuntimeException("Appium server not ready within " + timeout.toSeconds() + " seconds");
    }
}
