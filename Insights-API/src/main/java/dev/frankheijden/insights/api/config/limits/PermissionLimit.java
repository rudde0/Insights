package dev.frankheijden.insights.api.config.limits;

import dev.frankheijden.insights.api.concurrent.ScanOptions;
import dev.frankheijden.insights.api.config.parser.YamlParseException;
import dev.frankheijden.insights.api.config.parser.YamlParser;
import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import dev.frankheijden.insights.api.util.MaterialVariants;
import dev.frankheijden.insights.api.utils.EnumUtils;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PermissionLimit extends Limit {

    private final Map<Material, Integer> materials;
    private final Map<EntityType, Integer> entities;
    private final Set<ScanObject<?>> scanObjects;
    private final ScanOptions scanOptions;

    // Variants which are not configured themselves share the limit of their block (e.g. bamboo saplings
    // fall under bamboo), and the block and its variants are counted together.
    private final Map<Material, Material> variantMaterials;
    private final Set<Material> limitedMaterials;
    private final Map<ScanObject<?>, Set<ScanObject<?>>> countedScanObjects;

    protected PermissionLimit(Info info, Map<Material, Integer> materials, Map<EntityType, Integer> entities) {
        super(LimitType.PERMISSION, info);
        this.materials = Collections.unmodifiableMap(materials);
        this.entities = Collections.unmodifiableMap(entities);
        this.scanObjects = Collections.unmodifiableSet(ScanObject.of(materials.keySet(), entities.keySet()));

        Map<Material, Material> variantMaterials = new EnumMap<>(Material.class);
        Map<ScanObject<?>, Set<ScanObject<?>>> countedScanObjects = new HashMap<>();
        for (Material material : materials.keySet()) {
            Set<Material> counted = EnumSet.of(material);
            for (Material variant : MaterialVariants.getVariants(material)) {
                if (materials.containsKey(variant)) continue;
                variantMaterials.put(variant, material);
                counted.add(variant);
            }
            if (counted.size() == 1) continue;

            Set<ScanObject<?>> countedObjects = Collections.unmodifiableSet(
                    ScanObject.of(counted, Collections.emptySet())
            );
            for (Material m : counted) {
                countedScanObjects.put(ScanObject.of(m), countedObjects);
            }
        }

        this.variantMaterials = Collections.unmodifiableMap(variantMaterials);
        this.countedScanObjects = Collections.unmodifiableMap(countedScanObjects);
        this.limitedMaterials = Collections.unmodifiableSet(MaterialVariants.withVariants(materials.keySet()));
        this.scanOptions = determineScanOptions();
    }

    /**
     * Parses a PermissionLimit.
     */
    public static PermissionLimit parse(YamlParser parser, Info info) throws YamlParseException {
        Map<Material, Integer> materials = new EnumMap<>(Material.class);
        for (String key : parser.getKeys("limit.materials")) {
            String fullKey = "limit.materials." + key;
            Material material = parser.checkEnum(fullKey, key, Material.class, null, "material");
            int limit = parser.getInt(fullKey, -1, 0, Integer.MAX_VALUE);
            materials.put(material, limit);
        }

        Map<EntityType, Integer> entities = new EnumMap<>(EntityType.class);
        for (String key : parser.getKeys("limit.entities")) {
            String fullKey = "limit.entities." + key;
            EntityType entity = parser.checkEnum(fullKey, key, EntityType.class, null, "entity");
            int limit = parser.getInt(fullKey, -1, 0, Integer.MAX_VALUE);
            entities.put(entity, limit);
        }

        return new PermissionLimit(info, materials, entities);
    }

    @Override
    public LimitInfo getLimit(Material m) {
        Material limited = variantMaterials.getOrDefault(m, m);
        return new LimitInfo(EnumUtils.pretty(limited), materials.getOrDefault(limited, -1));
    }

    @Override
    public LimitInfo getLimit(EntityType e) {
        return new LimitInfo(EnumUtils.pretty(e), entities.getOrDefault(e, -1));
    }

    /**
     * Returns the materials this limit applies to, including variants sharing the limit of their block.
     */
    @Override
    public Set<Material> getMaterials() {
        return limitedMaterials;
    }

    public Set<EntityType> getEntities() {
        return entities.keySet();
    }

    @Override
    public Set<? extends ScanObject<?>> getScanObjects() {
        return scanObjects;
    }

    /**
     * Each material/entity has its own limit, only a block and its variants are counted together.
     */
    @Override
    public Set<? extends ScanObject<?>> getScanObjects(ScanObject<?> item) {
        Set<ScanObject<?>> counted = countedScanObjects.get(item);
        return counted == null ? Collections.singleton(item) : counted;
    }

    @Override
    public ScanOptions getScanOptions() {
        return scanOptions;
    }
}
