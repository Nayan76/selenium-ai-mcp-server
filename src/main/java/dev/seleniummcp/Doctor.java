package dev.seleniummcp;

import dev.seleniummcp.api.ApiRuntime;
import dev.seleniummcp.collection.management.SavedCollectionTools;
import dev.seleniummcp.security.Origins;
import dev.seleniummcp.security.WindowsMetadata;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class Doctor {
    private Doctor() {}

    static int run(String[] args, PrintStream output, Map<String, String> env) {
        if (args.length != 1) {
            output.println("FAIL Usage: java -jar selenium-mcp.jar --doctor");
            return 2;
        }

        String vendor = System.getProperty("java.vendor", "");
        if (!List.of("Eclipse Adoptium", "Oracle Corporation", "Ubuntu", "Debian", "Red Hat, Inc.",
                "Microsoft", "Azul Systems, Inc.", "Amazon.com Inc.", "BellSoft", "IBM Corporation")
                .contains(vendor)) vendor = "unrecognized";
        boolean java21 = Runtime.version().feature() == 21;
        output.printf("%s Java %s (%s)%n", java21 ? "PASS" : "FAIL", Runtime.version(), vendor);
        output.println(ApiRuntime.supportsApi() ? "PASS API runtime supports the HTTP header limit"
                : "WARN API tools require Java 21.0.5 or a newer Java 21 update");

        String os = System.getProperty("os.name", "");
        output.println(os.startsWith("Windows") ? "PASS Platform: Windows"
                : "Linux".equals(os) ? "PASS Platform: Linux"
                : os.startsWith("Mac") ? "WARN Platform: macOS (saved storage unavailable)"
                : "WARN Platform: unverified");
        output.println(browserDetected(os, env) ? "PASS Browser executable found"
                : "WARN Browser executable not detected in standard locations");

        boolean valid = java21;
        try {
            String browserOrigins = env.getOrDefault("SELENIUM_ALLOWED_ORIGINS", "");
            new Origins(browserOrigins);
            output.println(browserOrigins.isBlank() ? "SKIP Browser origin list is empty" : "PASS Browser origin list");
        } catch (RuntimeException ignored) {
            output.println("FAIL Browser origin list is invalid");
            valid = false;
        }

        String apiOrigins = env.getOrDefault("SELENIUM_API_ALLOWED_ORIGINS", "");
        boolean loopback = Boolean.parseBoolean(env.get("SELENIUM_API_ALLOW_LOOPBACK"));
        if (!ApiRuntime.validOrigins(apiOrigins, loopback)) {
            output.println("FAIL API origin list is invalid");
            valid = false;
        } else {
            output.println(apiOrigins.isBlank() ? "SKIP API origin list is empty" : "PASS API origin list");
        }

        switch (SavedCollectionTools.checkConfiguration(env)) {
            case UNCONFIGURED -> output.println("SKIP Saved environment configuration and store are unset");
            case READY -> output.println("PASS Saved environment configuration and store metadata");
            case INVALID -> {
                output.println("FAIL Saved environment configuration or store is invalid or inaccessible");
                valid = false;
            }
        }
        return valid ? 0 : 1;
    }

    private static boolean browserDetected(String os, Map<String, String> env) {
        if (os.startsWith("Windows")) {
            for (String key : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String root = env.get(key);
                if (executable(root, "Google/Chrome/Application/chrome.exe")
                        || executable(root, "Microsoft/Edge/Application/msedge.exe")
                        || executable(root, "Mozilla Firefox/firefox.exe")) return true;
            }
        }
        if (os.startsWith("Mac") && (executable("/Applications", "Google Chrome.app/Contents/MacOS/Google Chrome")
                || executable("/Applications", "Firefox.app/Contents/MacOS/firefox")
                || executable("/Applications", "Microsoft Edge.app/Contents/MacOS/Microsoft Edge"))) return true;
        if (!"Linux".equals(os)) return false;
        for (String dir : List.of("/usr/bin", "/usr/local/bin", "/opt/google/chrome", "/opt/microsoft/msedge")) {
            for (String name : List.of("google-chrome", "chrome", "chromium", "firefox", "microsoft-edge", "msedge")) {
                if (executable(dir, name)) return true;
            }
        }
        return false;
    }

    private static boolean executable(String directory, String name) {
        if (directory == null || directory.isBlank()) return false;
        try {
            Path root = Path.of(directory);
            if (!root.isAbsolute()) return false;
            Path file = root.resolve(name).normalize();
            if (com.sun.jna.Platform.isWindows() && !WindowsMetadata.isLocal(file, false)) return false;
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(file);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

}
