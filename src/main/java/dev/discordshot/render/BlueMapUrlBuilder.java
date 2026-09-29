package dev.discordshot.render;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Fills in BlueMap's URL-hash template with a specific location.
 *
 * <p>BlueMap's web app reads its camera position from the URL fragment (the part after
 * "#"), so navigating straight to a fully-formed link is enough to make it open already
 * looking at the right spot - no clicking around needed. The exact field order has shifted
 * between BlueMap releases in the past, so it lives in config.yml's {@code view-template}
 * rather than being hard-coded here: if a future BlueMap version changes its format,
 * server admins can fix it without waiting on a plugin update. To check your own server:
 * open BlueMap in a normal browser, move the camera around, and compare the address bar to
 * the template below.</p>
 */
public final class BlueMapUrlBuilder {

    private BlueMapUrlBuilder() {
    }

    public static String build(RenderRequest r) {
        String hash = r.viewTemplate()
                .replace("{map}", encode(r.mapId()))
                .replace("{x}", format(r.x()))
                .replace("{y}", format(r.y()))
                .replace("{z}", format(r.z()))
                .replace("{distance}", format(r.distance()))
                .replace("{rotation}", format(r.rotation()))
                .replace("{tilt}", format(r.tilt()));

        String base = r.baseUrl();
        // The default template already starts with "#"; the extra "/" branch just keeps
        // things working if someone rewrites the template to start with a path instead.
        return hash.startsWith("#") ? base + hash : base + "/" + hash;
    }

    private static String format(double value) {
        // BlueMap's parser expects plain decimal numbers - no scientific notation, no
        // grouping separators - and whole numbers are trimmed so URLs stay short and tidy.
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
