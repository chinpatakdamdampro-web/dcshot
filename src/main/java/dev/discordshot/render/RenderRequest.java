package dev.discordshot.render;

import java.util.List;

/**
 * Everything one screenshot needs, gathered into a single immutable value so the browser
 * automation code never has to reach back into Bukkit config or JDA objects directly.
 */
public record RenderRequest(
        String baseUrl,
        String viewTemplate,
        String mapId,
        double x,
        double y,
        double z,
        double distance,
        double rotation,
        double tilt,
        int width,
        int height,
        long renderWaitMs,
        long pageTimeoutMs,
        boolean hideUi,
        String hideUiCss,
        String imageFormat,
        String browserExecutablePath,
        List<String> browserExtraArgs
) {
}
