package dev.discordshot;

import dev.discordshot.config.PluginSettings;
import dev.discordshot.discord.ScreenshotCommand;
import dev.discordshot.render.BlueMapRenderer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point for the plugin. Wires together the config, the Discord bot (JDA) and the
 * background render pool, then hands everything off to {@link ScreenshotCommand}.
 *
 * <p>Nothing in this class touches the network or the browser directly - it only builds
 * the pieces and tears them back down on disable. See {@link ScreenshotCommand} for the
 * actual command handling and {@link BlueMapRenderer} for the headless-browser work.</p>
 */
public final class DiscordShotPlugin extends JavaPlugin {

    private JDA jda;
    private ThreadPoolExecutor renderExecutor;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        final PluginSettings settings;
        try {
            settings = PluginSettings.load(getConfig(), getLogger());
        } catch (PluginSettings.InvalidConfigException e) {
            getLogger().severe("config.yml is not set up correctly: " + e.getMessage());
            getLogger().severe("Fix config.yml and restart the server. Disabling DiscordShot.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // One dedicated, daemon thread drives the headless browser. Screenshots are slow
        // (a second or more) and Playwright's objects are only safe to use from the thread
        // that created them, so we never share a browser across threads - every queued job
        // gets its own short-lived Playwright + browser + page, all created and closed on
        // this single thread. Extra requests beyond `limits.max-queue` are rejected with a
        // friendly Discord reply instead of piling up unbounded background work.
        AtomicInteger threadCount = new AtomicInteger(1);
        ThreadFactory renderThreadFactory = runnable -> {
            Thread thread = new Thread(runnable, "DiscordShot-Render-" + threadCount.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        renderExecutor = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(1, settings.maxQueue())),
                renderThreadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );

        BlueMapRenderer renderer = new BlueMapRenderer(getLogger());

        try {
            jda = JDABuilder.createLight(settings.botToken(), Collections.emptyList())
                    // "Light" build: no message/member/presence caching. Slash commands
                    // don't need it - interaction events aren't gated by gateway intents.
                    .setActivity(Activity.watching("for /screenshot"))
                    .addEventListeners(new ScreenshotCommand(this, settings, renderExecutor, renderer))
                    .build();
        } catch (InvalidTokenException e) {
            getLogger().severe("Discord rejected the bot token in config.yml: " + e.getMessage());
            getLogger().severe("Double-check bot-token, then restart the server. Disabling DiscordShot.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (RuntimeException e) {
            getLogger().severe("Could not start the Discord bot: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("DiscordShot enabled. Waiting for Discord to connect...");
        getLogger().info("First time here? If screenshots ever fail with a browser/executable");
        getLogger().info("error, run this once from a terminal (same user that runs the server):");
        getLogger().info("  java -jar " + jarFileName() + " install chromium");
    }

    @Override
    public void onDisable() {
        // Bounded waits during shutdown are normal for Bukkit plugins and prevent this
        // plugin's threads (and its classloader) from leaking past a /reload or disable.
        if (renderExecutor != null) {
            renderExecutor.shutdownNow();
            try {
                renderExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (jda != null) {
            // Signals JDA to close its gateway connection and stop processing. JDA's
            // internal threads are daemon threads, so they can't keep the JVM (or a
            // /reload) hanging even if a couple linger briefly while shutting down.
            jda.shutdown();
        }
    }

    private String jarFileName() {
        try {
            return new java.io.File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getName();
        } catch (Exception e) {
            return "DiscordShot-1.0.0.jar";
        }
    }
}
