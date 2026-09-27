package dev.frankheijden.insights.api.concurrent.storage;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkStorage {

    // A chunk key holds x in its low and z in its high 32 bits, so Long#hashCode() is just x ^ z: nearby
    // chunks share very few hash values (every x == z chunk hashes to 0) and the map degenerates into long
    // collision chains and trees. Keys are therefore stored multiplied by an odd constant, a bijection on
    // longs (so keys stay unique and no two chunks share an entry) whose products spread over the table.
    // getChunks() still exposes the original chunk keys.
    private static final long SCRAMBLE = 0x9E3779B97F4A7C15L;
    private static final long UNSCRAMBLE = 0xF1DE83E19937733DL; // SCRAMBLE * UNSCRAMBLE == 1 (mod 2^64)

    private final Map<Long, Storage> distributionMap;
    private final Set<Long> chunks;

    /**
     * Constructs a new, empty ChunkStorage.
     */
    public ChunkStorage() {
        this.distributionMap = new ConcurrentHashMap<>();
        this.chunks = new ChunkKeySet(distributionMap.keySet());
    }

    static long scramble(long chunkKey) {
        return chunkKey * SCRAMBLE;
    }

    static long unscramble(long storedKey) {
        return storedKey * UNSCRAMBLE;
    }

    /**
     * Returns a live view of the stored chunk keys, backed by this storage.
     */
    public Set<Long> getChunks() {
        return chunks;
    }

    public Optional<Storage> get(long chunkKey) {
        return Optional.ofNullable(distributionMap.get(scramble(chunkKey)));
    }

    public void put(long chunkKey, Storage storage) {
        distributionMap.put(scramble(chunkKey), storage);
    }

    public void remove(long chunkKey) {
        distributionMap.remove(scramble(chunkKey));
    }

    /**
     * The map's key set translated back to chunk keys (same live, concurrent semantics as the key set).
     */
    private static final class ChunkKeySet extends AbstractSet<Long> {

        private final Set<Long> storedKeys;

        private ChunkKeySet(Set<Long> storedKeys) {
            this.storedKeys = storedKeys;
        }

        @Override
        public boolean contains(Object o) {
            Objects.requireNonNull(o); // like the concurrent key set
            return o instanceof Long chunkKey && storedKeys.contains(scramble(chunkKey));
        }

        @Override
        public boolean remove(Object o) {
            Objects.requireNonNull(o); // like the concurrent key set
            return o instanceof Long chunkKey && storedKeys.remove(scramble(chunkKey));
        }

        @Override
        public int size() {
            return storedKeys.size();
        }

        @Override
        public boolean isEmpty() {
            return storedKeys.isEmpty();
        }

        @Override
        public void clear() {
            storedKeys.clear();
        }

        @Override
        public Iterator<Long> iterator() {
            Iterator<Long> iterator = storedKeys.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Long next() {
                    return unscramble(iterator.next());
                }

                @Override
                public void remove() {
                    iterator.remove();
                }
            };
        }
    }
}
