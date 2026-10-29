package dev.frankheijden.insights.api.refund;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps how many refunds every chunk got within the refund window, so a chunk which keeps going beyond its limit
 * (e.g. a farm, or blocks placed by a machine) can't be used to harvest blocks into the refund chest over and over.
 *
 * <p>Kept in memory only, a restart forgets it. Only chunks which actually got a refund within the window are kept,
 * and expired entries are pruned once the map has doubled in size since the last prune, so the work of pruning
 * stays constant per refund. Entries are deliberately kept when a chunk unloads, as unloading it would otherwise
 * reset its quota.</p>
 */
final class RefundQuota {

    private static final int MIN_PRUNE_SIZE = 1024;

    private final Map<ChunkId, Window> windows = new ConcurrentHashMap<>();
    private volatile int pruneSize = MIN_PRUNE_SIZE;

    /**
     * Returns whether the chunk got as many refunds within the window as it may get.
     */
    boolean isExhausted(ChunkId id, RefundConfig cfg, long now) {
        int maxRefunds = cfg.maxRefundsPerChunk();
        if (maxRefunds < 0) return false;

        Window window = windows.get(id);
        return window != null && !window.isExpired(now, cfg.refundWindowMillis()) && window.refunds() >= maxRefunds;
    }

    /**
     * Counts a refund of the chunk, a new window starting with it if the previous one expired.
     */
    void record(ChunkId id, RefundConfig cfg, long now) {
        if (cfg.maxRefundsPerChunk() < 0) return;

        long windowMillis = cfg.refundWindowMillis();
        windows.compute(id, (chunk, window) -> window == null || window.isExpired(now, windowMillis)
                ? new Window(now, 1)
                : new Window(window.start(), window.refunds() + 1));

        if (windows.size() > pruneSize) {
            windows.values().removeIf(window -> window.isExpired(now, windowMillis));
            pruneSize = Math.max(MIN_PRUNE_SIZE, windows.size() * 2);
        }
    }

    private record Window(long start, int refunds) {

        boolean isExpired(long now, long windowMillis) {
            return now - start >= windowMillis;
        }
    }
}
