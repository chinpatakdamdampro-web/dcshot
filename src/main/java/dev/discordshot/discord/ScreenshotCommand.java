package dev.discordshot.discord;

import dev.discordshot.config.PluginSettings;
import dev.discordshot.render.BlueMapRenderer;
import dev.discordshot.render.RenderException;
import dev.discordshot.render.RenderRequest;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;

/**
 * Registers and handles the {@code /screenshot} Discord slash command.
 *
 * <p>Every Bukkit/world lookup here runs on the main server thread (the Bukkit API is not
 * thread-safe); the actual browser automation runs on the dedicated render thread handed
 * in from {@code DiscordShotPlugin}. JDA's own callbacks (this listener) run on JDA's
 * internal gateway threads, never the server thread, so none of the three ever blocks
 * either of the others.</p>
 */
public final class ScreenshotCommand extends ListenerAdapter {

    private static final String COMMAND_NAME = "screenshot";

    private final Plugin plugin;
    private final PluginSettings settings;
    private final ThreadPoolExecutor renderExecutor;
    private final BlueMapRenderer renderer;
    private final CooldownTracker cooldowns;

    public ScreenshotCommand(Plugin plugin, PluginSettings settings,
                              ThreadPoolExecutor renderExecutor, BlueMapRenderer renderer) {
        this.plugin = plugin;
        this.settings = settings;
        this.renderExecutor = renderExecutor;
        this.renderer = renderer;
        this.cooldowns = new CooldownTracker(settings.cooldownSeconds());
    }

    @Override
    public void onReady(ReadyEvent event) {
        JDA jda = event.getJDA();
        SlashCommandData command = Commands.slash(COMMAND_NAME,
                        "Post a BlueMap screenshot of a location in this channel.")
                .addOption(OptionType.NUMBER, "x", "X coordinate", true)
                .addOption(OptionType.NUMBER, "y", "Y coordinate", true)
                .addOption(OptionType.NUMBER, "z", "Z coordinate", true)
                .addOption(OptionType.STRING, "world", "World name (default: "
                        + (settings.defaultWorld().isEmpty() ? "server's main world" : settings.defaultWorld())
                        + ")", false);

        String guildId = settings.guildId();
        if (!guildId.isEmpty()) {
            Guild guild = jda.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(command).queue(
                        ok -> plugin.getLogger().info("Registered /screenshot in guild " + guild.getName()),
                        err -> plugin.getLogger().log(Level.WARNING, "Could not register /screenshot in the configured guild", err)
                );
                return;
            }
            plugin.getLogger().warning("guild-id in config.yml (" + guildId + ") was not found; "
                    + "registering /screenshot globally instead.");
        }

        jda.updateCommands().addCommands(command).queue(
                ok -> plugin.getLogger().info("Registered /screenshot globally (can take Discord a few minutes to show up)."),
                err -> plugin.getLogger().log(Level.WARNING, "Could not register /screenshot", err)
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals(COMMAND_NAME)) {
            return;
        }

        String allowedChannel = settings.allowedChannelId();
        if (!allowedChannel.isEmpty() && !event.getChannel().getId().equals(allowedChannel)) {
            event.reply("This command can only be used in <#" + allowedChannel + ">.")
                    .setEphemeral(true).queue();
            return;
        }

        long remaining = cooldowns.secondsRemaining(event.getUser().getIdLong());
        if (remaining > 0) {
            event.reply("Please wait " + remaining + "s before requesting another screenshot.")
                    .setEphemeral(true).queue();
            return;
        }

        OptionMapping xOpt = event.getOption("x");
        OptionMapping yOpt = event.getOption("y");
        OptionMapping zOpt = event.getOption("z");
        if (xOpt == null || yOpt == null || zOpt == null) {
            event.reply("x, y and z are required.").setEphemeral(true).queue();
            return;
        }
        double x = xOpt.getAsDouble();
        double y = yOpt.getAsDouble();
        double z = zOpt.getAsDouble();
        String requestedWorld = event.getOption("world", OptionMapping::getAsString);

        cooldowns.recordUse(event.getUser().getIdLong());

        // Acknowledge immediately (Discord gives us 3 seconds to respond at all), then -
        // once that acknowledgement is confirmed - swap in our own status text. This is
        // step (a) from the spec. Only after that do we hop onto the main server thread
        // to resolve the world, which is where the actual render gets queued.
        event.deferReply().queue(hook -> {
            hook.editOriginal("\uD83D\uDDBC\uFE0F Generating screenshot...").queue();
            Bukkit.getScheduler().runTask(plugin, () -> resolveWorldThenRender(event, x, y, z, requestedWorld));
        });
    }

    private void resolveWorldThenRender(SlashCommandInteractionEvent event, double x, double y, double z,
                                         String requestedWorld) {
        String worldName = requestedWorld;
        if (worldName == null || worldName.isBlank()) {
            worldName = settings.defaultWorld();
        }
        if (worldName == null || worldName.isBlank()) {
            List<World> worlds = Bukkit.getWorlds();
            if (worlds.isEmpty()) {
                event.getHook().editOriginal("\u274C No worlds are loaded on this server.").queue();
                return;
            }
            worldName = worlds.get(0).getName();
        }

        if (Bukkit.getWorld(worldName) == null) {
            event.getHook().editOriginal("\u274C World '" + worldName + "' does not exist on this server.").queue();
            return;
        }

        String mapId = settings.mapIdFor(worldName);
        RenderRequest request = new RenderRequest(
                settings.blueMapUrl(),
                settings.viewTemplate(),
                mapId,
                x, y, z,
                settings.defaultDistance(),
                settings.rotation(),
                settings.tilt(),
                settings.screenshotWidth(),
                settings.screenshotHeight(),
                settings.renderWaitMs(),
                settings.pageTimeoutMs(),
                settings.hideUi(),
                settings.hideUiCss(),
                settings.imageFormat(),
                settings.browserExecutablePath(),
                settings.browserExtraArgs()
        );

        String finalWorldName = worldName;
        try {
            renderExecutor.execute(() -> renderAndReply(event, request, finalWorldName, x, y, z));
        } catch (RejectedExecutionException e) {
            event.getHook().editOriginal("\u274C Too many screenshots are already queued. Try again in a moment.").queue();
        }
    }

    private void renderAndReply(SlashCommandInteractionEvent event, RenderRequest request,
                                 String worldName, double x, double y, double z) {
        try {
            byte[] image = renderer.capture(request);
            String fileName = "screenshot." + (request.imageFormat().equals("jpeg") ? "jpg" : "png");
            FileUpload file = FileUpload.fromData(image, fileName);

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("\uD83D\uDCF8 Screenshot")
                    .setDescription(String.format("**World:** %s%n**Coordinates:** %.0f, %.0f, %.0f", worldName, x, y, z))
                    .setImage("attachment://" + fileName)
                    .setColor(new Color(0x43B581))
                    .setTimestamp(Instant.now());

            if (!settings.publicMapUrl().isEmpty()) {
                embed.appendDescription(String.format("%n[Open in BlueMap](%s)", settings.publicMapUrl()));
            }

            MessageEmbed builtEmbed = embed.build();
            MessageEditData data = new MessageEditBuilder()
                    .setContent("")
                    .setEmbeds(builtEmbed)
                    .setFiles(file)
                    .build();
            event.getHook().editOriginal(data).queue();
        } catch (RenderException e) {
            plugin.getLogger().log(Level.WARNING, "Screenshot render failed", e);
            event.getHook().editOriginal("\u274C " + e.getMessage()).queue();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Unexpected error while rendering a screenshot", e);
            event.getHook().editOriginal("\u274C Something went wrong generating that screenshot. Check the server console.").queue();
        }
    }
}
