package dev.frankheijden.insights.api.refund;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;

class BlockPositionsTest {

    // The tallest world a data pack can define is 4064 blocks high.
    private static final int MAX_RELATIVE_Y = 4063;

    @Test
    void packsEveryPositionOfAChunkUniquely() {
        Set<Integer> packed = new HashSet<>();
        for (int y = 0; y <= MAX_RELATIVE_Y; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int position = BlockPositions.pack(x, y, z);
                    assertThat(position).isNotNegative();
                    assertThat(BlockPositions.localX(position)).isEqualTo(x);
                    assertThat(BlockPositions.relativeY(position)).isEqualTo(y);
                    assertThat(BlockPositions.localZ(position)).isEqualTo(z);
                    packed.add(position);
                }
            }
        }
        assertThat(packed).hasSize((MAX_RELATIVE_Y + 1) * 256);
    }

    @Test
    void keepsWorldCoordinatesWithinTheChunk() {
        int position = BlockPositions.pack(-1, 10, 17);
        assertThat(BlockPositions.localX(position)).isEqualTo(15);
        assertThat(BlockPositions.localZ(position)).isEqualTo(1);
        assertThat(BlockPositions.relativeY(position)).isEqualTo(10);
    }

    @Test
    void growsAndKeepsOrder() {
        BlockPositions positions = new BlockPositions();
        for (int i = 0; i < 1000; i++) {
            positions.add(i);
        }
        int[] array = positions.toArray();
        assertThat(array).hasSize(1000);
        for (int i = 0; i < array.length; i++) {
            assertThat(array[i]).isEqualTo(i);
        }
        assertThat(new BlockPositions().toArray()).isEmpty();
    }
}
