package dev.discordshot;

/**
 * Lets you install Playwright's headless Chromium without starting the Minecraft server.
 *
 * <pre>
 *   java -jar DiscordShot-1.0.0.jar install chromium
 * </pre>
 *
 * <p>Run this once - as the same OS user that runs your Minecraft server - after dropping
 * the jar into your plugins folder, or any time later if screenshots start failing with a
 * "browser not found" error. This has to live in its own {@code main()} rather than
 * running automatically inside the plugin: Playwright's install command ends by calling
 * {@code System.exit(...)}, which is harmless here (this is a short-lived helper process
 * that is expected to exit) but would instantly kill the entire Minecraft server if it
 * ran inside it. The jar's manifest points {@code Main-Class} at this class, while
 * plugin.yml separately points Bukkit at {@link DiscordShotPlugin} - the two never
 * interfere with each other.</p>
 */
public final class DiscordShotCLI {

    private DiscordShotCLI() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: java -jar <this jar> install chromium");
            return;
        }
        com.microsoft.playwright.CLI.main(args);
    }
}
