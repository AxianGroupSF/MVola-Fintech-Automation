package com.qaframework.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads configuration values from config.properties and system properties.
 * System properties / TestNG parameters take precedence over config file values.
 */
public final class ConfigReader {

    private static final Properties properties = new Properties();
    private static final String CONFIG_FILE = "config.properties";

    static {
        loadProperties();
    }

    private ConfigReader() {
    }

    private static void loadProperties() {
        try (InputStream input = ConfigReader.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (input == null) {
                throw new RuntimeException("Unable to find " + CONFIG_FILE + " in classpath");
            }
            properties.load(input);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load " + CONFIG_FILE, e);
        }
    }

    public static String get(String key) {
        String systemValue = System.getProperty(key);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue.trim();
        }
        return properties.getProperty(key, "").trim();
    }

    public static String get(String key, String defaultValue) {
        String value = get(key);
        return value.isEmpty() ? defaultValue : value;
    }

    public static int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        if (value.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    public static String getPlatformName() {
        String platform = get("platform");
        if (!platform.isEmpty()) {
            return platform.toLowerCase();
        }
        return get("platformName", "android").toLowerCase();
    }

    public static String getAndroidSdkPath() {
        return get("androidSdkPath");
    }

    public static boolean isAndroid() {
        return "android".equalsIgnoreCase(getPlatformName());
    }

    public static boolean isIOS() {
        return "ios".equalsIgnoreCase(getPlatformName());
    }

    public static void setPlatformName(String platform) {
        if (platform != null && !platform.isBlank()) {
            System.setProperty("platformName", platform.trim().toLowerCase());
        }
    }
}
