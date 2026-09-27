package dev.frankheijden.insights.api.concurrent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.frankheijden.insights.api.utils.ChunkUtils;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

class ChunkStorageTest {

    private static final int[] EDGE_COORDINATES = {0, 1, -1, 2, -2, 15, 16, -16, 1875000, -1875000, 29999984, -29999984, Integer.MAX_VALUE, Integer.MIN_VALUE};

    @Test
    void scrambleIsABijection() {
        Random random = new Random(42);
        for (int i = 0; i < 1_000_000; i++) {
            long key = random.nextLong();
            assertThat(ChunkStorage.unscramble(ChunkStorage.scramble(key))).isEqualTo(key);
            assertThat(ChunkStorage.scramble(ChunkStorage.unscramble(key))).isEqualTo(key);
        }
        for (int x : EDGE_COORDINATES) {
            for (int z : EDGE_COORDINATES) {
                long key = ChunkUtils.getKey(x, z);
                assertThat(ChunkStorage.unscramble(ChunkStorage.scramble(key))).isEqualTo(key);
            }
        }
        assertThat(ChunkStorage.unscramble(ChunkStorage.scramble(Long.MIN_VALUE))).isEqualTo(Long.MIN_VALUE);
        assertThat(ChunkStorage.unscramble(ChunkStorage.scramble(Long.MAX_VALUE))).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void behavesLikeAMapOfChunkKeys() {
        Random random = new Random(7);
        ChunkStorage chunkStorage = new ChunkStorage();
        Map<Long, Storage> model = new HashMap<>();
        Storage[] storages = {new DistributionStorage(), new DistributionStorage(), new DistributionStorage()};
        for (int i = 0; i < 200_000; i++) {
            long key = randomChunkKey(random);
            switch (random.nextInt(4)) {
                case 0 -> {
                    Storage storage = storages[random.nextInt(storages.length)];
                    chunkStorage.put(key, storage);
                    model.put(key, storage);
                }
                case 1 -> {
                    chunkStorage.remove(key);
                    model.remove(key);
                }
                default -> assertThat(chunkStorage.get(key).orElse(null)).isSameAs(model.get(key));
            }
            if (i % 20_000 == 0) {
                assertThat(new HashSet<>(chunkStorage.getChunks())).isEqualTo(model.keySet());
            }
        }
        for (Map.Entry<Long, Storage> entry : model.entrySet()) {
            assertThat(chunkStorage.get(entry.getKey())).containsSame(entry.getValue());
        }
        Set<Long> chunks = chunkStorage.getChunks();
        assertThat(chunks).hasSameSizeAs(model.keySet());
        assertThat(new HashSet<>(chunks)).isEqualTo(model.keySet());
        assertThat(chunks.hashCode()).isEqualTo(model.keySet().hashCode());
        assertThat(chunks).isEqualTo(model.keySet());
    }

    @Test
    void chunksIsALiveView() {
        ChunkStorage chunkStorage = new ChunkStorage();
        Set<Long> chunks = chunkStorage.getChunks();
        assertThat(chunks).isEmpty();
        assertThat(chunkStorage.getChunks()).isSameAs(chunks);

        final long a = ChunkUtils.getKey(3, -7);
        final long b = ChunkUtils.getKey(-30000, 12);
        final long c = ChunkUtils.getKey(0, 0);
        chunkStorage.put(a, new DistributionStorage());
        chunkStorage.put(b, new DistributionStorage());
        assertThat(chunks).containsExactlyInAnyOrder(a, b);
        assertThat(chunks.contains(a)).isTrue();
        assertThat(chunks.contains(c)).isFalse();
        assertThat(chunks.contains("not a chunk key")).isFalse();
        assertThatThrownBy(() -> chunks.contains(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chunks.remove(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> chunks.add(c)).isInstanceOf(UnsupportedOperationException.class);

        assertThat(chunks.remove(a)).isTrue();
        assertThat(chunks.remove(a)).isFalse();
        assertThat(chunkStorage.get(a)).isEmpty();
        assertThat(chunks).containsExactly(b);

        chunkStorage.put(c, new DistributionStorage());
        Iterator<Long> iterator = chunks.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() == c) {
                iterator.remove();
            }
        }
        assertThat(chunkStorage.get(c)).isEmpty();
        assertThat(chunks).containsExactly(b);

        chunks.clear();
        assertThat(chunkStorage.get(b)).isEmpty();
        assertThat(chunks).isEmpty();
    }

    private static long randomChunkKey(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> ChunkUtils.getKey(random.nextInt(64) - 32, random.nextInt(64) - 32);
            case 1 -> ChunkUtils.getKey(random.nextInt(4000) - 2000, random.nextInt(4000) - 2000);
            default -> ChunkUtils.getKey(EDGE_COORDINATES[random.nextInt(EDGE_COORDINATES.length)],
                    EDGE_COORDINATES[random.nextInt(EDGE_COORDINATES.length)]);
        };
    }
}
