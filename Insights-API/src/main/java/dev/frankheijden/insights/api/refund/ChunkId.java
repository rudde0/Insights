package dev.frankheijden.insights.api.refund;

import dev.frankheijden.insights.api.utils.ChunkUtils;
import java.util.UUID;

record ChunkId(UUID worldUid, long chunkKey) {

    int chunkX() {
        return ChunkUtils.getX(chunkKey);
    }

    int chunkZ() {
        return ChunkUtils.getZ(chunkKey);
    }
}
