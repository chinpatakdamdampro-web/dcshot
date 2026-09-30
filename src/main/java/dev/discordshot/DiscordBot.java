package dev.discordshot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.FileUpload;
import org.bukkit.configuration.file.FileConfiguration;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DiscordBot extends ListenerAdapter {

    private final DiscordShotPlugin plugin;
    private JDA jda;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordShot-worker");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    public DiscordBot(DiscordShotPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() throws Exception {
        FileConfiguration cfg = plugin.getConfig();
        String token = cfg.getString("bot-token");

        jda = JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES)
                .setActivity(Activity.watching("BlueMap"))
                .addEventListeners(this)
                .build()
                .awaitReady();

        String guildId = cfg.getString("guild-id", "").trim();
        var cmd = Commands.slash("screenshot", "Post a BlueMap map image for a location")
                .addOption(OptionType.INTEGER, "x", "X coordinate", true)
                .addOption(OptionType.INTEGER, "z", "Z coordinate", true)
                .addOption(OptionType.INTEGER, "y", "Y coordinate (for display only)", false)
                .addOption(OptionType.STRING,  "world", "World name (default: " + cfg.getString("default-world", "world") + ")", false);

        if (!guildId.isEmpty()) {
            jda.getGuildById(guildId).updateCommands().addCommands(cmd).queue();
        } else {
            jda.updateCommands().addCommands(cmd).queue();
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("screenshot")) return;

        FileConfiguration cfg = plugin.getConfig();

        String allowedChannelId = cfg.getString("allowed-channel-id", "").trim();
        if (!allowedChannelId.isEmpty() && !event.getChannel().getId().equals(allowedChannelId)) {
            event.reply("❌ This command is not available in this channel.").setEphemeral(true).queue();
            return;
        }

        int cooldownSec = cfg.getInt("cooldown-seconds", 10);
        if (cooldownSec > 0) {
            long lastUse = cooldowns.getOrDefault(event.getUser().getId(), 0L);
            long elapsed = (System.currentTimeMillis() - lastUse) / 1000;
            if (elapsed < cooldownSec) {
                event.reply("⏳ Please wait " + (cooldownSec - elapsed) + "s before requesting another screenshot.")
                        .setEphemeral(true).queue();
                return;
            }
            cooldowns.put(event.getUser().getId(), System.currentTimeMillis());
        }

        int    x     = event.getOption("x", 0, OptionMapping::getAsInt);
        int    z     = event.getOption("z", 0, OptionMapping::getAsInt);
        int    y     = event.getOption("y", 64, OptionMapping::getAsInt);
        String world = event.getOption("world", cfg.getString("default-world", "world"), OptionMapping::getAsString);

        @SuppressWarnings("unchecked")
        Map<String, Object> mapIds = cfg.getConfigurationSection("map-ids") != null
                ? cfg.getConfigurationSection("map-ids").getValues(false)
                : Map.of();
        String mapId = mapIds.containsKey(world) ? mapIds.get(world).toString() : world;

        event.deferReply().queue();

        String bluemapUrl = cfg.getString("bluemap-url", "http://localhost:8100");
        int    gridSize   = cfg.getInt("image.grid-size", 3);
        String format     = cfg.getString("image.format", "PNG");
        int    jpegQ      = cfg.getInt("image.jpeg-quality", 90);

        TileImageBuilder builder = new TileImageBuilder(bluemapUrl, gridSize, format, jpegQ, plugin.getLogger());
        String publicMapUrl      = cfg.getString("public-map-url", "").trim();

        worker.submit(() -> {
            try {
                byte[] imageBytes = builder.buildImage(mapId, x, z);

                String ext      = format.equalsIgnoreCase("JPEG") ? "jpg" : "png";
                String filename = "screenshot_" + x + "_" + y + "_" + z + "." + ext;

                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle("📍 " + world + "  (" + x + ", " + y + ", " + z + ")")
                        .setColor(Color.decode("#4A90D9"))
                        .setImage("attachment://" + filename)
                        .setFooter("Requested by " + event.getUser().getName(), event.getUser().getEffectiveAvatarUrl());

                if (!publicMapUrl.isEmpty()) {
                    embed.setDescription("[View on BlueMap](" + publicMapUrl + ")");
                }

                event.getHook()
                        .sendMessageEmbeds(embed.build())
                        .addFiles(FileUpload.fromData(imageBytes, filename))
                        .queue();

            } catch (Exception e) {
                plugin.getLogger().warning("[DiscordShot] Screenshot failed: " + e.getMessage());
                event.getHook()
                        .sendMessage("❌ Could not fetch map image: " + e.getMessage())
                        .queue();
            }
        });
    }

    public void shutdown() {
        worker.shutdownNow();
        if (jda != null) {
            jda.shutdown();
        }
    }
}
