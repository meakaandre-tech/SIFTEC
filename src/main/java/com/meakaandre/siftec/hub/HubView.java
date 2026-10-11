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
    /** Ticks until the freighter is back after the last milestone. */
    public long lock;
    /** What every cost is multiplied by: 50% more for each member after the first. */
    public float mult = 1f;
    public int members = 1;
    public String company = "";
    public String active = "";
    public Set<String> done = new LinkedHashSet<>();
    /** Parts delivered toward unfinished milestones: id -> count per cost line, in the milestone's order. */
    public Map<String, int[]> paid = new HashMap<>();

    /** Builds the view on the server. */
    public static HubView of(Company company, boolean gateway, long lock) {
        HubView v = new HubView();
        company.syncPaid();
        v.gateway = gateway;
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
    }

    /** Lists coming from the network are capped, so a bad packet cannot ask for huge arrays. */
    private static final int MAX = 4096;

    public static HubView read(FriendlyByteBuf buf) {
        HubView v = new HubView();
        v.gateway = buf.readBoolean();
        v.lock = buf.readVarLong();
        v.mult = buf.readFloat();
        v.members = buf.readVarInt();
        v.company = buf.readUtf();
        v.active = buf.readUtf();
        int n = Math.min(MAX, buf.readVarInt());
        for (int i = 0; i < n; i++) v.done.add(buf.readUtf());
        n = Math.min(MAX, buf.readVarInt());
        for (int i = 0; i < n; i++) v.paid.put(buf.readUtf(), buf.readVarIntArray(64));
        return v;
    }

}
