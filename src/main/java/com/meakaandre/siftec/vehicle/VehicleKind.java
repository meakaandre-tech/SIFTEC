package com.meakaandre.siftec.vehicle;

import org.jspecify.annotations.Nullable;

/**
 * The three vehicles of work package 14 and how each drives. Speeds are blocks a tick; {@code mw} is what it burns
 * while driving (Satisfactory's figure), drawn from the fuel's megajoules ({@link VehicleEntity#fuelMj}).
 * {@code rows} is its storage in rows of nine; {@code seat} where the driver sits, in blocks from the middle of its
 * footprint (x right, y up, z forward). {@code width}, {@code length} and {@code height} are the space it needs to be
 * put down (its footprint in blocks), and its collision box is {@code width} by {@code height}.
 */
public enum VehicleKind {
    // Satisfactory 1.0: Tractor 55 MW, Truck 75 MW, Explorer 75 MW
    TRACTOR("tractor", 0.45, 0.012, 3.5, 55, 3, 3, 4, 3, 2.6f, 2.4f, 0, 1.5, -1.0),
    TRUCK("truck", 0.38, 0.008, 2.5, 75, 6, 3, 6, 4, 3.0f, 3.2f, 0, 2.0, 1.0),
    EXPLORER("explorer", 0.75, 0.02, 5.0, 75, 1, 2, 3, 2, 2.0f, 1.8f, 0, 1.5, -0.5);

    public final String id;
    public final double maxSpeed, accel, turnDegrees;
    public final double mw;
    public final int rows, width, length, height;
    public final float boxWidth, boxHeight;
    public final double seatX, seatY, seatZ;

    VehicleKind(String id, double maxSpeed, double accel, double turnDegrees, double mw, int rows, int width, int length, int height,
                float boxWidth, float boxHeight, double seatX, double seatY, double seatZ) {
        this.id = id;
        this.maxSpeed = maxSpeed;
        this.accel = accel;
        this.turnDegrees = turnDegrees;
        this.mw = mw;
        this.rows = rows;
        this.width = width;
        this.length = length;
        this.height = height;
        this.boxWidth = boxWidth;
        this.boxHeight = boxHeight;
        this.seatX = seatX;
        this.seatY = seatY;
        this.seatZ = seatZ;
    }

    public int slots() {
        return rows * 9;
    }

    /** Megajoules its tank holds: four buckets of Fuel (25 m3 a bucket at 750 MJ a m3, as {@code mb_per_m3} has it). */
    public double tankMj() {
        return 75_000;
    }

    public static @Nullable VehicleKind of(String id) {
        for (VehicleKind k : values()) if (k.id.equals(id)) return k;
        return null;
    }
}
