package dev.discordshot.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Drives a throwaway headless Chromium to screenshot a BlueMap view.
 *
 * <p><b>Threading:</b> Playwright's objects are confined to the thread that created them -
 * sharing one {@link Playwright}/{@link Browser} across threads is not supported by the
 * library. This class never keeps one around: every call to {@link #capture} creates its
 * own Playwright instance, browser and page, uses them, and closes them again, all on
 * whichever single thread calls it. As long as every call happens on the plugin's one
 * dedicated render thread (see {@code DiscordShotPlugin}), this is safe even when several
 * screenshots are requested back-to-back.</p>
 */
public final class BlueMapRenderer {

    private final Logger log;

    public BlueMapRenderer(Logger log) {
        this.log = log;
    }

    public byte[] capture(RenderRequest request) throws RenderException {
        String url = BlueMapUrlBuilder.build(request);
        log.fine(() -> "DiscordShot: rendering " + url);

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(chromiumArgs(request.browserExtraArgs()));

            if (!request.browserExecutablePath().isBlank()) {
                launchOptions.setExecutablePath(Path.of(request.browserExecutablePath()));
            }

            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                Browser.NewPageOptions pageOptions = new Browser.NewPageOptions()
                        .setViewportSize(request.width(), request.height());

                try (Page page = browser.newPage(pageOptions)) {
                    page.navigate(url, new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.NETWORKIDLE)
                            .setTimeout(request.pageTimeoutMs()));

                    if (request.hideUi()) {
                        page.addStyleTag(new Page.AddStyleTagOptions().setContent(hideUiCss(request)));
                    }

                    // BlueMap keeps streaming and drawing map tiles for a moment after the
                    // page itself finishes loading. This fixed pause is what actually lets
                    // the 3D scene appear instead of an empty canvas - the server has no
                    // real GPU, so software rendering needs a bit longer than 1-2 seconds.
                    page.waitForTimeout(request.renderWaitMs());

                    return page.screenshot(new Page.ScreenshotOptions()
                            .setType(request.imageFormat().equals("jpeg") ? ScreenshotType.JPEG : ScreenshotType.PNG));
                }
            }
        } catch (PlaywrightException e) {
            throw new RenderException(friendlyMessage(e), e);
        }
    }

    private static List<String> chromiumArgs(List<String> extra) {
        List<String> args = new ArrayList<>(List.of(
                "--headless=new",
                // Headless Chromium (~M121+) blocks software-rendered WebGL by default;
                // BlueMap's 3D view needs it, since the server almost certainly has no GPU.
                "--use-gl=swiftshader",
                "--enable-webgl",
                "--enable-unsafe-swiftshader",
                "--ignore-gpu-blocklist",
                "--disable-gpu-sandbox",
                // Common flags for running Chromium inside containers / restricted hosts,
                // which describes most Minecraft server hosting.
                "--no-sandbox",
                "--disable-dev-shm-usage"
        ));
        args.addAll(extra);
        return args;
    }

    private static String hideUiCss(RenderRequest request) {
        if (!request.hideUiCss().isBlank()) {
            return request.hideUiCss();
        }
        // Best-effort default: BlueMap's UI is built from components whose class names
        // tend to contain words like these. This hides overlays without touching the map
        // canvas itself. If your BlueMap version still leaves something visible, inspect
        // the page in a normal browser and set screenshot.hide-ui-css in config.yml.
        return "[class*='sidebar'], [class*='toolbar'], [class*='menu'], [class*='popup'], "
                + "[class*='compass'], [class*='topbar'], [class*='controls'], [class*='options'], "
                + "[class*='info-box'], [class*='marker-label'] { display: none !important; }";
    }

    private static String friendlyMessage(PlaywrightException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("Executable doesn't exist")) {
            return "Chromium isn't downloaded yet. Run 'java -jar <this plugin's jar> install chromium' "
                    + "once from a terminal (as the same user that runs the server), then try again.";
        }
        if (msg.contains("Timeout") && msg.contains("exceeded")) {
            return "Timed out waiting for BlueMap. Check that bluemap-url in config.yml is reachable "
                    + "from this machine and that BlueMap's web server is actually running.";
        }
        return "Rendering failed: " + msg;
    }
}
