package dev.seleniummcp.api;

import dev.seleniummcp.support.ToolFailure;

/** Configure the JDK parser before the stdio process initializes its HTTP clients. */
public final class ApiRuntime {
    private static final String HEADER_PROPERTY = "jdk.http.maxHeaderSize";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final Runtime.Version MINIMUM = Runtime.Version.parse("21.0.5");

    private ApiRuntime() {}

    public static boolean supportsApi() {
        return supportsHeaderLimit(Runtime.version());
    }

    public static boolean validOrigins(String origins, boolean allowLoopback) {
        return new ApiRequestValidator(origins, allowLoopback).hasValidConfiguration();
    }

    public static void configureHeaderParser() {
        if (supportsHeaderLimit(Runtime.version()) && !hasSafeLimit()) {
            System.setProperty(HEADER_PROPERTY, Integer.toString(MAX_HEADER_BYTES));
        }
    }

    static boolean supportsHeaderLimit(Runtime.Version version) {
        return version.feature() == 21 && version.compareToIgnoreOptional(MINIMUM) >= 0 && version.pre().isEmpty();
    }

    public static void requireSafeParser() {
        if (!supportsHeaderLimit(Runtime.version())) {
            throw new ToolFailure("API_RUNTIME_UNSUPPORTED", "API requests require Java 21.0.5 or a newer Java 21 update.");
        }
        if (!hasSafeLimit()) {
            throw new ToolFailure("API_CONFIGURATION_INVALID", "API requests require a JDK HTTP header limit of at most 16384 bytes.");
        }
    }

    private static boolean hasSafeLimit() {
        try {
            String configured = System.getProperty(HEADER_PROPERTY, "");
            int limit = Integer.parseInt(configured);
            return limit > 0 && limit <= MAX_HEADER_BYTES && configured.equals(Integer.toString(limit));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
