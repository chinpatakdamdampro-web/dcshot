package dev.discordshot.render;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Screenshots a BlueMap view by launching a system-installed Chrome/Chromium in headless
 * mode and driving it over the Chrome DevTools Protocol (plain WebSocket + JSON, using only
 * classes that ship with Java 21 - no browser-automation library is bundled).
 *
 * <p>Every call to {@link #capture} starts its own throwaway browser with a temporary
 * profile, takes one picture, and then force-kills the browser process (and any helper
 * processes it spawned) and deletes the profile, even on timeouts and errors. Nothing is
 * left running between screenshots.</p>
 */
public final class BlueMapRenderer {

    private static final Pattern DEVTOOLS_LINE = Pattern.compile("DevTools listening on (ws://\\S+)");
    private static final long BROWSER_START_TIMEOUT_MS = 20_000;
    private static final Gson GSON = new Gson();

    private final Logger log;

    public BlueMapRenderer(Logger log) {
        this.log = log;
    }

    public byte[] capture(RenderRequest request) throws RenderException {
        String url = BlueMapUrlBuilder.build(request);
        log.fine(() -> "DiscordShot: rendering " + url);

        Path browser = resolveBrowser(request.browserExecutablePath());
        Path profileDir = null;
        Process process = null;
        Cdp cdp = null;

        try {
            profileDir = Files.createTempDirectory("discordshot-chrome-");

            List<String> command = new ArrayList<>();
            command.add(browser.toString());
            command.addAll(chromiumArgs(request, profileDir));
            command.add("about:blank");

            process = new ProcessBuilder(command).redirectErrorStream(true).start();

            OutputWatcher watcher = new OutputWatcher(process);
            String browserWsUrl = watcher.awaitDevToolsUrl(BROWSER_START_TIMEOUT_MS);

            String pageWsUrl = findPageWebSocket(browserWsUrl);
            cdp = Cdp.connect(pageWsUrl);

            long timeout = request.pageTimeoutMs();

            cdp.call("Page.enable", null, timeout);

            JsonObject metrics = new JsonObject();
            metrics.addProperty("width", request.width());
            metrics.addProperty("height", request.height());
            metrics.addProperty("deviceScaleFactor", 1);
            metrics.addProperty("mobile", false);
            cdp.call("Emulation.setDeviceMetricsOverride", metrics, timeout);

            JsonObject nav = new JsonObject();
            nav.addProperty("url", url);
            JsonObject navResult = cdp.call("Page.navigate", nav, timeout);
            if (navResult.has("errorText")) {
                throw new RenderException("Could not open BlueMap (" + navResult.get("errorText").getAsString()
                        + "). Check that bluemap-url in config.yml is reachable from this machine "
                        + "and that BlueMap's web server is actually running.");
            }

            try {
                cdp.awaitLoad(timeout);
            } catch (TimeoutException e) {
                throw new RenderException("Timed out waiting for BlueMap. Check that bluemap-url in config.yml "
                        + "is reachable from this machine and that BlueMap's web server is actually running.");
            }

            if (request.hideUi()) {
                JsonObject eval = new JsonObject();
                eval.addProperty("expression", "(()=>{const s=document.createElement('style');"
                        + "s.textContent=" + new JsonPrimitive(hideUiCss(request)) + ";"
                        + "document.head.appendChild(s);})()");
                cdp.call("Runtime.evaluate", eval, timeout);
            }

            // BlueMap keeps streaming and drawing map tiles for a moment after the page
            // itself finishes loading. This fixed pause is what lets the 3D scene appear
            // instead of an empty canvas - software rendering needs a few seconds.
            Thread.sleep(Math.max(0, request.renderWaitMs()));

            JsonObject shot = new JsonObject();
            boolean jpeg = request.imageFormat().equals("jpeg");
            shot.addProperty("format", jpeg ? "jpeg" : "png");
            if (jpeg) {
                shot.addProperty("quality", 90);
            }
            JsonObject shotResult = cdp.call("Page.captureScreenshot", shot, timeout);
            if (!shotResult.has("data")) {
                throw new RenderException("The browser returned no image data.");
            }
            return Base64.getDecoder().decode(shotResult.get("data").getAsString());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RenderException("Screenshot was cancelled (server or plugin shutting down).", e);
        } catch (IOException e) {
            throw new RenderException("Could not start the browser: " + e.getMessage(), e);
        } finally {
            if (cdp != null) {
                cdp.close();
            }
            killTree(process);
            deleteQuietly(profileDir);
        }
    }

    // ------------------------------------------------------------------ browser launch

    private static List<String> chromiumArgs(RenderRequest request, Path profileDir) {
        List<String> args = new ArrayList<>(List.of(
                "--headless=new",
                "--remote-debugging-port=0",
                "--remote-debugging-address=127.0.0.1",
                "--user-data-dir=" + profileDir,
                "--window-size=" + request.width() + "," + request.height(),
                "--hide-scrollbars",
                "--mute-audio",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                // Headless Chromium blocks software-rendered WebGL by default; BlueMap's
                // 3D view needs it (and a server usually has no usable GPU).
                "--use-gl=swiftshader",
                "--enable-webgl",
                "--enable-unsafe-swiftshader",
                "--ignore-gpu-blocklist",
                "--disable-gpu-sandbox",
                // Needed when running as root or inside restricted hosts/containers.
                "--no-sandbox",
                "--disable-dev-shm-usage"
        ));
        args.addAll(request.browserExtraArgs());
        return args;
    }

    private static Path resolveBrowser(String configured) throws RenderException {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (!Files.isExecutable(p)) {
                throw new RenderException("browser.executable-path in config.yml points to '" + p
                        + "', which doesn't exist or isn't executable.");
            }
            return p;
        }

        // Google Chrome first: on Ubuntu, 'chromium' / 'chromium-browser' are snap packages,
        // which frequently refuse to start when launched by another program.
        String[] names = {"google-chrome-stable", "google-chrome", "chrome", "chromium", "chromium-browser"};
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String name : names) {
                for (String dir : pathEnv.split(File.pathSeparator)) {
                    if (dir.isBlank()) {
                        continue;
                    }
                    Path candidate = Path.of(dir, name);
                    if (Files.isExecutable(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        String[] absolute = {
                "/opt/google/chrome/chrome",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        };
        for (String a : absolute) {
            Path p = Path.of(a);
            if (Files.isExecutable(p)) {
                return p;
            }
        }
        throw new RenderException("No Chrome/Chromium found on this machine. Install Google Chrome "
                + "(Ubuntu: download the .deb from google.com/chrome, then 'sudo apt install ./google-chrome-stable_current_amd64.deb'), "
                + "or set browser.executable-path in config.yml.");
    }

    /** Reads the browser's console output: finds the DevTools address, then keeps draining the pipe. */
    private static final class OutputWatcher {
        private final CompletableFuture<String> wsUrl = new CompletableFuture<>();
        private final ConcurrentLinkedDeque<String> recent = new ConcurrentLinkedDeque<>();

        OutputWatcher(Process process) {
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        recent.addLast(line);
                        while (recent.size() > 8) {
                            recent.pollFirst();
                        }
                        Matcher m = DEVTOOLS_LINE.matcher(line);
                        if (m.find()) {
                            wsUrl.complete(m.group(1));
                        }
                    }
                } catch (IOException ignored) {
                    // process was killed; nothing to do
                } finally {
                    wsUrl.completeExceptionally(new IOException("The browser exited before it was ready."));
                }
            }, "DiscordShot-browser-output");
            t.setDaemon(true);
            t.start();
        }

        String awaitDevToolsUrl(long timeoutMs) throws RenderException, InterruptedException {
            try {
                return wsUrl.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new RenderException("The browser didn't become ready in time." + tail());
            } catch (ExecutionException e) {
                throw new RenderException("The browser exited before it was ready." + tail());
            }
        }

        private String tail() {
            return recent.isEmpty() ? "" : " Browser output: " + String.join(" | ", recent);
        }
    }

    /** Finds the DevTools WebSocket of the (about:blank) page tab via the browser's local HTTP endpoint. */
    private static String findPageWebSocket(String browserWsUrl) throws RenderException, InterruptedException {
        URI ws = URI.create(browserWsUrl);
        URI list = URI.create("http://127.0.0.1:" + ws.getPort() + "/json/list");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(list).timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonArray targets = JsonParser.parseString(resp.body()).getAsJsonArray();
                for (JsonElement el : targets) {
                    JsonObject t = el.getAsJsonObject();
                    if (t.has("type") && "page".equals(t.get("type").getAsString())
                            && t.has("webSocketDebuggerUrl")) {
                        return t.get("webSocketDebuggerUrl").getAsString();
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // not ready yet - retry
            }
            Thread.sleep(200);
        }
        throw new RenderException("The browser started but didn't open a page tab.");
    }

    // ------------------------------------------------------------------ cleanup

    private static void killTree(Process process) {
        if (process == null) {
            return;
        }
        // Chrome spawns many helper processes; kill them all so nothing lingers and eats RAM.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ helpers

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

    /** Minimal Chrome DevTools Protocol client over Java's built-in WebSocket. */
    private static final class Cdp implements WebSocket.Listener {
        private final AtomicInteger ids = new AtomicInteger();
        private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
        private final CompletableFuture<Void> loaded = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();
        private volatile WebSocket socket;

        static Cdp connect(String wsUrl) throws RenderException, InterruptedException {
            Cdp cdp = new Cdp();
            try {
                cdp.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(wsUrl), cdp)
                        .get(15, TimeUnit.SECONDS);
                return cdp;
            } catch (ExecutionException | TimeoutException e) {
                throw new RenderException("Could not connect to the browser's debugging port.", e);
            }
        }

        JsonObject call(String method, JsonObject params, long timeoutMs) throws RenderException, InterruptedException {
            int id = ids.incrementAndGet();
            JsonObject msg = new JsonObject();
            msg.addProperty("id", id);
            msg.addProperty("method", method);
            if (params != null) {
                msg.add("params", params);
            }
            CompletableFuture<JsonObject> future = new CompletableFuture<>();
            pending.put(id, future);
            try {
                socket.sendText(GSON.toJson(msg), true);
                JsonObject resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (resp.has("error")) {
                    throw new RenderException("Browser command " + method + " failed: " + resp.get("error"));
                }
                return resp.has("result") ? resp.getAsJsonObject("result") : new JsonObject();
            } catch (TimeoutException e) {
                throw new RenderException("Timed out during " + method
                        + ". Try raising screenshot.page-timeout-ms in config.yml.");
            } catch (ExecutionException e) {
                throw new RenderException("Lost connection to the browser during " + method + ".", e);
            } finally {
                pending.remove(id);
            }
        }

        void awaitLoad(long timeoutMs) throws TimeoutException, InterruptedException, RenderException {
            try {
                loaded.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw new RenderException("Lost connection to the browser while loading the page.", e);
            }
        }

        void close() {
            WebSocket s = socket;
            if (s != null) {
                s.abort();
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                handle(text);
            }
            ws.request(1);
            return null;
        }

        private void handle(String text) {
            try {
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                if (obj.has("id")) {
                    CompletableFuture<JsonObject> f = pending.get(obj.get("id").getAsInt());
                    if (f != null) {
                        f.complete(obj);
                    }
                } else if (obj.has("method") && "Page.loadEventFired".equals(obj.get("method").getAsString())) {
                    loaded.complete(null);
                }
            } catch (RuntimeException ignored) {
                // ignore malformed/unrelated messages
            }
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            failAll(error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            failAll(new IOException("Browser connection closed"));
            return null;
        }

        private void failAll(Throwable t) {
            pending.values().forEach(f -> f.completeExceptionally(t));
            loaded.completeExceptionally(t);
        }
    }
}
