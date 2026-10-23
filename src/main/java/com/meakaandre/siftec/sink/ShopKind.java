package com.meakaandre.siftec.sink;

/** What an AWESOME Shop entry gives ({@link Shop.Entry#kind()}: the part of its "gives" before the colon). */
public enum ShopKind {
    CUSTOMIZER("customizer"),
    SWATCH("swatch"),
    FINISH("finish"),
    SKIN("skin"),
    DECOR("decor"),
    CUP("cup"),
    SIGN("sign"),
    UNLOCK("unlock"),
    MATERIAL("material"),
    PATTERN("pattern"),
    EMOTE("emote"),
    HELMET("helmet"),
    TRIM("trim"),
    BANNER("banner"),
    GOLDEN_CART("golden_cart"),
    BOOM_BOX("boom_box"),
    TAPE("tape");

    private final String id;

    ShopKind(String id) {
        this.id = id;
    }

    /** Its id, as the data writes it. */
    public String id() {
        return id;
    }

    public static ShopKind of(String id) {
        for (ShopKind v : values()) if (v.id.equals(id)) return v;
        throw new IllegalArgumentException("unknown shop entry kind \"" + id + "\"");
    }

    @Override
    public String toString() {
        return id;
    }
}
