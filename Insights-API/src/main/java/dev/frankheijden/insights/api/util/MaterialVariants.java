package dev.frankheijden.insights.api.util;

import org.bukkit.Material;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Blocks which the game turns into a different block than the one players think of.
 * Placing bamboo on the ground puts down a BAMBOO_SAPLING, which only turns into BAMBOO once a
 * stalk grows on top of it, and a pumpkin/melon stem becomes an attached stem while its fruit is
 * next to it. A limit configured for a block also covers its variants, and both are counted
 * together. A limit configured for a variant only covers that variant.
 */
public final class MaterialVariants {

    private static final Map<Material, Set<Material>> variants = new EnumMap<>(Material.class);
    private static final Map<Material, Set<Material>> relatives = new EnumMap<>(Material.class);

    static {
        register(Material.BAMBOO, Material.BAMBOO_SAPLING);
        register(Material.PUMPKIN_STEM, Material.ATTACHED_PUMPKIN_STEM);
        register(Material.MELON_STEM, Material.ATTACHED_MELON_STEM);
    }

    private MaterialVariants() {}

    private static void register(Material material, Material variant) {
        Set<Material> materialVariants = variants.computeIfAbsent(material, k -> EnumSet.noneOf(Material.class));
        materialVariants.add(variant);

        Set<Material> family = EnumSet.of(material);
        family.addAll(materialVariants);
        for (Material member : family) {
            Set<Material> others = EnumSet.copyOf(family);
            others.remove(member);
            relatives.put(member, others);
        }
    }

    /**
     * Returns the variants of given material, excluding the material itself.
     */
    public static Set<Material> getVariants(Material material) {
        Set<Material> set = variants.get(material);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    /**
     * Returns the block and variants the given material belongs to, excluding the material itself.
     * For a variant, this is its block and the other variants of that block.
     */
    public static Set<Material> getRelatives(Material material) {
        Set<Material> set = relatives.get(material);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
    }

    /**
     * Returns the given materials including all of their variants.
     * The given set is returned as-is if none of its materials have a variant missing from it.
     */
    public static Set<Material> withVariants(Set<Material> materials) {
        Set<Material> result = null;
        for (Material material : materials) {
            for (Material variant : getVariants(material)) {
                if (materials.contains(variant)) continue;
                if (result == null) {
                    result = EnumSet.noneOf(Material.class);
                    result.addAll(materials);
                }
                result.add(variant);
            }
        }
        return result == null ? materials : result;
    }
}
