package dev.frankheijden.insights.api.refund;

import dev.frankheijden.insights.api.config.Limits;
import dev.frankheijden.insights.api.config.Settings;
import dev.frankheijden.insights.api.config.limits.Limit;
import dev.frankheijden.insights.api.config.limits.LimitType;
import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The graceful refund settings resolved against the loaded limits, built once per (re)load.
 */
final class RefundConfig {

    // Blocks which hold more than an item can carry, handing them back would lose (e.g. stacked spawners) or
    // duplicate (e.g. the other half of a double chest) something. Blocks like these are skipped at removal
    // time as well, excluding them here only saves looking for them.
    private static final Set<Material> NEVER_REFUNDED = EnumSet.of(
            Material.CHEST,
            Material.TRAPPED_CHEST,
            Material.SPAWNER,
            Material.TRIAL_SPAWNER,
            Material.VAULT
    );

    private final Settings settings;
    private final Limits limits;
    private final Limit limit;
    private final RefundGroup[] groups;
    private final RefundGroup[] groupsByMaterial;

    private RefundConfig(
            Settings settings,
            Limits limits,
            Limit limit,
            RefundGroup[] groups,
            RefundGroup[] groupsByMaterial
    ) {
        this.settings = settings;
        this.limits = limits;
        this.limit = limit;
        this.groups = groups;
        this.groupsByMaterial = groupsByMaterial;
    }

    static RefundConfig disabled(Settings settings, Limits limits) {
        return new RefundConfig(settings, limits, null, new RefundGroup[0], new RefundGroup[0]);
    }

    /**
     * Resolves the refund settings, logging why nothing will be refunded if that is the case.
     */
    static RefundConfig create(Settings settings, Limits limits, Logger logger) {
        if (settings == null || limits == null || !settings.GRACEFUL_REFUND_ENABLED) {
            return disabled(settings, limits);
        }

        String fileName = settings.GRACEFUL_REFUND_LIMIT_FILE;
        Optional<Limit> limitOptional = limits.getLimitByFileName(fileName);
        if (limitOptional.isEmpty()) {
            logger.warning("Graceful refund is disabled: limit file '" + fileName + "' is not loaded.");
            return disabled(settings, limits);
        }

        Limit limit = limitOptional.get();
        if (limit.getType() != LimitType.PERMISSION) {
            logger.warning("Graceful refund is disabled: limit file '" + fileName + "' is of type "
                    + limit.getType() + ", only PERMISSION limits (a limit per material) are supported.");
            return disabled(settings, limits);
        }

        Material[] materials = Material.values();
        RefundGroup[] groupsByMaterial = new RefundGroup[materials.length];
        List<RefundGroup> groups = new ArrayList<>();
        for (Material material : settings.GRACEFUL_REFUND_MATERIALS) {
            if (groupsByMaterial[material.ordinal()] != null) continue;

            int amount = limit.getLimit(material).getLimit();
            if (!limit.getMaterials().contains(material) || amount < 0) {
                logger.warning("Graceful refund: " + material + " is not limited in '" + fileName + "', ignoring it.");
                continue;
            }

            Material refundMaterial = refundMaterialOf(material);
            if (refundMaterial == null || NEVER_REFUNDED.contains(material) || Tag.SHULKER_BOXES.isTagged(material)) {
                logger.warning("Graceful refund: " + material + " can't be handed back as an item, ignoring it.");
                continue;
            }

            Set<Material> members = EnumSet.of(material);
            for (ScanObject<?> scanObject : limit.getScanObjects(ScanObject.of(material))) {
                if (scanObject.getObject() instanceof Material member) {
                    members.add(member);
                }
            }

            // Members are counted towards one limit only, so groups never overlap. Checked regardless, a material
            // belonging to two groups would be counted (and removed) for either of them.
            boolean overlaps = false;
            for (Material member : members) {
                overlaps |= groupsByMaterial[member.ordinal()] != null;
            }
            if (overlaps) {
                logger.warning("Graceful refund: " + material + " is counted together with another material, ignoring it.");
                continue;
            }

            RefundGroup group = new RefundGroup(
                    groups.size(),
                    material,
                    Collections.unmodifiableSet(members),
                    ScanObject.of(material),
                    refundMaterial,
                    amount
            );
            groups.add(group);
            for (Material member : members) {
                groupsByMaterial[member.ordinal()] = group;
            }
        }

        if (groups.isEmpty()) {
            logger.warning("Graceful refund is disabled: none of its materials can be refunded.");
            return disabled(settings, limits);
        }
        return new RefundConfig(settings, limits, limit, groups.toArray(new RefundGroup[0]), groupsByMaterial);
    }

    /**
     * Returns the item a player places the given block with, or null if there is none (e.g. for a spawner).
     */
    private static Material refundMaterialOf(Material material) {
        if (material.isItem()) {
            return material.isAir() ? null : material;
        }
        if (!material.isBlock()) return null;

        // E.g. a pumpkin stem is placed with pumpkin seeds.
        Material placementMaterial = material.createBlockData().getPlacementMaterial();
        return placementMaterial.isItem() && !placementMaterial.isAir() ? placementMaterial : null;
    }

    boolean isFor(Settings settings, Limits limits) {
        return this.settings == settings && this.limits == limits;
    }

    boolean isEnabled() {
        return limit != null;
    }

    Limit limit() {
        return limit;
    }

    RefundGroup[] groups() {
        return groups;
    }

    /**
     * Returns the group the given material is counted in, or null if it is not refunded.
     */
    RefundGroup group(Material material) {
        return groupsByMaterial[material.ordinal()];
    }

    boolean appliesTo(World world) {
        return limit.getSettings().appliesToWorld(world.getName());
    }

    int delayTicks() {
        return settings.GRACEFUL_REFUND_DELAY_TICKS;
    }

    long cooldownMillis() {
        return settings.GRACEFUL_REFUND_COOLDOWN_SECONDS * 1000L;
    }

    int maxBlocksPerRun() {
        return settings.GRACEFUL_REFUND_MAX_BLOCKS_PER_RUN;
    }

    int notifyRadius() {
        return settings.GRACEFUL_REFUND_NOTIFY_RADIUS;
    }

    boolean log() {
        return settings.GRACEFUL_REFUND_LOG;
    }
}
