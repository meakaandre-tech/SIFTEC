package com.meakaandre.siftec.hub;

import com.meakaandre.siftec.company.Company;
import net.minecraft.network.FriendlyByteBuf;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the HUB screen shows, made by the server for one player's company and sent to that player's open
 * HUB menu. The client only draws it; every action is checked again on the server.
 */
public final class HubView {
    public boolean gateway;
    /** The highest tier the HUB building is good for (-1 for none, {@link Milestones#TIERS} when the rule does not apply). */
    public int built = -1;
    /** True when the building rule is skipped (creative players, the Gateway). */
    public boolean exempt;
    /** Ticks until the HUB unlocks after the last milestone. */
    public long lock;
    /** What every cost is multiplied by: 50% more for each member after the first. */
    public float mult = 1f;
    public int members = 1;
    public String company = "";
    public String active = "";
    public Set<String> done = new LinkedHashSet<>();
    /** Parts delivered toward unfinished milestones: id -> count per cost line, in the milestone's order. */
    public Map<String, int[]> paid = new HashMap<>();
    // the HUB building, as last measured
    public boolean marked, inside, tooBig, tooLong, roof;
    public int walls, unloaded, buildMembers = 1;
    public int[] counts = new int[HubBuilding.FAMILIES.size()];

    /** Builds the view on the server. {@code building} is null when no area has been marked. */
    public static HubView of(Company company, boolean gateway, int built, boolean exempt, long lock, HubBuilding.Result building) {
        HubView v = new HubView();
        company.syncPaid();
        v.gateway = gateway;
        v.built = built;
        v.exempt = exempt;
        v.lock = Math.max(0, lock);
        v.mult = company.paidAt > 0 ? company.paidAt : company.costMultiplier();
        v.members = company.members.size();
        v.company = company.name;
        v.active = company.active;
        for (String id : company.done) {
            Milestone m = Milestones.get(id);
            if (m != null && !m.isResearch()) v.done.add(id);
        }
        for (String id : company.paid.keySet()) {
            Milestone m = Milestones.get(id);
            if (m == null || m.isResearch() || company.has(id)) continue;
            int[] row = new int[m.cost().size()];
            for (int i = 0; i < row.length; i++) row[i] = company.paid(m, m.cost().get(i));
            v.paid.put(id, row);
        }
        if (building != null) {
            v.marked = true;
            v.inside = building.hubInside;
            v.tooBig = building.tooBig;
            v.tooLong = building.tooLong;
            v.roof = building.roof;
            v.walls = building.walls;
            v.unloaded = building.unloaded;
            v.buildMembers = building.members;
            System.arraycopy(building.counts, 0, v.counts, 0, Math.min(v.counts.length, building.counts.length));
        }
        return v;
    }

    /** What a cost line asks for at the company's current size; the same sum as {@link Company#cost}. */
    public int need(Milestone.Cost cost) {
        return (int) Math.ceil(cost.count() * mult);
    }

    public int paid(Milestone m, int line) {
        if (done.contains(m.id())) return need(m.cost().get(line));
        int[] row = paid.get(m.id());
        return row == null || line >= row.length ? 0 : Math.min(row[line], need(m.cost().get(line)));
    }

    public boolean fullyPaid(Milestone m) {
        List<Milestone.Cost> cost = m.cost();
        for (int i = 0; i < cost.size(); i++) if (cost.get(i).present() && paid(m, i) < need(cost.get(i))) return false;
        return true;
    }

    /** A stand-in company holding only what {@link Milestones#blocker} and {@link Milestones#tierOpen} look at. */
    public Company asCompany() {
        Company c = new Company();
        c.done = new LinkedHashSet<>(done);
        c.active = active;
        c.name = company;
        return c;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(gateway);
        buf.writeVarInt(built + 1);
        buf.writeBoolean(exempt);
        buf.writeVarLong(lock);
        buf.writeFloat(mult);
        buf.writeVarInt(members);
        buf.writeUtf(company);
        buf.writeUtf(active);
        buf.writeVarInt(done.size());
        for (String id : done) buf.writeUtf(id);
        buf.writeVarInt(paid.size());
        for (Map.Entry<String, int[]> e : paid.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarIntArray(e.getValue());
        }
        buf.writeBoolean(marked);
        buf.writeBoolean(inside);
        buf.writeBoolean(tooBig);
        buf.writeBoolean(tooLong);
        buf.writeBoolean(roof);
        buf.writeVarInt(walls);
        buf.writeVarInt(unloaded);
        buf.writeVarInt(buildMembers);
        buf.writeVarIntArray(counts);
    }

    /** Lists coming from the network are capped, so a bad packet cannot ask for huge arrays. */
    private static final int MAX = 4096;

    public static HubView read(FriendlyByteBuf buf) {
        HubView v = new HubView();
        v.gateway = buf.readBoolean();
        v.built = buf.readVarInt() - 1;
        v.exempt = buf.readBoolean();
        v.lock = buf.readVarLong();
        v.mult = buf.readFloat();
        v.members = buf.readVarInt();
        v.company = buf.readUtf();
        v.active = buf.readUtf();
        int n = Math.min(MAX, buf.readVarInt());
        for (int i = 0; i < n; i++) v.done.add(buf.readUtf());
        n = Math.min(MAX, buf.readVarInt());
        for (int i = 0; i < n; i++) v.paid.put(buf.readUtf(), buf.readVarIntArray(64));
        v.marked = buf.readBoolean();
        v.inside = buf.readBoolean();
        v.tooBig = buf.readBoolean();
        v.tooLong = buf.readBoolean();
        v.roof = buf.readBoolean();
        v.walls = buf.readVarInt();
        v.unloaded = buf.readVarInt();
        v.buildMembers = buf.readVarInt();
        int[] counts = buf.readVarIntArray(64);
        System.arraycopy(counts, 0, v.counts, 0, Math.min(counts.length, v.counts.length));
        return v;
    }

    // ---- the building rule, as HubBuilding.Result has it

    public int required(int tier) {
        return HubBuilding.PER_TIER * (tier + 1) + HubBuilding.PER_MEMBER * Math.max(0, buildMembers - 1);
    }

    public int total(int tier) {
        int total = 0;
        for (int i = 0; i < counts.length; i++) if (HubBuilding.FAMILIES.get(i).fromTier() <= tier) total += counts[i];
        return total;
    }
}
