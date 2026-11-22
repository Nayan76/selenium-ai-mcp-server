package dev.seleniummcp.security;

import dev.seleniummcp.support.ToolFailure;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

public final class Origins {
    private final Set<String> allowed = new HashSet<>();

    public Origins(String configured) {
        if (configured.isBlank()) return;
        for (String entry : configured.split(",", -1)) {
            URI uri = parse(entry.strip());
            if (!(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Configure origins without paths, queries or fragments.");
            }
            allowed.add(origin(uri));
        }
    }

    public void check(String url) {
        originOf(url);
    }

    public String originOf(String url) {
        String resolved = origin(parse(url));
        if (!allowed.contains(resolved)) {
            throw new ToolFailure("ORIGIN_DENIED", "Choose an origin listed in SELENIUM_ALLOWED_ORIGINS.");
        }
        return resolved;
    }

    public void checkContext(WebDriver driver) {
        check(driver.getCurrentUrl());
        // WebDriver's current URL describes the top-level window even inside a frame.
        var js = (JavascriptExecutor) driver;
        // The browser protects Location and Window.top across origins. Read the
        // actual top Location, even for HTTP frames, to reject opaque sandboxes.
        Object verified = js.executeScript("""
                try {
                  const url = window.location.href;
                  if (window === window.top) return url;
                  const topLocation = window.top.location;
                  if (url === "about:srcdoc") {
                    // Index zero is guaranteed to be a real child if this window
                    // is a descendant. Avoid page-controlled DOM metadata.
                    return window.top[0] === window ? topLocation.href : null;
                  }
                  return window.location.origin === topLocation.origin ? url : null;
                } catch (_) { return null; }
                """);
        if (!(verified instanceof String url)) {
            throw new ToolFailure("ORIGIN_DENIED", "The active frame origin could not be verified.");
        }
        check(url);
    }

    private static URI parse(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new ToolFailure("INVALID_URL", "Supply an absolute HTTP or HTTPS URL.");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() > 65535) {
            throw new ToolFailure("INVALID_URL", "Supply an absolute HTTP or HTTPS URL without credentials.");
        }
        String host = uri.getHost();
        if (host.contains("%") || uri.getPort() == 0 || uri.getRawAuthority().endsWith(":")
                || ambiguousNumericHost(host)) {
            throw new ToolFailure("INVALID_URL", "Use a canonical host and a valid port.");
        }
        return uri;
    }

    private static boolean ambiguousNumericHost(String host) {
        // Browser URL parsers accept shortened, hexadecimal and octal IPv4 representations.
        if (!host.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*\\.?")) return false;
        return !host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}");
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int defaultPort = scheme.equals("https") ? 443 : 80;
        int port = uri.getPort() == -1 ? defaultPort : uri.getPort();
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }
}
