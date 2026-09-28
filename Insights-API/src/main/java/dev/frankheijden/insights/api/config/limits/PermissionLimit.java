package dev.frankheijden.insights.api.config.limits;

import dev.frankheijden.insights.api.concurrent.ScanOptions;
import dev.frankheijden.insights.api.config.ConfigError;
import dev.frankheijden.insights.api.config.parser.YamlParseException;
import dev.frankheijden.insights.api.config.parser.YamlParser;
import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import dev.frankheijden.insights.api.util.MaterialVariants;
import dev.frankheijden.insights.api.utils.EnumUtils;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

public class PermissionLimit extends Limit {

    /**
     * Separates the materials of a material key which share a single limit, e.g. {@code BAMBOO/BAMBOO_SAPLING: 128}.
     */
    public static final String GROUP_SEPARATOR = "/";

    private final Map<EntityType, Integer> entities;
    private final Set<ScanObject<?>> scanObjects;
    private final Set<ScanObject<?>> entryObjects;
    private final ScanOptions scanOptions;

    // Materials of one entry (e.g. BAMBOO/BAMBOO_SAPLING) share its limit and are counted together. Variants which
    // are not configured themselves share the limit of their block (e.g. bamboo saplings fall under bamboo).
    // Everything is resolved once here, so lookups while placing blocks are a single map get without allocations.
    private final Map<Material, LimitInfo> materialInfos;
    private final Map<Material, String> materialKeys;
    private final Set<Material> limitedMaterials;
    private final Map<ScanObject<?>, Set<ScanObject<?>>> countedScanObjects;

    protected PermissionLimit(Info info, Map<Material, Integer> materials, Map<EntityType, Integer> entities) {
        this(info, materials, entities, Collections.emptyList());
    }

    /**
     * Constructs a PermissionLimit, where each of the given material groups shares a single limit.
     * Every material of a group must be present in the materials map, holding the limit of the group.
     */
    protected PermissionLimit(
            Info info,
            Map<Material, Integer> materials,
            Map<EntityType, Integer> entities,
            List<? extends Set<Material>> materialGroups
    ) {
        super(LimitType.PERMISSION, info);
        this.entities = Collections.unmodifiableMap(entities);
        this.scanObjects = Collections.unmodifiableSet(ScanObject.of(materials.keySet(), entities.keySet()));

        List<Set<Material>> entries = new ArrayList<>(materials.size());
        Set<Material> grouped = EnumSet.noneOf(Material.class);
        for (Set<Material> group : materialGroups) {
            entries.add(group);
            grouped.addAll(group);
        }
        for (Material material : materials.keySet()) {
            if (!grouped.contains(material)) entries.add(Collections.singleton(material));
        }

        Map<Material, LimitInfo> materialInfos = new EnumMap<>(Material.class);
        Map<Material, String> materialKeys = new EnumMap<>(Material.class);
        Map<ScanObject<?>, Set<ScanObject<?>>> countedScanObjects = new HashMap<>();
        Set<ScanObject<?>> entryObjects = new LinkedHashSet<>();
        for (Set<Material> entry : entries) {
            Material first = entry.iterator().next();
            StringJoiner key = new StringJoiner(GROUP_SEPARATOR);
            StringJoiner name = new StringJoiner(GROUP_SEPARATOR);
            for (Material member : entry) {
                key.add(member.name());
                name.add(EnumUtils.pretty(member));
            }
            var limitInfo = new LimitInfo(name.toString(), materials.get(first));

            Set<Material> counted = EnumSet.copyOf(entry);
            for (Material member : entry) {
                for (Material variant : MaterialVariants.getVariants(member)) {
                    if (!materials.containsKey(variant)) counted.add(variant);
                }
            }

            for (Material m : counted) {
                materialInfos.put(m, limitInfo);
                materialKeys.put(m, key.toString());
            }
            entryObjects.add(ScanObject.of(first));

            if (counted.size() == 1) continue;
            Set<ScanObject<?>> countedObjects = Collections.unmodifiableSet(
                    ScanObject.of(counted, Collections.emptySet())
            );
            for (Material m : counted) {
                countedScanObjects.put(ScanObject.of(m), countedObjects);
            }
        }
        for (EntityType entity : entities.keySet()) {
            entryObjects.add(ScanObject.of(entity));
        }

        this.materialInfos = Collections.unmodifiableMap(materialInfos);
        this.materialKeys = Collections.unmodifiableMap(materialKeys);
        this.countedScanObjects = Collections.unmodifiableMap(countedScanObjects);
        this.entryObjects = Collections.unmodifiableSet(entryObjects);
        this.limitedMaterials = Collections.unmodifiableSet(materialInfos.isEmpty()
                ? EnumSet.noneOf(Material.class)
                : EnumSet.copyOf(materialInfos.keySet()));
        this.scanOptions = determineScanOptions();
    }

    /**
     * Parses a PermissionLimit.
     * A material key may name several materials separated by {@link #GROUP_SEPARATOR}, which then share one limit.
     */
    public static PermissionLimit parse(YamlParser parser, Info info) throws YamlParseException {
        Map<Material, Integer> materials = new EnumMap<>(Material.class);
        List<Set<Material>> materialGroups = new ArrayList<>();
        for (String key : parser.getKeys("limit.materials")) {
            String fullKey = "limit.materials." + key;
            Set<Material> group = new LinkedHashSet<>();
            for (String part : key.split(GROUP_SEPARATOR)) {
                String name = part.trim();
                if (name.isEmpty()) continue;
                Material material = parser.checkEnum(fullKey, name, Material.class, null, "material");
                if (material == null) continue;
                if (materials.containsKey(material) || group.contains(material)) {
                    parser.addError(new ConfigError(parser.getName(), fullKey,
                            "material " + material + " is limited more than once, ignoring it here"));
                    continue;
                }
                group.add(material);
            }
            if (group.isEmpty()) continue;

            int limit = parser.getInt(fullKey, -1, 0, Integer.MAX_VALUE);
            for (Material material : group) {
                materials.put(material, limit);
            }
            if (group.size() > 1) materialGroups.add(group);
        }

        Map<EntityType, Integer> entities = new EnumMap<>(EntityType.class);
        for (String key : parser.getKeys("limit.entities")) {
            String fullKey = "limit.entities." + key;
            EntityType entity = parser.checkEnum(fullKey, key, EntityType.class, null, "entity");
            int limit = parser.getInt(fullKey, -1, 0, Integer.MAX_VALUE);
            entities.put(entity, limit);
        }

        return new PermissionLimit(info, materials, entities, materialGroups);
    }

    @Override
    public LimitInfo getLimit(Material m) {
        LimitInfo limitInfo = materialInfos.get(m);
        return limitInfo == null ? new LimitInfo(EnumUtils.pretty(m), -1) : limitInfo;
    }

    @Override
    public LimitInfo getLimit(EntityType e) {
        return new LimitInfo(EnumUtils.pretty(e), entities.getOrDefault(e, -1));
    }

    /**
     * Returns the key of the limit the given item falls under, the names of the materials sharing it joined by
     * {@link #GROUP_SEPARATOR} (e.g. {@code BAMBOO/BAMBOO_SAPLING}). Variants return the key of their block.
     */
    public String getKey(ScanObject<?> item) {
        if (item.getObject() instanceof Material material) {
            String key = materialKeys.get(material);
            if (key != null) return key;
        }
        return item.name();
    }

    /**
     * Returns one object per configured limit: the first material of each material key, and every entity.
     */
    public Set<ScanObject<?>> getEntryObjects() {
        return entryObjects;
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
     * Each material key/entity has its own limit, only the materials sharing it and their variants are counted
     * together.
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
