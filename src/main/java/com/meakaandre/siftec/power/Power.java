package com.meakaandre.siftec.power;

import org.antarcticgardens.cna.config.CNAConfig;

import java.util.Locale;

/**
 * The one power scale of the pack. Satisfactory's megawatts are SU: 1 MW = 204.8 SU, so a Biomass Burner's 30 MW is
 * 6,144 SU and a Constructor's 4 MW is 819.2 SU. Underneath, the grid is Create: New Age electricity, and FE follows
 * New Age's own SU-to-energy rate (its {@code suToEnergy}, 0.029296875 FE a tick per SU by default), so a New Age motor
 * fed a building's FE gives exactly that building's SU. At the default rate 1 MW is 6 FE a tick.
 * <p>
 * The grid itself is balanced in SU ({@link PowerGrids}); only what goes into or comes out of a New Age block is
 * turned into FE.
 */
public final class Power {
    /** SU per Satisfactory megawatt. */
    public static final double SU_PER_MW = 204.8;
    /** Ticks in an hour, for the storage in SU-hours (Satisfactory shows MWh). */
    public static final double TICKS_PER_HOUR = 72_000;

    private Power() {
    }

    /** New Age's FE a tick for one SU (its server config). */
    public static double fePerSu() {
        try {
            return CNAConfig.getServer().suToEnergy.get();
        } catch (RuntimeException e) {
            return 0.029296875;
        }
    }

    public static double su(double mw) {
        return mw * SU_PER_MW;
    }

    public static double feOfSu(double su) {
        return su * fePerSu();
    }

    public static double suOfFe(double fe) {
        double rate = fePerSu();
        return rate <= 0 ? 0 : fe / rate;
    }

    /** Energy in SU-ticks for Satisfactory megajoules (1 MJ is 1 MW for a second, 20 ticks). */
    public static double suTicksOfMj(double mj) {
        return mj * SU_PER_MW * 20;
    }

    /** "6,144 SU", rounded to a whole SU. */
    public static String su(float su) {
        return String.format(Locale.ROOT, "%,d SU", Math.round(su));
    }

    public static String fmt(double su) {
        return String.format(Locale.ROOT, "%,d", Math.round(su));
    }

    /** SU-ticks as SU-hours, the way Satisfactory shows a Power Storage in MWh. */
    public static String suHours(double suTicks) {
        double h = suTicks / TICKS_PER_HOUR;
        return h >= 100 ? String.format(Locale.ROOT, "%,d SUh", Math.round(h)) : String.format(Locale.ROOT, "%.1f SUh", h);
    }
}
