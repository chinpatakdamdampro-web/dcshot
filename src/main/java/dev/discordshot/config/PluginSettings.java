package dev.discordshot.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A typed, validated snapshot of config.yml, taken once at startup. Keeping every setting
 * as a plain value here - instead of re-reading FileConfiguration all over the codebase -
 * means the rest of the plugin never has to worry about missing keys, wrong types, or
 * values changing underneath it mid-render.
 */
public final class PluginSettings {

    private final String botToken;
    private final String allowedChannelId;
    private final String guildId;
    private final String blueMapUrl;
    private final String publicMapUrl;
    private final String defaultWorld;
    private final Map<String, String> mapIds;
    private final String viewTemplate;

    private final int screenshotWidth;
    private final int screenshotHeight;
    private final long renderWaitMs;
    private final long pageTimeoutMs;
    private final double defaultDistance;
    private final double rotation;
    private final double tilt;
    private final boolean hideUi;
    private final String hideUiCss;
    private final String imageFormat;

    private final int maxQueue;
    private final int cooldownSeconds;

    private final String browserExecutablePath;
    private final List<String> browserExtraArgs;

    private PluginSettings(Builder b) {
        this.botToken = b.botToken;
        this.allowedChannelId = b.allowedChannelId;
        this.guildId = b.guildId;
        this.blueMapUrl = b.blueMapUrl;
        this.publicMapUrl = b.publicMapUrl;
        this.defaultWorld = b.defaultWorld;
        this.mapIds = b.mapIds;
        this.viewTemplate = b.viewTemplate;
        this.screenshotWidth = b.screenshotWidth;
        this.screenshotHeight = b.screenshotHeight;
        this.renderWaitMs = b.renderWaitMs;
        this.pageTimeoutMs = b.pageTimeoutMs;
        this.defaultDistance = b.defaultDistance;
        this.rotation = b.rotation;
        this.tilt = b.tilt;
        this.hideUi = b.hideUi;
        this.hideUiCss = b.hideUiCss;
        this.imageFormat = b.imageFormat;
        this.maxQueue = b.maxQueue;
        this.cooldownSeconds = b.cooldownSeconds;
        this.browserExecutablePath = b.browserExecutablePath;
        this.browserExtraArgs = b.browserExtraArgs;
    }

    public static PluginSettings load(FileConfiguration cfg, Logger log) throws InvalidConfigException {
        Builder b = new Builder();

        b.botToken = trimToNull(cfg.getString("bot-token"));
        if (b.botToken == null || b.botToken.equals("PASTE_YOUR_BOT_TOKEN_HERE")) {
            throw new InvalidConfigException("bot-token is missing. Paste your Discord bot token into config.yml.");
        }

        String renderMethod = cfg.getString("render-method", "BLUEMAP");
        if (renderMethod == null || !renderMethod.equalsIgnoreCase("BLUEMAP")) {
            throw new InvalidConfigException(
                    "render-method '" + renderMethod + "' is not supported yet. Only BLUEMAP is implemented.");
        }

        b.allowedChannelId = safeString(cfg.getString("allowed-channel-id", ""));
        b.guildId = safeString(cfg.getString("guild-id", ""));

        b.blueMapUrl = safeString(cfg.getString("bluemap-url", "http://localhost:8100"));
        if (b.blueMapUrl.isEmpty()) {
            throw new InvalidConfigException("bluemap-url cannot be empty.");
        }
        b.blueMapUrl = stripTrailingSlash(b.blueMapUrl);

        b.publicMapUrl = stripTrailingSlash(safeString(cfg.getString("public-map-url", "")));
        b.defaultWorld = safeString(cfg.getString("default-world", ""));

        b.mapIds = new HashMap<>();
        if (cfg.isConfigurationSection("map-ids")) {
            for (String key : cfg.getConfigurationSection("map-ids").getKeys(false)) {
                Object value = cfg.getConfigurationSection("map-ids").get(key);
                if (value != null) {
                    b.mapIds.put(key, String.valueOf(value));
                }
            }
        }

        b.viewTemplate = safeString(cfg.getString("view-template",
                "#{map}:{x}:{y}:{z}:{distance}:{rotation}:{tilt}:0:0:perspective"));

        b.screenshotWidth = clamp(cfg.getInt("screenshot.width", 1280), 256, 3840);
        b.screenshotHeight = clamp(cfg.getInt("screenshot.height", 720), 256, 2160);
        b.renderWaitMs = Math.max(0, cfg.getLong("screenshot.render-wait-ms", 3000));
        b.pageTimeoutMs = Math.max(1000, cfg.getLong("screenshot.page-timeout-ms", 30000));
        b.defaultDistance = cfg.getDouble("screenshot.default-distance", 120.0);
        b.rotation = cfg.getDouble("screenshot.rotation", 0.0);
        b.tilt = cfg.getDouble("screenshot.tilt", 0.75);
        b.hideUi = cfg.getBoolean("screenshot.hide-ui", true);
        b.hideUiCss = safeString(cfg.getString("screenshot.hide-ui-css", ""));
        String format = safeString(cfg.getString("screenshot.format", "png")).toLowerCase();
        b.imageFormat = (format.equals("jpeg") || format.equals("jpg")) ? "jpeg" : "png";

        b.maxQueue = clamp(cfg.getInt("limits.max-queue", 4), 1, 32);
        b.cooldownSeconds = Math.max(0, cfg.getInt("limits.cooldown-seconds", 10));

        b.browserExecutablePath = safeString(cfg.getString("browser.executable-path", ""));
        b.browserExtraArgs = cfg.getStringList("browser.extra-args");

        return new PluginSettings(b);
    }

    // --- accessors -------------------------------------------------------

    public String botToken() { return botToken; }
    public String allowedChannelId() { return allowedChannelId; }
    public String guildId() { return guildId; }
    public String blueMapUrl() { return blueMapUrl; }
    public String publicMapUrl() { return publicMapUrl; }
    public String defaultWorld() { return defaultWorld; }
    public String viewTemplate() { return viewTemplate; }
    public String mapIdFor(String worldName) { return mapIds.getOrDefault(worldName, worldName); }
    public int screenshotWidth() { return screenshotWidth; }
    public int screenshotHeight() { return screenshotHeight; }
    public long renderWaitMs() { return renderWaitMs; }
    public long pageTimeoutMs() { return pageTimeoutMs; }
    public double defaultDistance() { return defaultDistance; }
    public double rotation() { return rotation; }
    public double tilt() { return tilt; }
    public boolean hideUi() { return hideUi; }
    public String hideUiCss() { return hideUiCss; }
    public String imageFormat() { return imageFormat; }
    public int maxQueue() { return maxQueue; }
    public int cooldownSeconds() { return cooldownSeconds; }
    public String browserExecutablePath() { return browserExecutablePath; }
    public List<String> browserExtraArgs() { return browserExtraArgs; }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String safeString(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Thrown when config.yml is missing something required or has an unusable value. */
    public static final class InvalidConfigException extends Exception {
        public InvalidConfigException(String message) {
            super(message);
        }
    }

    private static final class Builder {
        String botToken;
        String allowedChannelId;
        String guildId;
        String blueMapUrl;
        String publicMapUrl;
        String defaultWorld;
        Map<String, String> mapIds;
        String viewTemplate;
        int screenshotWidth;
        int screenshotHeight;
        long renderWaitMs;
        long pageTimeoutMs;
        double defaultDistance;
        double rotation;
        double tilt;
        boolean hideUi;
        String hideUiCss;
        String imageFormat;
        int maxQueue;
        int cooldownSeconds;
        String browserExecutablePath;
        List<String> browserExtraArgs;
    }
}
