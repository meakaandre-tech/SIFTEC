package com.meakaandre.siftec.building;

/** The Build Gun's tabs, in the order Tab cycles through them. */
public enum Category {
    PRODUCTION, POWER, LOGISTICS, FOUNDATIONS, SPECIAL;

    public String key() {
        return "siftec.build_gun.category." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Category of(String name) {
        return valueOf(name.toUpperCase(java.util.Locale.ROOT));
    }
}
