package dev.seleniummcp.support;

import org.openqa.selenium.*;

public final class ToolFailure extends RuntimeException {
    public final String code;

    public ToolFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ToolFailure browser(RuntimeException failure) {
        return switch (failure) {
            case ElementClickInterceptedException ignored -> new ToolFailure("ELEMENT_CLICK_INTERCEPTED", "Another element intercepted the click.");
            case ElementNotInteractableException ignored -> new ToolFailure("ELEMENT_NOT_INTERACTABLE", "The element cannot receive this interaction.");
            case StaleElementReferenceException ignored -> new ToolFailure("STALE_ELEMENT", "The page changed. Locate the element again.");
            case InvalidSelectorException ignored -> new ToolFailure("INVALID_SELECTOR", "Supply a valid locator.");
            case NoSuchElementException ignored -> new ToolFailure("ELEMENT_NOT_FOUND", "The requested element or option is unavailable.");
            case TimeoutException ignored -> new ToolFailure("TIMEOUT", "Timed out waiting for the page or element.");
            case NoSuchSessionException ignored -> new ToolFailure("SESSION_LOST", "The browser session has ended.");
            case NoSuchWindowException ignored -> new ToolFailure("WINDOW_NOT_FOUND", "List windows and choose an available handle.");
            case NoSuchFrameException ignored -> new ToolFailure("FRAME_NOT_FOUND", "Choose an available child frame.");
            default -> new ToolFailure("BROWSER_ERROR", "Browser operation failed. Check browser and driver availability.");
        };
    }
}
