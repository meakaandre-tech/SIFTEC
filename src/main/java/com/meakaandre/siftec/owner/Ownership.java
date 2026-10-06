package com.meakaandre.siftec.owner;

import com.meakaandre.siftec.Siftec;
import com.meakaandre.siftec.claim.Claims;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.company.Company;
import com.meakaandre.siftec.company.CompanyData;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Which company a machine belongs to. A machine remembers the company of the player who placed it; one that
 * nobody placed by hand (a Deployer's or a contraption's work) belongs to the company whose claim it stands in.
 */
public final class Ownership {
    /** The owning company's id, saved with the block. */
    public static final AttachmentType<String> OWNER = AttachmentRegistry.create(Siftec.id("owner"), builder -> builder.persistent(Codec.STRING));
    /** The player whose click is being carried out right now, so whatever it creates can be marked theirs. */
    private static final ThreadLocal<ServerPlayer> ACTING = new ThreadLocal<>();
    /** The machine whose tick is running right now, so a recipe lookup knows who is asking. */
    private static final ThreadLocal<BlockEntity> TICKING = new ThreadLocal<>();

    private Ownership() {
    }

    public static void register() {
    }

    public static void acting(@Nullable ServerPlayer player) {
        ACTING.set(player);
    }

    /** Called for every block entity as it joins the world. */
    public static void stamp(BlockEntity be) {
        ServerPlayer player = ACTING.get();
        if (player == null || be.hasAttached(OWNER)) return;
        be.setAttached(OWNER, Companies.of(player).id);
    }

    public static @Nullable BlockEntity ticking() {
        return TICKING.get();
    }

    public static void ticking(@Nullable BlockEntity be) {
        TICKING.set(be != null && be.getLevel() instanceof ServerLevel ? be : null);
    }

    public static @Nullable Company of(BlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) return null;
        CompanyData data = CompanyData.get(level.getServer());
        String id = be.getAttached(OWNER);
        Company company = id == null ? null : data.byId(id);
        if (company == null) {
            Claims.Claim claim = Claims.at(level, be.getBlockPos());
            if (claim != null) company = data.byId(claim.company);
        }
        return company;
    }
}
