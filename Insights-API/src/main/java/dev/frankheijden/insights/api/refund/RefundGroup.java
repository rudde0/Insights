package dev.frankheijden.insights.api.refund;

import dev.frankheijden.insights.api.objects.wrappers.ScanObject;
import org.bukkit.Material;
import java.util.Set;

/**
 * A material which is refunded, together with the variants counted towards its limit (e.g. attached stems).
 *
 * @param index the position of this group in {@link RefundConfig#groups()}
 * @param material the material the limit is configured for
 * @param members every material counted towards the limit, including the material itself
 * @param scanObject the scan object of the material, counting all members in a storage
 * @param refundMaterial the item handed back for every block removed
 * @param limit the amount a chunk may hold
 */
record RefundGroup(
        int index,
        Material material,
        Set<Material> members,
        ScanObject<?> scanObject,
        Material refundMaterial,
        int limit
) {

    boolean contains(Material type) {
        return members.contains(type);
    }
}
