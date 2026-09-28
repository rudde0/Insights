package dev.frankheijden.insights.api;

/**
 * The state of a single limit in the area a player is standing in.
 *
 * @param key the material/entity name the limit applies to (e.g. {@code HOPPER}), or the name of
 *            the limit itself for limits which span multiple materials/entities. Materials sharing
 *            one limit are joined by a slash (e.g. {@code BAMBOO/BAMBOO_SAPLING})
 * @param name the display name of the limit, as shown to players, joined the same way
 *            (e.g. {@code Bamboo/Bamboo sapling})
 * @param count the amount the area currently holds
 * @param limit the maximum amount the area allows
 */
public record LimitStatus(String key, String name, long count, int limit) {

    /**
     * Returns how much of this limit is still available, never negative.
     */
    public long remaining() {
        return Math.max(0, limit - count);
    }

    /**
     * Returns how full this limit is, where 1.0 means the limit is reached.
     */
    public double ratio() {
        return limit <= 0 ? 1D : (double) count / limit;
    }

    /**
     * Returns how full this limit is in percent, rounded down.
     */
    public int percentage() {
        return (int) Math.floor(ratio() * 100);
    }

    public boolean exceeded() {
        return count >= limit;
    }
}
