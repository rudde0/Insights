package dev.frankheijden.insights.api;

import dev.frankheijden.insights.api.addons.Region;
import dev.frankheijden.insights.api.concurrent.storage.Storage;
import dev.frankheijden.insights.api.config.LimitEnvironment;
import dev.frankheijden.insights.api.config.limits.Limit;
import dev.frankheijden.insights.api.config.limits.LimitInfo;
import dev.frankheijden.insights.api.config.limits.LimitType;
import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import dev.frankheijden.insights.api.utils.ChunkUtils;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Entry point for other plugins and scripts which want to read the limits applying to a player.
 *
 * <p>Everything here reads from the scan cache and never blocks, so it is safe to call from a
 * command or an event handler. When the area a player stands in has not been scanned yet,
 * {@link #hasData(Player)} returns false and {@link #requestScan(Player)} can be used to have it
 * scanned in the background.</p>
 */
public final class InsightsApi {

    /**
     * The area name used when the player does not stand inside a region of an addon.
     */
    public static final String CHUNK_AREA = "chunk";

    private static final Comparator<LimitStatus> byUsage = Comparator
            .<LimitStatus>comparingDouble(LimitStatus::ratio)
            .reversed()
            .thenComparing(LimitStatus::name);

    private InsightsApi() {}

    /**
     * Returns the name of the area limits apply to at the player's location, which is either
     * {@link #CHUNK_AREA} or the area name of the addon owning the region the player stands in.
     */
    public static String getAreaName(Player player) {
        var plugin = InsightsPlugin.getInstance();
        return plugin.getAddonManager().getRegion(player.getLocation())
                .map(region -> plugin.getAddonManager().getAddon(region.getAddon()).getAreaName())
                .orElse(CHUNK_AREA);
    }

    /**
     * Returns whether the area the player stands in has been scanned already.
     * While this is false, {@link #getLimits(Player)} returns an empty list.
     */
    public static boolean hasData(Player player) {
        return getStorage(player.getLocation()).isPresent();
    }

    /**
     * Returns whether the counts of the chunk the player stands in are older than the refresh interval
     * ({@code settings.chunk-scans.refresh-interval-seconds}), so changes made without an event (e.g. through
     * WorldEdit) may be missing from them. Regions of addons are never refreshed, so this is always false inside
     * of one, as it is when the chunk has no data at all.
     */
    public static boolean isOutdated(Player player) {
        var plugin = InsightsPlugin.getInstance();
        long refreshIntervalMillis = plugin.getSettings().CHUNK_SCANS_REFRESH_INTERVAL_MILLIS;
        if (refreshIntervalMillis <= 0) return false;

        Location location = player.getLocation();
        if (plugin.getAddonManager().getRegion(location).isPresent()) return false;
        return plugin.getWorldStorage()
                .getWorld(location.getWorld().getUID())
                .get(ChunkUtils.getKey(location))
                .map(storage -> storage.getAgeMillis() >= refreshIntervalMillis)
                .orElse(false);
    }

    /**
     * Returns whether the area the player stands in is currently waiting to be scanned.
     */
    public static boolean isScanQueued(Player player) {
        var plugin = InsightsPlugin.getInstance();
        Location location = player.getLocation();

        Optional<Region> regionOptional = plugin.getAddonManager().getRegion(location);
        if (regionOptional.isPresent()) {
            return plugin.getAddonScanTracker().isQueued(regionOptional.get().getKey());
        }
        return plugin.getWorldChunkScanTracker().isQueued(
                location.getWorld().getUID(),
                ChunkUtils.getKey(location)
        );
    }

    /**
     * Returns every limit which applies to the player at their current location, holding the amount
     * the area currently contains and the maximum it allows, sorted from fullest to emptiest.
     *
     * <p>Limits of other worlds and addons, and limits the player has the bypass permission for,
     * are left out. The list is empty when no limits apply or when the area has no scan data yet,
     * which {@link #hasData(Player)} tells apart.</p>
     */
    public static List<LimitStatus> getLimits(Player player) {
        Optional<Storage> storageOptional = getStorage(player.getLocation());
        return storageOptional.isEmpty()
                ? Collections.emptyList()
                : getLimits(player, storageOptional.get());
    }

    /**
     * Returns every limit which applies to the player, counted against the given storage.
     * Useful right after a scan completed, when the result is at hand but not cached yet.
     */
    public static List<LimitStatus> getLimits(Player player, Storage storage) {
        var plugin = InsightsPlugin.getInstance();
        LimitEnvironment env = environmentOf(player);

        List<LimitStatus> statuses = new ArrayList<>();
        for (Limit limit : plugin.getLimits().getLimits()) {
            if (!env.test(limit)) continue;

            if (limit.getType() == LimitType.PERMISSION) {
                // Permission limits hold a separate limit per material/entity.
                for (ScanObject<?> item : limit.getScanObjects()) {
                    addStatus(statuses, item.name(), limit.getLimit(item), storage.count(limit, item));
                }
            } else {
                // Tile/group limits share one limit over all of their objects, so any of them
                // describes the limit as a whole.
                Iterator<? extends ScanObject<?>> it = limit.getScanObjects().iterator();
                if (it.hasNext()) {
                    LimitInfo info = limit.getLimit(it.next());
                    addStatus(statuses, info.getName(), info, storage.count(limit));
                }
            }
        }

        statuses.sort(byUsage);
        return statuses;
    }

    /**
     * Requests a background scan of the chunk the player stands in, so its limits become available
     * shortly after. Safe to call repeatedly, calls are ignored while a scan is already pending.
     *
     * <p>Nothing is scanned when the area is already known, when the chunk is not loaded, or when
     * the player stands inside a region of an addon. Regions may span thousands of chunks, so they
     * are only scanned by the listeners, as a reaction to an actual block change.</p>
     *
     * @return whether a scan was submitted.
     */
    public static boolean requestScan(Player player) {
        var plugin = InsightsPlugin.getInstance();
        Location location = player.getLocation();
        if (plugin.getAddonManager().getRegion(location).isPresent()) return false;
        if (getStorage(location).isPresent()) return false;

        World world = location.getWorld();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (plugin.getWorldChunkScanTracker().isQueued(world.getUID(), ChunkUtils.getKey(chunkX, chunkZ))) {
            return false;
        }
        if (!world.isChunkLoaded(chunkX, chunkZ)) return false;

        scanChunk(world, chunkX, chunkZ, storage -> { });
        return true;
    }

    /**
     * Scans a single chunk off the main thread and hands the result to the given consumer.
     * The consumer is not called when the chunk unloaded before the scan could start.
     */
    public static void scanChunk(World world, int chunkX, int chunkZ, Consumer<Storage> consumer) {
        var plugin = InsightsPlugin.getInstance();
        plugin.getServer().getRegionScheduler().run(plugin, world, chunkX, chunkZ, scheduledTask -> {
            // Callers check isChunkLoaded() on their own thread, so the chunk may have unloaded
            // since. Re-checking here is reliable: this runs on the thread owning the chunk, so it
            // cannot unload between the check and the getChunkAt() below, which would otherwise
            // load and possibly generate it synchronously.
            if (!world.isChunkLoaded(chunkX, chunkZ)) return;

            plugin.getChunkContainerExecutor().submit(world.getChunkAt(chunkX, chunkZ))
                    .thenAccept(consumer)
                    .exceptionally(th -> {
                        plugin.getLogger().log(Level.SEVERE, th, th::getMessage);
                        return null;
                    });
        });
    }

    private static void addStatus(List<LimitStatus> statuses, String key, LimitInfo info, long count) {
        if (info.getLimit() > 0) {
            statuses.add(new LimitStatus(key, info.getName(), count, info.getLimit()));
        }
    }

    private static LimitEnvironment environmentOf(Player player) {
        Location location = player.getLocation();
        String worldName = location.getWorld().getName();
        return InsightsPlugin.getInstance().getAddonManager().getRegion(location)
                .map(region -> new LimitEnvironment(player, worldName, region.getAddon()))
                .orElseGet(() -> new LimitEnvironment(player, worldName));
    }

    private static Optional<Storage> getStorage(Location location) {
        var plugin = InsightsPlugin.getInstance();
        Optional<Region> regionOptional = plugin.getAddonManager().getRegion(location);
        if (regionOptional.isPresent()) {
            return plugin.getAddonStorage().get(regionOptional.get().getKey());
        }
        return plugin.getWorldStorage()
                .getWorld(location.getWorld().getUID())
                .get(ChunkUtils.getKey(location));
    }
}
