package dev.seleniummcp.api;

import dev.seleniummcp.support.ToolFailure;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ApiRequestValidator {
    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final int MAX_URL = 2048;
    static final int MAX_BODY = 64 * 1024;
    static final int MAX_HEADERS = 32;
    static final int MAX_HEADER_NAME = 64;
    static final int MAX_HEADER_VALUE = 2048;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final Pattern IPV4 = Pattern.compile("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}");
    private static final Pattern HEADER = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}");
    private static final Set<String> RESERVED = Set.of("accept-encoding", "connection", "content-encoding",
            "content-length", "expect", "forwarded", "host", "http2-settings", "keep-alive", "te",
            "trailer", "transfer-encoding", "upgrade");

    private final Set<String> origins;
    private final boolean allowLoopback;
    private final boolean validConfiguration;

    ApiRequestValidator(String configuredOrigins, boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
        Set<String> configured = new HashSet<>();
        boolean valid = true;
        try {
            if (!configuredOrigins.isBlank()) {
                for (String entry : configuredOrigins.split(",", -1)) {
                    URI uri = endpoint(entry.strip());
                    if (!(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")) || uri.getRawQuery() != null) {
                        throw invalid("Configure API origins without paths or queries.");
                    }
                    configured.add(origin(uri));
                }
            }
        } catch (ToolFailure ignored) {
            valid = false;
        }
        validConfiguration = valid;
        origins = valid ? Set.copyOf(configured) : Set.of();
    }

    record Request(HttpRequest httpRequest, String origin) {}

    boolean hasValidConfiguration() {
        return validConfiguration;
    }

    Request validate(Map<String, Object> args) {
        if (!validConfiguration) {
            throw new ToolFailure("API_CONFIGURATION_INVALID", "Check the configured API origin list.");
        }
        if (!(args.get("method") instanceof String method) || !METHODS.contains(method)) {
            throw invalid("Choose GET, POST, PUT, PATCH or DELETE.");
        }
        if (!(args.get("url") instanceof String url)) throw invalid("Supply an API URL.");
        URI uri = endpoint(url);
        checkAddress(uri);
        String origin = origin(uri);
        if (!origins.contains(origin)) {
            throw new ToolFailure("API_ORIGIN_DENIED", "Choose an origin in SELENIUM_API_ALLOWED_ORIGINS.");
        }
        int timeout = integer(args.getOrDefault("timeoutSeconds", 15), 1, 30);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeout))
                .header("Accept-Encoding", "identity");
        Set<String> names = addHeaders(builder, args.getOrDefault("headers", Map.of()));
        var body = HttpRequest.BodyPublishers.noBody();
        if (args.containsKey("body")) {
            if (!(args.get("body") instanceof String value) || value.length() > MAX_BODY
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
                throw invalid("Supply a valid UTF-8 body of at most 65536 bytes.");
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BODY) throw invalid("API request body exceeds 65536 bytes.");
            body = HttpRequest.BodyPublishers.ofByteArray(bytes);
            if (!names.contains("content-type")) builder.header("Content-Type", "application/json");
        }
        return new Request(builder.method(method, body).build(), origin);
    }

    private static Set<String> addHeaders(HttpRequest.Builder builder, Object value) {
        if (!(value instanceof Map<?, ?> headers) || headers.size() > MAX_HEADERS) {
            throw invalid("Supply at most 32 request headers.");
        }
        Set<String> names = new HashSet<>();
        int bytes = 0;
        for (var header : headers.entrySet()) {
            if (!(header.getKey() instanceof String name) || !HEADER.matcher(name).matches()
                    || !(header.getValue() instanceof String field) || field.length() > MAX_HEADER_VALUE
                    || field.chars().anyMatch(c -> c < 32 || c > 126)) {
                throw invalid("Supply token header names and printable ASCII values.");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (RESERVED.contains(lower) || lower.startsWith("proxy-") || lower.startsWith("x-forwarded-")
                    || !names.add(lower)) {
                throw invalid("A request header is reserved or duplicated.");
            }
            bytes += name.length() + field.length() + 4;
            if (bytes > MAX_HEADER_BYTES) throw invalid("Request headers exceed 8192 bytes.");
            builder.header(name, field);
        }
        return names;
    }

    private static URI endpoint(String url) {
        if (url.length() > MAX_URL) throw invalid("API URL exceeds 2048 characters.");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ignored) {
            throw invalid("Supply an absolute HTTP or HTTPS API URL.");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) {
            throw invalid("Supply an absolute HTTP or HTTPS API URL without credentials or a fragment.");
        }
        return uri;
    }

    private void checkAddress(URI uri) {
        String host = uri.getHost();
        // An IPv4 literal is parsed directly by the JDK. No hostname is passed to DNS.
        if (!IPV4.matcher(host).matches()) throw deniedAddress();
        int[] octets = Arrays.stream(host.split("\\.")).mapToInt(Integer::parseInt).toArray();
        if (Arrays.stream(octets).anyMatch(octet -> octet > 255)) throw deniedAddress();
        if (allowLoopback && host.equals("127.0.0.1") && uri.getScheme().equalsIgnoreCase("http")) return;
        int a = octets[0], b = octets[1], c = octets[2];
        if (a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && ((b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99) || b == 168))
                || (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                || (a == 203 && b == 0 && c == 113)
                || host.equals("168.63.129.16")) {
            throw deniedAddress();
        }
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        return scheme + "://" + uri.getHost() + ":" + port;
    }

    static int integer(Object value, int min, int max) {
        if (!(value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < min || ((Number) value).longValue() > max) {
            throw invalid("Supply an integer between " + min + " and " + max + ".");
        }
        return ((Number) value).intValue();
    }

    static ToolFailure invalid(String message) {
        return new ToolFailure("INVALID_INPUT", message);
    }

    private static ToolFailure deniedAddress() {
        return new ToolFailure("API_ADDRESS_DENIED", "Use an allowed public IPv4 literal or an enabled HTTP loopback fixture.");
    }
}
