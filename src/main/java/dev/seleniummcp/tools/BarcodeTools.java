package dev.seleniummcp.tools;

import com.google.zxing.*;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import dev.seleniummcp.browser.BrowserEvidence;
import dev.seleniummcp.engine.ScenarioEngine;
import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.security.Origins;
import dev.seleniummcp.support.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;

final class BarcodeTools {
    static final List<BarcodeFormat> SUPPORTED_FORMATS = List.of(
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.EAN_13,
            BarcodeFormat.UPC_A,
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX);
    static final List<String> FORMAT_NAMES = SUPPORTED_FORMATS.stream().map(Enum::name).toList();
    static final List<String> STRATEGIES = List.of("css", "xpath");

    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 8_388_608L;
    private static final int QUIET_ZONE = 24;
    private static final int MAX_VALUE_LENGTH = 4096;
    private static final Map<DecodeHintType, Object> HINTS = Map.of(
            DecodeHintType.POSSIBLE_FORMATS, SUPPORTED_FORMATS,
            DecodeHintType.TRY_HARDER, Boolean.TRUE,
            DecodeHintType.CHARACTER_SET, "UTF-8");
    private static final String READY_SCRIPT = """
            const el = arguments[0];
            if (!el || !el.isConnected) return 'STALE';
            const rect = el.getBoundingClientRect();
            if (!(rect.width > 0 && rect.height > 0)) return 'EMPTY';
            if (el.tagName && el.tagName.toLowerCase() === 'img') {
              if (!el.complete) return 'LOADING';
              if (!(el.naturalWidth > 0 && el.naturalHeight > 0)) return 'BROKEN_IMAGE';
            }
            el.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'});
            return 'READY';
            """;

    private final Origins origins;

    BarcodeTools(Origins origins) {
        this.origins = origins;
    }

    record DecodedBarcode(String value, BarcodeFormat format) {}

    static By locator(Map<String, Object> args) {
        String selector = (String) args.get("selector");
        if (selector.isBlank()) throw new ToolFailure("INVALID_SELECTOR", "Supply a nonblank locator.");
        return switch ((String) args.getOrDefault("strategy", "css")) {
            case "css" -> By.cssSelector(selector);
            case "xpath" -> By.xpath(selector);
            default -> throw new ToolFailure("INVALID_INPUT", "Unsupported locator strategy.");
        };
    }

    CallToolResult decodePageBarcode(WebDriver driver, Map<String, Object> args) {
        By by = locator(args);
        WebElement element = new WebDriverWait(driver, ScenarioEngine.limitWait(Duration.ofSeconds(5)))
                .ignoring(StaleElementReferenceException.class).until(d -> {
                    origins.checkContext(d);
                    var matches = d.findElements(by);
                    if (matches.isEmpty()) return null;
                    WebElement first = matches.getFirst();
                    if (!first.isDisplayed()) return null;
                    String state = readiness(d, first);
                    if ("BROKEN_IMAGE".equals(state)) {
                        throw new ToolFailure("ELEMENT_NOT_VISIBLE", "The barcode element is not visible or failed to load.");
                    }
                    return "READY".equals(state) ? first : null;
                });
        DecodedBarcode decoded = decode(BrowserEvidence.elementScreenshot(driver, element, origins));
        String format = decoded.format().name();
        var data = new LinkedHashMap<String, Object>();
        data.put("value", decoded.value());
        data.put("format", format);
        return CallToolResult.builder()
                .addTextContent("Decoded " + format + " barcode: " + decoded.value())
                .structuredContent(data).build();
    }

    CallToolResult assertBarcodeValue(WebDriver driver, Map<String, Object> args) {
        By by = locator(args);
        var matches = driver.findElements(by);
        if (matches.isEmpty()) {
            throw new ToolFailure("ELEMENT_NOT_FOUND", "No element matched the barcode locator.");
        }
        WebElement element = matches.getFirst();
        if (!element.isDisplayed()) {
            throw new ToolFailure("ELEMENT_NOT_VISIBLE", "The barcode element is not visible or failed to load.");
        }
        String state = readiness(driver, element);
        if ("LOADING".equals(state)) {
            element = new WebDriverWait(driver, ScenarioEngine.limitWait(Duration.ofSeconds(5)))
                    .ignoring(StaleElementReferenceException.class).until(d -> {
                        origins.checkContext(d);
                        var current = d.findElements(by);
                        if (current.isEmpty()) {
                            throw new ToolFailure("ELEMENT_NOT_FOUND", "No element matched the barcode locator.");
                        }
                        WebElement first = current.getFirst();
                        String currentState = first.isDisplayed() ? readiness(d, first) : "HIDDEN";
                        if ("LOADING".equals(currentState)) return null;
                        if (!"READY".equals(currentState)) {
                            throw new ToolFailure("ELEMENT_NOT_VISIBLE", "The barcode element is not visible or failed to load.");
                        }
                        return first;
                    });
        } else if (!"READY".equals(state)) {
            throw new ToolFailure("ELEMENT_NOT_VISIBLE", "The barcode element is not visible or failed to load.");
        }
        DecodedBarcode decoded = decode(BrowserEvidence.elementScreenshot(driver, element, origins));
        String expected = (String) args.get("expected");
        String expectedFormat = (String) args.get("expectedFormat");
        String actualFormat = decoded.format().name();
        boolean valueMatches = expected.equals(decoded.value());
        boolean formatMatches = expectedFormat == null || expectedFormat.equals(actualFormat);
        boolean passed = valueMatches && formatMatches;
        String message = passed ? "Expectation satisfied."
                : !valueMatches && !formatMatches ? "Barcode value and format mismatch."
                : !valueMatches ? "Barcode value mismatch."
                : "Barcode format mismatch.";
        var report = new LinkedHashMap<String, Object>();
        report.put("status", passed ? "PASS" : "FAIL");
        report.put("expected", "[redacted]");
        report.put("actual", "[redacted]");
        if (expectedFormat != null) report.put("expectedFormat", expectedFormat);
        report.put("actualFormat", actualFormat);
        report.put("message", message);
        return CallToolResult.builder()
                .addTextContent(report.get("status") + ": " + message)
                .structuredContent(report).build();
    }

    private static String readiness(WebDriver driver, WebElement element) {
        Object result = ((JavascriptExecutor) driver).executeScript(READY_SCRIPT, element);
        return result instanceof String s ? s : "EMPTY";
    }

    static DecodedBarcode decode(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            throw new ToolFailure("BARCODE_NOT_FOUND", "No supported barcode could be decoded from the element.");
        }
        if (pngBytes.length > ExecutionHistory.MAX_IMAGE) {
            throw new ToolFailure("IMAGE_TOO_LARGE", "Captured barcode image exceeds 4 MiB.");
        }
        BufferedImage image = readBoundedPng(pngBytes);
        int[] scales = (image.getWidth() <= 600 && image.getHeight() <= 600) ? new int[]{1, 2} : new int[]{1};
        for (int scale : scales) {
            LuminanceSource source = toLuminance(image, scale, QUIET_ZONE);
            DecodedBarcode decoded = tryDecode(source);
            if (decoded != null) return decoded;
        }
        throw new ToolFailure("BARCODE_NOT_FOUND", "No supported barcode could be decoded from the element.");
    }

    private static BufferedImage readBoundedPng(byte[] pngBytes) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
        if (!readers.hasNext()) {
            throw new ToolFailure("BROWSER_ERROR", "PNG decoder is unavailable.");
        }
        ImageReader reader = readers.next();
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(pngBytes))) {
            reader.setInput(stream, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0) {
                throw new ToolFailure("BARCODE_NOT_FOUND", "Captured element image is empty.");
            }
            if (width > MAX_DIMENSION || height > MAX_DIMENSION || (long) width * height > MAX_PIXELS) {
                throw new ToolFailure("IMAGE_TOO_LARGE", "Captured barcode image exceeds dimension limits.");
            }
            BufferedImage image = reader.read(0);
            if (image == null) {
                throw new ToolFailure("BARCODE_NOT_FOUND", "Captured element image could not be read.");
            }
            return image;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ToolFailure failure) throw failure;
            throw new ToolFailure("BARCODE_NOT_FOUND", "Captured element image could not be read.");
        } finally {
            reader.dispose();
        }
    }

    private static LuminanceSource toLuminance(BufferedImage source, int scale, int padding) {
        int sw = source.getWidth();
        int sh = source.getHeight();
        int[] raw = source.getRGB(0, 0, sw, sh, null, 0, sw);
        int dw = sw * scale + padding * 2;
        int dh = sh * scale + padding * 2;
        int[] pixels = new int[dw * dh];
        Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int argb = raw[y * sw + x];
                int a = (argb >>> 24) & 0xFF;
                int r = (((argb >>> 16) & 0xFF) * a + 255 * (255 - a)) / 255;
                int g = (((argb >>> 8) & 0xFF) * a + 255 * (255 - a)) / 255;
                int b = ((argb & 0xFF) * a + 255 * (255 - a)) / 255;
                int rgb = 0xFF000000 | (r << 16) | (g << 8) | b;
                for (int dy = 0; dy < scale; dy++) {
                    int rowOffset = (padding + y * scale + dy) * dw + padding + x * scale;
                    for (int dx = 0; dx < scale; dx++) {
                        pixels[rowOffset + dx] = rgb;
                    }
                }
            }
        }
        return new RGBLuminanceSource(dw, dh, pixels);
    }

    private static DecodedBarcode tryDecode(LuminanceSource source) {
        var reader = new MultiFormatReader();
        reader.setHints(HINTS);
        for (Binarizer binarizer : List.of(new HybridBinarizer(source), new GlobalHistogramBinarizer(source))) {
            try {
                Result result = reader.decodeWithState(new BinaryBitmap(binarizer));
                if (result != null && result.getText() != null
                        && SUPPORTED_FORMATS.contains(result.getBarcodeFormat())) {
                    if (result.getText().length() > MAX_VALUE_LENGTH) {
                        throw new ToolFailure("BARCODE_VALUE_TOO_LARGE", "Decoded barcode value exceeds 4,096 characters.");
                    }
                    return new DecodedBarcode(result.getText(), result.getBarcodeFormat());
                }
            } catch (com.google.zxing.NotFoundException ignored) {
                // Try the next binarizer or scale.
            } finally {
                reader.reset();
            }
        }
        return null;
    }
}
