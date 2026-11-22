package dev.seleniummcp.browser;

import dev.seleniummcp.support.ToolFailure;
import java.time.Duration;
import java.util.function.Function;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.slf4j.LoggerFactory;

public final class BrowserSession {
    private WebDriver driver;
    private String browserType = "none";

    public BrowserSession() {}

    public synchronized String browserType() { return browserType; }
    private boolean stopped;

    public synchronized void launch(String browser, boolean headless) {
        if (stopped) throw new ToolFailure("SHUTTING_DOWN", "Server is shutting down.");
        if (driver != null) throw new ToolFailure("SESSION_ACTIVE", "Close the active browser first.");
        driver = switch (browser) {
            case "chrome" -> {
                var options = new ChromeOptions();
                options.setAcceptInsecureCerts(false);
                if (headless) options.addArguments("--headless=new");
                yield new ChromeDriver(options);
            }
            case "edge" -> {
                var options = new EdgeOptions();
                options.setAcceptInsecureCerts(false);
                if (headless) options.addArguments("--headless=new");
                yield new EdgeDriver(options);
            }
            case "firefox" -> {
                var options = new FirefoxOptions();
                options.setAcceptInsecureCerts(false);
                if (headless) options.addArguments("-headless");
                yield new FirefoxDriver(options);
            }
            default -> throw new ToolFailure("INVALID_INPUT", "Choose chrome, firefox or edge.");
        };
        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(20));
            browserType = browser;
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public synchronized <T> T use(Function<WebDriver, T> action) {
        if (driver == null) throw new ToolFailure("NO_SESSION", "Launch a browser first.");
        return action.apply(driver);
    }

    public synchronized void close() {
        if (driver != null) {
            try {
                driver.quit();
            } finally {
                driver = null;
                browserType = "none";
            }
        }
    }

    public synchronized boolean active() {
        return driver != null;
    }

    public synchronized void closeQuietly() {
        try {
            close();
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(BrowserSession.class).warn("Driver cleanup failed ({})", failure.getClass().getSimpleName());
        }
    }

    public synchronized void shutdown() {
        stopped = true;
        closeQuietly();
    }
}
