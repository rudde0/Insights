package dev.frankheijden.insights.api.refund;

import java.util.Arrays;

/**
 * A growable list of block positions within a single chunk, each packed into one int.
 * The height is stored relative to the world's minimum height, so it is never negative.
 */
final class BlockPositions {

    private int[] positions = new int[16];
    private int size = 0;

    static int pack(int x, int relativeY, int z) {
        return relativeY << 8 | (z & 15) << 4 | (x & 15);
    }

    static int localX(int position) {
        return position & 15;
    }

    static int relativeY(int position) {
        return position >>> 8;
    }

    static int localZ(int position) {
        return position >>> 4 & 15;
    }

    void add(int position) {
        if (size == positions.length) {
            positions = Arrays.copyOf(positions, size * 2);
        }
        positions[size++] = position;
    }

    int[] toArray() {
        return Arrays.copyOf(positions, size);
    }
}
