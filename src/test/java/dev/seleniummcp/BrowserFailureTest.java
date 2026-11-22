package dev.seleniummcp;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.openqa.selenium.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserFailureTest {
    static Stream<Arguments> classifications() {
        String privateValue = "private-driver-diagnostic";
        return Stream.of(
                Arguments.of(new ElementClickInterceptedException(privateValue), "ELEMENT_CLICK_INTERCEPTED"),
                Arguments.of(new ElementNotInteractableException(privateValue), "ELEMENT_NOT_INTERACTABLE"),
                Arguments.of(new StaleElementReferenceException(privateValue), "STALE_ELEMENT"),
                Arguments.of(new NoSuchElementException(privateValue), "ELEMENT_NOT_FOUND"),
                Arguments.of(new TimeoutException(privateValue), "TIMEOUT"),
                Arguments.of(new InvalidSelectorException(privateValue), "INVALID_SELECTOR"),
                Arguments.of(new WebDriverException(privateValue), "BROWSER_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("classifications")
    void reportsOnlySanitizedDiagnostics(RuntimeException original, String expected) {
        var result = ToolFailure.browser(original);
        assertEquals(expected, result.code);
        assertFalse(result.getMessage().contains("private-driver-diagnostic"));
        assertNull(result.getCause());
    }
}
