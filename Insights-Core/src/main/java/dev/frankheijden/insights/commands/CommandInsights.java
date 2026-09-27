package dev.frankheijden.insights.commands;

import dev.frankheijden.insights.Insights;
import dev.frankheijden.insights.api.InsightsApi;
import dev.frankheijden.insights.api.InsightsPlugin;
import dev.frankheijden.insights.api.LimitStatus;
import dev.frankheijden.insights.api.commands.InsightsCommand;
import dev.frankheijden.insights.api.config.Messages;
import dev.frankheijden.insights.api.utils.ColorUtils;
import dev.frankheijden.insights.api.utils.StringUtils;
import dev.frankheijden.insights.concurrent.ContainerExecutorService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import java.util.List;

@Command("insights|in")
public class CommandInsights extends InsightsCommand {

    public CommandInsights(InsightsPlugin plugin) {
        super(plugin);
    }

    @Command("")
    private void showBase(CommandSender sender) {
        // Players get the limits of the area they're standing in, the console has no location.
        if (sender instanceof Player player) {
            displayLimits(player);
        } else {
            displayInfo(sender);
        }
    }

    @Command("info")
    @Permission("insights.info")
    private void showInfo(CommandSender sender) {
        displayInfo(sender);
    }

    @Command("chunklimits|limits")
    private void showChunkLimits(Player player) {
        displayLimits(player);
    }

    @Command("reload")
    @Permission("insights.reload")
    private void reloadConfigurations(CommandSender sender) {
        plugin.reloadConfigs();
        plugin.reload();
        plugin.getMessages().getMessage(Messages.Key.CONFIGS_RELOADED).sendTo(sender);
    }

    @Command("stats")
    @Permission("insights.stats")
    private void displayStatistics(CommandSender sender) {
        ContainerExecutorService executor = ((Insights) plugin).getExecutor();
        plugin.getMessages().getMessage(Messages.Key.STATS).addTemplates(
                Messages.tagOf("chunks_scanned", StringUtils.pretty(executor.getCompletedTaskCount())),
                Messages.tagOf(
                        "blocks_scanned",
                        StringUtils.pretty(plugin.getMetricsManager().getTotalBlocksScanned().sum())
                ),
                Messages.tagOf("queue_size", StringUtils.pretty(executor.getQueueSize()))
        ).sendTo(sender);
    }

    private void displayInfo(CommandSender sender) {
        sender.sendMessage(ColorUtils.colorize(
                "&8&l&m---------------=&r&8[ &b&lInsights&8 ]&l&m=----------------",
                "&b Plugin version: &a" + plugin.getPluginMeta().getVersion(),
                "&b Plugin author(s): &7" + String.join(", ", plugin.getPluginMeta().getAuthors()),
                "&b Plugin link: &7https://www.spigotmc.org/resources/56489/",
                "&b Wiki: &7https://github.com/InsightsPlugin/Insights/wiki",
                "&8&m-------------------------------------------------"
        ));
    }

    private void displayLimits(Player player) {
        String area = InsightsApi.getAreaName(player);
        boolean hasData = InsightsApi.hasData(player);
        if (hasData && !InsightsApi.isOutdated(player)) {
            sendLimits(player, InsightsApi.getLimits(player), area);
            return;
        }

        if (InsightsApi.isScanQueued(player)) {
            plugin.getMessages().getMessage(Messages.Key.AREA_SCAN_QUEUED).addTemplates(
                    Messages.tagOf("area", area)
            ).sendTo(player);
            return;
        }

        // Scanning one chunk costs the same as placing a single block in an unscanned chunk, and
        // the result is cached, so repeated calls read straight from the cache. Outdated counts are
        // scanned again, they may miss changes made without an event (e.g. through WorldEdit).
        // Regions are never scanned here, they may span thousands of chunks and this command is open to everyone.
        Location location = player.getLocation();
        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!InsightsApi.CHUNK_AREA.equals(area) || !world.isChunkLoaded(chunkX, chunkZ)) {
            if (hasData) {
                sendLimits(player, InsightsApi.getLimits(player), area);
                return;
            }
            plugin.getMessages().getMessage(Messages.Key.CHUNKLIMITS_NO_CACHE).addTemplates(
                    Messages.tagOf("area", area)
            ).sendTo(player);
            return;
        }

        plugin.getMessages().getMessage(Messages.Key.AREA_SCAN_STARTED).addTemplates(
                Messages.tagOf("area", area)
        ).sendTo(player);
        InsightsApi.scanChunk(world, chunkX, chunkZ, storage -> {
            if (player.isOnline()) {
                sendLimits(player, InsightsApi.getLimits(player, storage), area);
            }
        });
    }

    private void sendLimits(Player player, List<LimitStatus> statuses, String area) {
        var messages = plugin.getMessages();
        if (statuses.isEmpty()) {
            messages.getMessage(Messages.Key.CHUNKLIMITS_NO_LIMITS).addTemplates(
                    Messages.tagOf("area", area)
            ).sendTo(player);
            return;
        }

        // Sent as one block instead of paginated: the amount of limits is decided by the server
        // owner and stays small, and this way the output needs no clickable page buttons.
        messages.getMessage(Messages.Key.CHUNKLIMITS_RESULT_HEADER).sendTo(player);
        for (LimitStatus status : statuses) {
            player.sendMessage(formatEntry(status));
        }
        messages.getMessage(Messages.Key.CHUNKLIMITS_RESULT_FOOTER).addTemplates(
                Messages.tagOf("area", area)
        ).sendTo(player);
    }

    private Component formatEntry(LimitStatus status) {
        return plugin.getMessages().getMessage(Messages.Key.CHUNKLIMITS_RESULT_FORMAT).addTemplates(
                Messages.tagOf("name", status.name()),
                Messages.tagOf("count", StringUtils.pretty(status.count())),
                Messages.tagOf("limit", StringUtils.pretty(status.limit())),
                Messages.tagOf("percentage", status.percentage()),
                TagResolver.resolver("usage-color", Tag.styling(usageColor(status.ratio())))
        ).toComponent().orElse(Component.empty());
    }

    private static NamedTextColor usageColor(double ratio) {
        if (ratio >= 1D) return NamedTextColor.RED;
        if (ratio >= .75D) return NamedTextColor.GOLD;
        if (ratio >= .5D) return NamedTextColor.YELLOW;
        return NamedTextColor.GREEN;
    }
}
