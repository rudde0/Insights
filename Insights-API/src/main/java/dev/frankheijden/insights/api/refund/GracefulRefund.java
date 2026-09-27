package dev.frankheijden.insights.api.refund;

import dev.frankheijden.insights.api.InsightsApi;
import dev.frankheijden.insights.api.InsightsPlugin;
import dev.frankheijden.insights.api.concurrent.storage.Storage;
import dev.frankheijden.insights.api.config.Limits;
import dev.frankheijden.insights.api.config.Settings;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.IllegalPluginAccessException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Removes the blocks a chunk holds beyond its limit, and hands them back in a chest ("graceful refund").
 *
 * <p>Finding such chunks costs next to nothing, as it only compares the counts Insights keeps anyway, and only
 * when a chunk was scanned or one of the refunded materials was added to it. Those counts are never trusted
 * to remove anything though, they may be off (e.g. after WorldEdit changed the chunk). A flagged chunk is
 * snapshotted, the snapshot is searched for the blocks off the main thread, and every block found is looked up in
 * the world again, on the thread owning the chunk, right before removing anything. Only blocks which are
 * verifiably there count, blocks placed since are not included, so what is counted can only be lower than what
 * really is there, and the chunk never ends up below its limit.</p>
 */
public class GracefulRefund {

    private static final int PRUNE_THRESHOLD = 1024;
    private static final long PENDING_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    private final InsightsPlugin plugin;
    private final NamespacedKey chestKey;
    private final Map<ChunkId, Long> pending = new ConcurrentHashMap<>();
    private final Map<ChunkId, Long> lastRuns = new ConcurrentHashMap<>();
    private volatile RefundConfig config = null;

    public GracefulRefund(InsightsPlugin plugin) {
        this.plugin = plugin;
        this.chestKey = new NamespacedKey(plugin, "graceful_refund");
    }

    /**
     * Checks the counts of a chunk which have just been scanned and cached.
     */
    public void onChunkScanned(World world, long chunkKey, Storage storage) {
        try {
            RefundConfig cfg = getConfig();
            if (cfg == null || !cfg.appliesTo(world)) return;

            for (RefundGroup group : cfg.groups()) {
                if (storage.count(cfg.limit(), group.scanObject()) > group.limit()) {
                    flag(cfg, world, chunkKey);
                    return;
                }
            }
        } catch (RuntimeException ex) {
            // Never let this get in the way of scanning.
            plugin.getLogger().log(Level.SEVERE, "Graceful refund failed to check a scanned chunk", ex);
        }
    }

    /**
     * Checks the counts of a chunk after a block of given material has been added to them.
     * This runs for every block change Insights tracks, anything but a refunded material returns right away.
     */
    public void onBlockAdded(World world, long chunkKey, Storage storage, Material material) {
        try {
            RefundConfig cfg = getConfig();
            if (cfg == null) return;

            RefundGroup group = cfg.group(material);
            if (group != null && cfg.appliesTo(world) && storage.count(cfg.limit(), group.scanObject()) > group.limit()) {
                flag(cfg, world, chunkKey);
            }
        } catch (RuntimeException ex) {
            // Never let this get in the way of the event which changed the block.
            plugin.getLogger().log(Level.SEVERE, "Graceful refund failed to check a block change", ex);
        }
    }

    /**
     * Forgets about a chunk which unloaded.
     */
    public void onChunkUnload(World world, long chunkKey) {
        if (!lastRuns.isEmpty()) {
            lastRuns.remove(new ChunkId(world.getUID(), chunkKey));
        }
    }

    InsightsPlugin getPlugin() {
        return plugin;
    }

    NamespacedKey getChestKey() {
        return chestKey;
    }

    /**
     * Returns the current refund settings, or null if nothing is refunded.
     */
    RefundConfig getConfig() {
        Settings settings = plugin.getSettings();
        Limits limits = plugin.getLimits();
        RefundConfig cfg = config;
        if (cfg == null || !cfg.isFor(settings, limits)) {
            synchronized (this) {
                cfg = config;
                if (cfg == null || !cfg.isFor(settings, limits)) {
                    try {
                        cfg = RefundConfig.create(settings, limits, plugin.getLogger());
                    } catch (RuntimeException ex) {
                        plugin.getLogger().log(Level.SEVERE, "Graceful refund is disabled, its settings could not be loaded", ex);
                        cfg = RefundConfig.disabled(settings, limits);
                    }
                    config = cfg;
                }
            }
        }
        return cfg.isEnabled() ? cfg : null;
    }

    private void flag(RefundConfig cfg, World world, long chunkKey) {
        ChunkId id = new ChunkId(world.getUID(), chunkKey);
        long now = System.currentTimeMillis();
        Long pendingSince = pending.putIfAbsent(id, now);
        if (pendingSince != null) {
            // Already on its way. Unless it got lost, a scheduler may drop the task of a chunk which unloaded.
            long timeoutMillis = PENDING_TIMEOUT_MILLIS + cfg.cooldownMillis() + cfg.delayTicks() * 50L;
            if (now - pendingSince < timeoutMillis || !pending.replace(id, pendingSince, now)) return;
        }

        // Waiting a bit lets a burst of changes settle, and a chunk is handled at most once per cooldown.
        long delayTicks = cfg.delayTicks();
        Long lastRun = lastRuns.get(id);
        if (lastRun != null) {
            long cooldownLeftMillis = lastRun + cfg.cooldownMillis() - now;
            delayTicks = Math.max(delayTicks, (cooldownLeftMillis + 49) / 50);
        }
        if (lastRuns.size() > PRUNE_THRESHOLD) {
            lastRuns.values().removeIf(time -> now - time >= cfg.cooldownMillis());
        }

        try {
            plugin.getServer().getRegionScheduler().runDelayed(
                    plugin,
                    world,
                    id.chunkX(),
                    id.chunkZ(),
                    task -> snapshot(id, now),
                    delayTicks
            );
        } catch (IllegalPluginAccessException ex) {
            // Insights is being disabled.
            pending.remove(id, now);
        }
    }

    /**
     * Takes a snapshot of the chunk, runs on the thread owning the chunk.
     */
    private void snapshot(ChunkId id, long since) {
        boolean searching = false;
        boolean examined = false;
        try {
            RefundConfig cfg = getConfig();
            World world = plugin.getServer().getWorld(id.worldUid());
            if (cfg == null || world == null || !cfg.appliesTo(world)) return;

            // Chunks are never loaded for this, an unloaded chunk will be looked at once it is scanned again.
            if (!world.isChunkLoaded(id.chunkX(), id.chunkZ())) return;

            examined = true;
            ChunkSnapshot snapshot = world.getChunkAt(id.chunkX(), id.chunkZ()).getChunkSnapshot(false, false, false);
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> search(id, since, cfg, snapshot, minY, maxY));
            searching = true;
        } finally {
            if (!searching) done(id, since, examined);
        }
    }

    /**
     * Searches the snapshot for the refunded materials, runs off the main thread.
     */
    private void search(ChunkId id, long since, RefundConfig cfg, ChunkSnapshot snapshot, int minY, int maxY) {
        boolean refunding = false;
        try {
            int[][] found = find(cfg, snapshot, minY, maxY);
            boolean outdated = isCacheOutdated(id, cfg, found);

            boolean exceeded = false;
            for (RefundGroup group : cfg.groups()) {
                exceeded |= found[group.index()].length > group.limit();
            }

            World world = plugin.getServer().getWorld(id.worldUid());
            if (world == null) return;
            if (!exceeded) {
                if (outdated) refresh(world, id);
                return;
            }

            plugin.getServer().getRegionScheduler().run(
                    plugin,
                    world,
                    id.chunkX(),
                    id.chunkZ(),
                    task -> refund(id, since, cfg, found, minY, maxY, outdated)
            );
            refunding = true;
        } catch (IllegalPluginAccessException ex) {
            // Insights is being disabled.
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Graceful refund failed to search chunk " + id, ex);
        } finally {
            if (!refunding) done(id, since, true);
        }
    }

    /**
     * Returns the positions of the blocks of every group found in the snapshot, indexed by group.
     */
    static int[][] find(RefundConfig cfg, ChunkSnapshot snapshot, int minY, int maxY) {
        RefundGroup[] groups = cfg.groups();
        BlockPositions[] positions = new BlockPositions[groups.length];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = new BlockPositions();
        }

        int sections = (maxY - minY) >> 4;
        for (int section = 0; section < sections; section++) {
            if (snapshot.isSectionEmpty(section)) continue;

            int sectionMinY = minY + (section << 4);
            for (int y = sectionMinY; y < sectionMinY + 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        RefundGroup group = cfg.group(snapshot.getBlockType(x, y, z));
                        if (group != null) {
                            positions[group.index()].add(BlockPositions.pack(x, y - minY, z));
                        }
                    }
                }
            }
        }

        int[][] found = new int[groups.length][];
        for (int i = 0; i < found.length; i++) {
            found[i] = positions[i].toArray();
        }
        return found;
    }

    /**
     * Returns whether the cached counts of the chunk differ from what the snapshot holds.
     */
    private boolean isCacheOutdated(ChunkId id, RefundConfig cfg, int[][] found) {
        Optional<Storage> storageOptional = plugin.getWorldStorage().getWorld(id.worldUid()).get(id.chunkKey());
        if (storageOptional.isEmpty()) return false;

        Storage storage = storageOptional.get();
        for (RefundGroup group : cfg.groups()) {
            if (storage.count(cfg.limit(), group.scanObject()) != found[group.index()].length) return true;
        }
        return false;
    }

    /**
     * Removes the blocks beyond the limit, runs on the thread owning the chunk.
     */
    private void refund(ChunkId id, long since, RefundConfig cfg, int[][] found, int minY, int maxY, boolean outdated) {
        World world = plugin.getServer().getWorld(id.worldUid());
        boolean again = false;
        try {
            // Settings reloaded since, the next detection starts over with the new ones.
            if (world == null || getConfig() != cfg) return;
            if (!world.isChunkLoaded(id.chunkX(), id.chunkZ())) return;

            again = new RefundRun(this, cfg, world, id.chunkX(), id.chunkZ(), minY, maxY, found).run();

            // Rescanned after the removals, so the fresh counts include them.
            if (outdated) refresh(world, id);
        } finally {
            done(id, since, true);
            if (again) flag(cfg, world, id.chunkKey());
        }
    }

    /**
     * Rescans the chunk, replacing cached counts which turned out to be off.
     */
    private void refresh(World world, ChunkId id) {
        if (!plugin.getWorldChunkScanTracker().isQueued(id.worldUid(), id.chunkKey())) {
            InsightsApi.scanChunk(world, id.chunkX(), id.chunkZ(), storage -> { });
        }
    }

    private void done(ChunkId id, long since, boolean examined) {
        if (examined) {
            lastRuns.put(id, System.currentTimeMillis());
        }
        pending.remove(id, since);
    }
}
