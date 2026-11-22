package dev.seleniummcp.tools;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.sun.net.httpserver.HttpServer;
import dev.seleniummcp.browser.BrowserSession;
import dev.seleniummcp.reporting.ExecutionHistory;
import dev.seleniummcp.security.Origins;
import dev.seleniummcp.support.ToolFailure;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BarcodeToolsTest {
    private static final String VAL_CODE128 = "ORDER-2026-9981";
    private static final String VAL_CODE39 = "SKU-42-ALPHA";
    private static final String VAL_EAN13 = "4006381333931";
    private static final String VAL_UPCA = "036000291452";
    private static final String VAL_QR = "https://example.com/item/77";
    private static final String VAL_DATAMATRIX = "DM-LOT-90421";

    private final BrowserSession sessions = new BrowserSession();
    private BrowserTools tools = new BrowserTools(sessions, new Origins(""));

    private CallToolResult call(String name, Map<String, Object> args) {
        var spec = tools.specifications().stream().filter(s -> name.equals(s.tool().name())).findFirst().orElseThrow();
        return spec.callHandler().apply(null, new CallToolRequest(name, args));
    }

    private Map<?, ?> data(String name, Map<String, Object> args) {
        var result = call(name, args);
        assertNotEquals(Boolean.TRUE, result.isError(), () -> name + " " + result.structuredContent());
        return (Map<?, ?>) result.structuredContent();
    }

    private static BitMatrix encode(BarcodeFormat format, String value) throws Exception {
        int width = switch (format) {
            case QR_CODE -> 180;
            case DATA_MATRIX -> 96;
            default -> 320;
        };
        int height = switch (format) {
            case QR_CODE -> 180;
            case DATA_MATRIX -> 96;
            default -> 110;
        };
        return new MultiFormatWriter().encode(value, format, width, height);
    }

    private static byte[] renderPng(BitMatrix matrix, boolean transparentBackground, int scale, int margin) throws Exception {
        int w = matrix.getWidth() * scale + margin * 2;
        int h = matrix.getHeight() * scale + margin * 2;
        int type = transparentBackground ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        var image = new BufferedImage(w, h, type);
        int bg = transparentBackground ? 0x00000000 : 0xFFFFFFFF;
        int fg = 0xFF000000;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) image.setRGB(x, y, bg);
        }
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            image.setRGB(margin + x * scale + dx, margin + y * scale + dy, fg);
                        }
                    }
                }
            }
        }
        var out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out));
        return out.toByteArray();
    }

    private static String renderSvg(String id, BitMatrix matrix, int scale) {
        int w = matrix.getWidth() * scale;
        int h = matrix.getHeight() * scale;
        var svg = new StringBuilder();
        svg.append("<svg id=\"").append(id).append("\" xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(w).append("\" height=\"").append(h).append("\" viewBox=\"0 0 ")
                .append(matrix.getWidth()).append(" ").append(matrix.getHeight())
                .append("\" shape-rendering=\"crispEdges\">");
        for (int y = 0; y < matrix.getHeight(); y++) {
            int runStart = -1;
            for (int x = 0; x <= matrix.getWidth(); x++) {
                boolean on = x < matrix.getWidth() && matrix.get(x, y);
                if (on && runStart < 0) runStart = x;
                else if (!on && runStart >= 0) {
                    svg.append("<rect x=\"").append(runStart).append("\" y=\"").append(y)
                            .append("\" width=\"").append(x - runStart).append("\" height=\"1\" fill=\"#000\"/>");
                    runStart = -1;
                }
            }
        }
        return svg.append("</svg>").toString();
    }

    private static String renderCanvasScript(String id, BitMatrix matrix, int scale) {
        var rows = new StringBuilder("[");
        for (int y = 0; y < matrix.getHeight(); y++) {
            if (y > 0) rows.append(',');
            rows.append('"');
            for (int x = 0; x < matrix.getWidth(); x++) rows.append(matrix.get(x, y) ? '1' : '0');
            rows.append('"');
        }
        rows.append(']');
        return "drawMatrix('" + id + "'," + rows + "," + scale + ");";
    }

    @Test void registersAllToolsWithPreservedAndBarcodeSchemas() {
        var specs = tools.specifications();
        assertEquals(32, specs.size());
        var byName = new HashMap<String, Tool>();
        for (var spec : specs) byName.put(spec.tool().name(), spec.tool());

        assertTrue(byName.containsKey("decode_page_barcode"));
        assertTrue(byName.containsKey("assert_barcode_value"));
        assertEquals(List.of("css", "xpath"),
                ((Map<?, ?>) byName.get("decode_page_barcode").inputSchema().properties().get("strategy")).get("enum"));
        assertEquals(List.of("css", "xpath"),
                ((Map<?, ?>) byName.get("assert_barcode_value").inputSchema().properties().get("strategy")).get("enum"));
        assertEquals(BarcodeTools.FORMAT_NAMES,
                ((Map<?, ?>) byName.get("assert_barcode_value").inputSchema().properties().get("expectedFormat")).get("enum"));
        // Existing tool schemas remain unchanged.
        assertEquals(List.of("css", "id", "name"),
                ((Map<?, ?>) byName.get("wait_for_element").inputSchema().properties().get("strategy")).get("enum"));
        assertEquals(List.of("json", "html"),
                ((Map<?, ?>) byName.get("export_test_report").inputSchema().properties().get("format")).get("enum"));
    }

    @Test void validatesArgumentsBeforeDriverAccess() {
        var invalidDecode = List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of("selector", ""),
                Map.<String, Object>of("selector", "#bc", "strategy", "id"),
                Map.<String, Object>of("selector", "#bc", "extra", "no"));
        for (var args : invalidDecode) {
            var res = call("decode_page_barcode", args);
            assertTrue(res.isError());
            assertEquals("INVALID_INPUT", ((Map<?, ?>) res.structuredContent()).get("code"));
        }
        var invalidAssert = List.of(
                Map.<String, Object>of("selector", "#bc"),
                Map.<String, Object>of("expected", "123"),
                Map.<String, Object>of("selector", "#bc", "expected", "123", "strategy", "name"),
                Map.<String, Object>of("selector", "#bc", "expected", "123", "expectedFormat", "PDF_417"));
        for (var args : invalidAssert) {
            var res = call("assert_barcode_value", args);
            assertTrue(res.isError());
            var body = (Map<?, ?>) res.structuredContent();
            assertEquals("ERROR", body.get("status"));
            assertEquals("INVALID_INPUT", body.get("code"));
        }
        var noSessionDecode = call("decode_page_barcode", Map.of("selector", "#bc"));
        assertTrue(noSessionDecode.isError());
        assertEquals("NO_SESSION", ((Map<?, ?>) noSessionDecode.structuredContent()).get("code"));

        var noSessionAssert = call("assert_barcode_value", Map.of("selector", "#bc", "expected", "123"));
        assertTrue(noSessionAssert.isError());
        var assertBody = (Map<?, ?>) noSessionAssert.structuredContent();
        assertEquals("ERROR", assertBody.get("status"));
        assertEquals("NO_SESSION", assertBody.get("code"));
    }

    @Test void decodesAllSixSupportedFormatsIncludingTransparentPngsAndEnforcesLimits() throws Exception {
        var samples = List.of(
                Map.entry(BarcodeFormat.CODE_128, VAL_CODE128),
                Map.entry(BarcodeFormat.CODE_39, VAL_CODE39),
                Map.entry(BarcodeFormat.EAN_13, VAL_EAN13),
                Map.entry(BarcodeFormat.UPC_A, VAL_UPCA),
                Map.entry(BarcodeFormat.QR_CODE, VAL_QR),
                Map.entry(BarcodeFormat.DATA_MATRIX, VAL_DATAMATRIX));
        for (var entry : samples) {
            BitMatrix matrix = encode(entry.getKey(), entry.getValue());
            int scale = entry.getKey() == BarcodeFormat.DATA_MATRIX ? 4 : 1;
            for (boolean transparent : List.of(false, true)) {
                byte[] png = renderPng(matrix, transparent, scale, 0);
                var decoded = BarcodeTools.decode(png);
                assertEquals(entry.getKey(), decoded.format(), () -> entry.getKey() + " transparent=" + transparent);
                assertEquals(entry.getValue(), decoded.value(), () -> entry.getKey() + " transparent=" + transparent);
            }
        }
        // Blank image and invalid bytes return BARCODE_NOT_FOUND; oversized byte arrays return IMAGE_TOO_LARGE.
        var blank = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(blank, "png", out);
        assertEquals("BARCODE_NOT_FOUND", assertThrows(ToolFailure.class, () -> BarcodeTools.decode(out.toByteArray())).code);
        assertEquals("BARCODE_NOT_FOUND", assertThrows(ToolFailure.class, () -> BarcodeTools.decode(new byte[]{1, 2, 3})).code);
        assertEquals("IMAGE_TOO_LARGE", assertThrows(ToolFailure.class, () -> BarcodeTools.decode(new byte[ExecutionHistory.MAX_IMAGE + 1])).code);
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnabledIfSystemProperty(named = "browser.test", matches = "chrome|firefox|edge")
    class Browser {
        private HttpServer http;
        private String origin;
        private Path artifacts;

        @BeforeAll void setup(@TempDir Path directory) throws Exception {
            artifacts = directory;
            var pngs = new HashMap<String, byte[]>();
            BitMatrix mCode128 = encode(BarcodeFormat.CODE_128, VAL_CODE128);
            BitMatrix mCode39 = encode(BarcodeFormat.CODE_39, VAL_CODE39);
            BitMatrix mEan13 = encode(BarcodeFormat.EAN_13, VAL_EAN13);
            BitMatrix mUpca = encode(BarcodeFormat.UPC_A, VAL_UPCA);
            BitMatrix mQr = encode(BarcodeFormat.QR_CODE, VAL_QR);
            BitMatrix mDataMatrix = encode(BarcodeFormat.DATA_MATRIX, VAL_DATAMATRIX);

            pngs.put("/img/code128.png", renderPng(mCode128, false, 1, 10));
            pngs.put("/img/code39.png", renderPng(mCode39, false, 1, 10));
            pngs.put("/img/ean13.png", renderPng(mEan13, false, 1, 10));
            pngs.put("/img/upca.png", renderPng(mUpca, false, 1, 10));
            pngs.put("/img/qr.png", renderPng(mQr, false, 1, 10));
            pngs.put("/img/datamatrix.png", renderPng(mDataMatrix, false, 4, 12));

            String html = """
                    <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Barcode Fixtures</title>
                    <style>body{background:#fff;margin:20px}img:not([hidden]),svg:not([hidden]),canvas:not([hidden]){display:block;margin:16px 0}</style></head><body>
                    <h1>Barcode Fixtures</h1>
                    <img id="img-code128" src="/img/code128.png" alt="code128">
                    <img id="img-code39" src="/img/code39.png" alt="code39">
                    <img id="img-ean13" src="/img/ean13.png" alt="ean13">
                    <img id="img-upca" src="/img/upca.png" alt="upca">
                    <img id="img-qr" src="/img/qr.png" alt="qr">
                    <img id="img-datamatrix" src="/img/datamatrix.png" alt="datamatrix">
                    <img id="img-broken" src="/img/missing.png" alt="broken">
                    <img id="hidden-qr" src="/img/qr.png" hidden alt="hidden">
                    <div data-kind="svg-container">
                      %s
                      %s
                      %s
                    </div>
                    <canvas id="canvas-code128"></canvas>
                    <canvas id="canvas-ean13"></canvas>
                    <canvas id="canvas-qr"></canvas>
                    <canvas id="canvas-blank" width="160" height="80"></canvas>
                    <div style="height:1200px"></div>
                    <img id="below-fold-qr" src="/img/qr.png" alt="below-fold">
                    <script>
                      function drawMatrix(id, rows, scale) {
                        const c = document.getElementById(id);
                        c.width = rows[0].length * scale;
                        c.height = rows.length * scale;
                        const ctx = c.getContext('2d');
                        ctx.fillStyle = '#000';
                        for (let y = 0; y < rows.length; y++) {
                          const row = rows[y];
                          for (let x = 0; x < row.length; x++) {
                            if (row.charCodeAt(x) === 49) ctx.fillRect(x * scale, y * scale, scale, scale);
                          }
                        }
                      }
                      %s
                      %s
                      %s
                    </script></body></html>
                    """.formatted(
                    renderSvg("svg-code128", mCode128, 1),
                    renderSvg("svg-qr", mQr, 1),
                    renderSvg("svg-datamatrix", mDataMatrix, 4),
                    renderCanvasScript("canvas-code128", mCode128, 1),
                    renderCanvasScript("canvas-ean13", mEan13, 1),
                    renderCanvasScript("canvas-qr", mQr, 1));

            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            origin = "http://127.0.0.1:" + http.getAddress().getPort();
            byte[] pageBytes = html.getBytes(StandardCharsets.UTF_8);
            http.createContext("/", ex -> {
                try (ex) {
                    String path = ex.getRequestURI().getPath();
                    if ("/".equals(path)) {
                        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        ex.sendResponseHeaders(200, pageBytes.length);
                        ex.getResponseBody().write(pageBytes);
                    } else if (pngs.containsKey(path)) {
                        byte[] img = pngs.get(path);
                        ex.getResponseHeaders().set("Content-Type", "image/png");
                        ex.sendResponseHeaders(200, img.length);
                        ex.getResponseBody().write(img);
                    } else {
                        ex.sendResponseHeaders(404, -1);
                    }
                }
            });
            http.start();
            tools = new BrowserTools(sessions, new Origins(origin), new ExecutionHistory(artifacts, 50));
            data("launch_browser", Map.of("browser", System.getProperty("browser.test")));
        }

        @BeforeEach void open() {
            data("navigate", Map.of("url", origin + "/"));
        }

        @AfterAll void stop() {
            sessions.shutdown();
            if (http != null) http.stop(0);
        }

        @Test void decodesAllSixFormatsFromImgElements() {
            var expected = Map.of(
                    "#img-code128", Map.of("value", VAL_CODE128, "format", "CODE_128"),
                    "#img-code39", Map.of("value", VAL_CODE39, "format", "CODE_39"),
                    "#img-ean13", Map.of("value", VAL_EAN13, "format", "EAN_13"),
                    "#img-upca", Map.of("value", VAL_UPCA, "format", "UPC_A"),
                    "#img-qr", Map.of("value", VAL_QR, "format", "QR_CODE"),
                    "#img-datamatrix", Map.of("value", VAL_DATAMATRIX, "format", "DATA_MATRIX"));
            for (var entry : expected.entrySet()) {
                var decoded = data("decode_page_barcode", Map.of("selector", entry.getKey()));
                assertEquals(entry.getValue(), decoded, entry.getKey());
            }
        }

        @Test void decodesSvgCanvasAndXPathAndBelowFoldElements() {
            assertEquals(Map.of("value", VAL_CODE128, "format", "CODE_128"),
                    data("decode_page_barcode", Map.of("selector", "#svg-code128")));
            assertEquals(Map.of("value", VAL_QR, "format", "QR_CODE"),
                    data("decode_page_barcode", Map.of("selector", "//*[@id='svg-qr']", "strategy", "xpath")));
            assertEquals(Map.of("value", VAL_DATAMATRIX, "format", "DATA_MATRIX"),
                    data("decode_page_barcode", Map.of("selector", "#svg-datamatrix")));

            assertEquals(Map.of("value", VAL_CODE128, "format", "CODE_128"),
                    data("decode_page_barcode", Map.of("selector", "#canvas-code128")));
            assertEquals(Map.of("value", VAL_EAN13, "format", "EAN_13"),
                    data("decode_page_barcode", Map.of("selector", "//canvas[@id='canvas-ean13']", "strategy", "xpath")));
            assertEquals(Map.of("value", VAL_QR, "format", "QR_CODE"),
                    data("decode_page_barcode", Map.of("selector", "#canvas-qr")));

            assertEquals(Map.of("value", VAL_QR, "format", "QR_CODE"),
                    data("decode_page_barcode", Map.of("selector", "#below-fold-qr")));
        }

        @Test void assertsPassFailAndErrorStates() {
            var passValue = data("assert_barcode_value", Map.of("selector", "#img-code128", "expected", VAL_CODE128));
            assertEquals("PASS", passValue.get("status"));
            assertEquals("[redacted]", passValue.get("expected"));
            assertEquals("[redacted]", passValue.get("actual"));
            assertEquals("CODE_128", passValue.get("actualFormat"));
            assertFalse(passValue.toString().contains(VAL_CODE128));

            var passWithFormat = data("assert_barcode_value", Map.of(
                    "selector", "//*[@id='canvas-qr']", "strategy", "xpath",
                    "expected", VAL_QR, "expectedFormat", "QR_CODE"));
            assertEquals("PASS", passWithFormat.get("status"));
            assertEquals("QR_CODE", passWithFormat.get("expectedFormat"));
            assertEquals("QR_CODE", passWithFormat.get("actualFormat"));

            var failValue = data("assert_barcode_value", Map.of("selector", "#img-ean13", "expected", "0000000000000"));
            assertEquals("FAIL", failValue.get("status"));
            assertEquals("EAN_13", failValue.get("actualFormat"));
            assertEquals("Barcode value mismatch.", failValue.get("message"));

            var failFormat = data("assert_barcode_value", Map.of(
                    "selector", "#img-upca", "expected", VAL_UPCA, "expectedFormat", "EAN_13"));
            assertEquals("FAIL", failFormat.get("status"));
            assertEquals("EAN_13", failFormat.get("expectedFormat"));
            assertEquals("UPC_A", failFormat.get("actualFormat"));
            assertEquals("Barcode format mismatch.", failFormat.get("message"));

            var failBoth = data("assert_barcode_value", Map.of(
                    "selector", "#svg-datamatrix", "expected", "WRONG", "expectedFormat", "QR_CODE"));
            assertEquals("FAIL", failBoth.get("status"));
            assertEquals("Barcode value and format mismatch.", failBoth.get("message"));

            assertError("ELEMENT_NOT_FOUND", "assert_barcode_value", Map.of("selector", "#does-not-exist", "expected", "X"));
            assertError("ELEMENT_NOT_VISIBLE", "assert_barcode_value", Map.of("selector", "#hidden-qr", "expected", VAL_QR));
            assertError("ELEMENT_NOT_VISIBLE", "assert_barcode_value", Map.of("selector", "#img-broken", "expected", "X"));
            assertError("BARCODE_NOT_FOUND", "assert_barcode_value", Map.of("selector", "#canvas-blank", "expected", "X"));
            assertError("INVALID_SELECTOR", "assert_barcode_value", Map.of("selector", "///[[[", "strategy", "xpath", "expected", "X"));
            assertError("INVALID_SELECTOR", "decode_page_barcode", Map.of("selector", "///[[[", "strategy", "xpath"));
            assertError("BARCODE_NOT_FOUND", "decode_page_barcode", Map.of("selector", "#canvas-blank"));
        }

        @Test void integratesAssertionFailuresWithScenarioEvidenceAndReports() throws Exception {
            var scenario = new HashMap<String, Object>();
            scenario.put("id", "barcode-check-01");
            scenario.put("description", "Validate barcode assertion reporting and evidence");
            scenario.put("continueOnFailure", true);
            scenario.put("steps", List.of(
                    Map.of("tool", "assert_barcode_value", "arguments", Map.of(
                            "selector", "#img-code128", "expected", VAL_CODE128, "expectedFormat", "CODE_128")),
                    Map.of("tool", "assert_barcode_value", "arguments", Map.of(
                            "selector", "#svg-qr", "expected", "SECRET-MISMATCH-VALUE", "expectedFormat", "CODE_39")),
                    Map.of("tool", "assert_barcode_value", "arguments", Map.of(
                            "selector", "#canvas-blank", "expected", "ANY"))));
            var result = data("execute_test_scenario", Map.of("scenario", scenario));
            assertEquals("ERROR", result.get("status"));
            var steps = (List<?>) result.get("steps");
            assertEquals(3, steps.size());
            assertEquals("PASS", ((Map<?, ?>) steps.get(0)).get("status"));
            var failRow = (Map<?, ?>) steps.get(1);
            assertEquals("FAIL", failRow.get("status"));
            assertEquals("QR_CODE", failRow.get("actualFormat"));
            assertEquals("CODE_39", failRow.get("expectedFormat"));
            assertTrue(failRow.containsKey("evidence"));
            var errorRow = (Map<?, ?>) steps.get(2);
            assertEquals("ERROR", errorRow.get("status"));
            assertEquals("BARCODE_NOT_FOUND", errorRow.get("code"));
            assertTrue(errorRow.containsKey("evidence"));

            String id = (String) result.get("executionId");
            var evidence = (List<?>) data("get_execution_evidence", Map.of("executionId", id)).get("evidence");
            assertEquals(2, evidence.size());
            for (String format : List.of("json", "html")) {
                var exported = data("export_test_report", Map.of("executionId", id, "format", format));
                String content = Files.readString(artifacts.resolve((String) exported.get("path")));
                assertFalse(content.contains(VAL_CODE128));
                assertFalse(content.contains("SECRET-MISMATCH-VALUE"));
                assertTrue(content.contains("QR_CODE"));
                assertTrue(content.contains("CODE_39"));
            }
        }

        @Test void enforcesViewportOriginSecurity() {
            sessions.use(d -> ((org.openqa.selenium.JavascriptExecutor) d)
                    .executeScript("document.body.append(document.createElement('iframe'))"));
            var decodeRes = call("decode_page_barcode", Map.of("selector", "#img-qr"));
            assertTrue(decodeRes.isError());
            assertEquals("INVALID_URL", ((Map<?, ?>) decodeRes.structuredContent()).get("code"));

            var assertRes = call("assert_barcode_value", Map.of("selector", "#img-qr", "expected", VAL_QR));
            assertTrue(assertRes.isError());
            var body = (Map<?, ?>) assertRes.structuredContent();
            assertEquals("ERROR", body.get("status"));
            assertEquals("INVALID_URL", body.get("code"));
        }

        private void assertError(String code, String tool, Map<String, Object> args) {
            var res = call(tool, args);
            assertTrue(res.isError(), () -> tool + " expected error " + code + " got " + res.structuredContent());
            var body = (Map<?, ?>) res.structuredContent();
            assertEquals(code, body.get("code"), () -> tool + " " + body);
            if (tool.equals("assert_barcode_value")) {
                assertEquals("ERROR", body.get("status"), () -> tool + " " + body);
            }
        }
    }
}
