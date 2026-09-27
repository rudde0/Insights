package dev.frankheijden.insights.api.concurrent.storage;

import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import dev.frankheijden.insights.api.util.MaterialVariants;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DistributionStorage extends Distribution<ScanObject<?>> implements Storage {

    private final long createdAt = System.nanoTime();

    public DistributionStorage() {
        this(new ConcurrentHashMap<>());
    }

    public DistributionStorage(Map<ScanObject<?>, Long> map) {
        super(map);
    }

    /**
     * Constructs a new DistributionStorage from given material and entity distributions.
     */
    public static DistributionStorage of(Distribution<Material> materials, Distribution<EntityType> entities) {
        return of(materials.distributionMap, entities.distributionMap);
    }

    /**
     * Constructs a new DistributionStorage from given material and entity distribution maps.
     */
    public static DistributionStorage of(Map<Material, Long> materials, Map<EntityType, Long> entities) {
        Map<ScanObject<?>, Long> map = new ConcurrentHashMap<>();
        for (Map.Entry<Material, Long> entry : materials.entrySet()) {
            map.put(ScanObject.of(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<EntityType, Long> entry : entities.entrySet()) {
            map.put(ScanObject.of(entry.getKey()), entry.getValue());
        }
        return new DistributionStorage(map);
    }

    @Override
    public void modify(ScanObject<?> item, long amount) {
        if (amount >= 0 || item == null || item.getType() != ScanObject.Type.MATERIAL) {
            super.modify(item, amount);
            return;
        }

        // Some blocks turn into a variant of theirs without an event (e.g. a stem attaching to its
        // pumpkin, and detaching again once the pumpkin is harvested), so a removed block may still
        // be counted as one of its relatives. Whatever the block itself cannot cover is taken from those.
        Set<Material> relatives = MaterialVariants.getRelatives((Material) item.getObject());
        long excess = relatives.isEmpty() ? 0 : -amount - count(item);
        super.modify(item, amount);

        for (Material relative : relatives) {
            if (excess <= 0) break;
            ScanObject<?> relativeItem = ScanObject.of(relative);
            long taken = Math.min(excess, count(relativeItem));
            if (taken > 0) {
                super.modify(relativeItem, -taken);
                excess -= taken;
            }
        }
    }

    @Override
    public long getAgeMillis() {
        return (System.nanoTime() - createdAt) / 1_000_000L;
    }

    @Override
    public DistributionStorage copy(Map<ScanObject<?>, Long> map) {
        map.putAll(distributionMap);
        return new DistributionStorage(map);
    }
}
