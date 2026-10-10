package com.meakaandre.siftec.company;

import com.meakaandre.siftec.hub.Milestone;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A company owns all progression: milestones, wormhole phases and (later) research. Plain fields, because
 * the whole thing is saved as JSON.
 */
public class Company {
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public Set<String> members = new LinkedHashSet<>();
    public Set<String> invites = new LinkedHashSet<>();
    /** Ids of finished milestones, phases and research. */
    public Set<String> done = new LinkedHashSet<>();
    /** Parts already delivered toward unfinished milestones: milestone id -> item id -> count. */
    public Map<String, Map<String, Integer>> paid = new HashMap<>();
    /** The milestone that parts arriving by belt or funnel go to. */
    public String active = "";
    /** Overworld game time at which the HUB unlocks again. */
    public long lockUntil;
    /** The MAM node being researched, and the game time it finishes. Empty when nothing is running. */
    /** Slugs and artefacts already picked up, as "x,z". */
    public Set<String> collected = new LinkedHashSet<>();
    /** AWESOME Sink points. */
    public long points;
    /** The alternates a finished Hard Drive is offering; empty when there is nothing to choose. */
    public java.util.List<String> offer = new java.util.ArrayList<>();
    public String research = "";
    /** The MAM node that parts arriving at a MAM by belt or funnel go to; empty for none. */
    public String mamPick = "";
    public long researchEnd;
    /** The Dimensional Depot cloud: item id -> count. */
    public Map<String, Integer> cloud = new HashMap<>();
    /** The founder, who may kick members. Empty in older saves, where the first member counts as founder. */
    public String owner = "";
    /**
     * Players who left or were kicked, and the real time (ms) they went. For a while they still count toward
     * the cost multiplier and cannot join again, so leaving to pay less does not work.
     */
    public Map<String, Long> leftAt = new HashMap<>();
    /** The cost multiplier the parts in {@link #paid} were delivered at; they are scaled when it changes. 0 in older saves. */
    public float paidAt;

    public String owner() {
        if (!owner.isEmpty() && members.contains(owner)) return owner;
        return members.isEmpty() ? "" : members.iterator().next();
    }

    /** Players who left recently and are not back. */
    public int recentLeavers() {
        long now = System.currentTimeMillis(), cooldown = com.meakaandre.siftec.config.SiftecConfig.memberCooldownMillis();
        leftAt.values().removeIf(at -> now - at >= cooldown);
        int n = 0;
        for (String id : leftAt.keySet()) if (!members.contains(id)) n++;
        return n;
    }

    /** True if the player left this company too recently to join it again. */
    public boolean coolingDown(String playerId) {
        Long at = leftAt.get(playerId);
        return at != null && System.currentTimeMillis() - at < com.meakaandre.siftec.config.SiftecConfig.memberCooldownMillis();
    }

    /** Scales parts already delivered when the multiplier has changed, so a partial payment keeps its share. */
    public void syncPaid() {
        float now = costMultiplier();
        if (paidAt <= 0) {
            paidAt = now;
            return;
        }
        if (Math.abs(paidAt - now) < 1e-4) return;
        float ratio = now / paidAt;
        for (Map<String, Integer> parts : paid.values()) parts.replaceAll((item, count) -> Math.round(count * ratio));
        paidAt = now;
    }

    public boolean has(String id) {
        return done.contains(id);
    }

    /** Costs rise 50% for each member after the first; someone who left recently still counts. */
    public float costMultiplier() {
        return 1f + 0.5f * Math.max(0, members.size() + recentLeavers() - 1);
    }

    public int cost(Milestone.Cost cost) {
        syncPaid();
        return (int) Math.ceil(cost.count() * paidAt);
    }

    public int paid(Milestone m, Milestone.Cost cost) {
        Map<String, Integer> map = paid.get(m.id());
        return map == null ? 0 : Math.min(cost(cost), map.getOrDefault(cost.key(), 0));
    }

    public int needed(Milestone m, Milestone.Cost cost) {
        return cost(cost) - paid(m, cost);
    }

    public void pay(Milestone m, Milestone.Cost cost, int amount) {
        syncPaid();
        paid.computeIfAbsent(m.id(), k -> new HashMap<>()).merge(cost.key(), amount, Integer::sum);
    }

    public boolean fullyPaid(Milestone m) {
        for (Milestone.Cost cost : m.cost()) {
            if (cost.present() && needed(m, cost) > 0) return false;
        }
        return true;
    }

    /** How many finished milestones carry a token, such as "backpack". */
    public int count(String token) {
        // asked for by machines every tick, so the answers are kept until the company finishes something else
        if (countsFor != done.size()) {
            counts.clear();
            countsFor = done.size();
        }
        return counts.computeIfAbsent(token, this::countNow);
    }

    private transient Map<String, Integer> counts = new HashMap<>();
    private transient int countsFor = -1;

    private int countNow(String token) {
        int n = 0;
        for (String id : done) {
            Milestone m = com.meakaandre.siftec.hub.Milestones.get(id);
            if (m != null && m.tokens().contains(token)) n++;
        }
        return n;
    }

    /** True if a finished milestone carries the token, such as "scanner:copper". */
    public boolean hasToken(String token) {
        return count(token) > 0;
    }

    /** The highest value among tokens like "cap:96", or the fallback. */
    public int best(String prefix, int fallback) {
        int best = fallback;
        for (String id : done) {
            Milestone m = com.meakaandre.siftec.hub.Milestones.get(id);
            if (m == null) continue;
            for (String token : m.tokens()) {
                if (token.startsWith(prefix)) best = Math.max(best, Integer.parseInt(token.substring(prefix.length())));
            }
        }
        return best;
    }
}
