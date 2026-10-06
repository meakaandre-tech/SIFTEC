package com.meakaandre.siftec.node;

/**
 * One resource node. x and z are fixed by the seed. Surface nodes sit wherever the ground turns out to be;
 * cave nodes (sulfur) also carry the height they were found at.
 */
public record Node(int x, int z, NodeType type, Purity purity, int y) {
    public static final int SURFACE = Integer.MIN_VALUE;

    public Node(int x, int z, NodeType type, Purity purity) {
        this(x, z, type, purity, SURFACE);
    }

    public long key() {
        return (((long) x << 32) ^ (z & 0xffffffffL)) * 31 + type.ordinal();
    }

    public double distanceTo(double px, double pz) {
        return Math.sqrt((x - px) * (x - px) + (z - pz) * (z - pz));
    }
}
