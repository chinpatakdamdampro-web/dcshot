package dev.discordshot;

import org.bukkit.plugin.java.JavaPlugin;

public final class DiscordShotPlugin extends JavaPlugin {

    private DiscordBot bot;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String token = getConfig().getString("bot-token", "");
        if (token.isBlank() || token.equals("PASTE_BOT_TOKEN_HERE")) {
            getLogger().severe("No bot-token set in config.yml — plugin disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            bot = new DiscordBot(this);
            bot.start();
            getLogger().info("DiscordShot enabled — slash command /screenshot registered.");
        } catch (Exception e) {
            getLogger().severe("Failed to start Discord bot: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (bot != null) {
            bot.shutdown();
        }
        getLogger().info("DiscordShot disabled.");
    }
}
