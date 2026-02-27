package us.poliscore.polibench.providers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ConfigLoader {
    private static final String CONFIG_FILE = "polibench.properties";
    private static Properties properties;

    public static synchronized void load() {
        if (properties != null)
            return;

        properties = new Properties();
        File configFile = new File(CONFIG_FILE);

        // Try to load from current directory first
        if (configFile.exists() && !configFile.isDirectory()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
                System.out.println("Loaded config from " + configFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Warning: Could not read " + CONFIG_FILE + ": " + e.getMessage());
            }
        } else {
            // Try to load from classpath
            try (java.io.InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                if (is != null) {
                    properties.load(is);
                    System.out.println("Loaded config from classpath");
                }
            } catch (IOException e) {
                System.err.println("Warning: Could not read " + CONFIG_FILE + " from classpath: " + e.getMessage());
            }
        }
    }

    public static String getProperty(String key) {
        if (properties == null)
            load();
        return properties.getProperty(key);
    }

    public static String getProperty(String key, String defaultValue) {
        if (properties == null)
            load();
        return properties.getProperty(key, defaultValue);
    }
}
