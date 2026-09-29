package dev.discordshot.render;

/** Anything that goes wrong while driving the headless browser or reading its screenshot. */
public final class RenderException extends Exception {

    public RenderException(String message) {
        super(message);
    }

    public RenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
