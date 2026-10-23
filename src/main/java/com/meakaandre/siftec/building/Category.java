package com.meakaandre.siftec.building;

import java.util.Locale;

/** The Build Gun's tabs, in the order Tab cycles through them. */
public enum Category {
    PRODUCTION, POWER, LOGISTICS, FOUNDATIONS, SPECIAL;

    public String key() {
        return "siftec.build_gun.category." + name().toLowerCase(Locale.ROOT);
    }

    public static Category of(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }
}
