package com.meakaandre.siftec.node;

/** One resource node. Only x and z are fixed by the seed; the height is wherever the ground turns out to be. */
public record Node(int x, int z, NodeType type, Purity purity) {
    public long key() {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public double distanceTo(double px, double pz) {
        return Math.sqrt((x - px) * (x - px) + (z - pz) * (z - pz));
    }
}
