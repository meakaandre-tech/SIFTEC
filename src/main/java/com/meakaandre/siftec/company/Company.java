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
    public String research = "";
    public long researchEnd;
    /** The Dimensional Depot cloud: item id -> count. */
    public Map<String, Integer> cloud = new HashMap<>();

    public boolean has(String id) {
        return done.contains(id);
    }

    /** Costs rise 50% for each member after the first. */
    public float costMultiplier() {
        return 1f + 0.5f * Math.max(0, members.size() - 1);
    }

    public int cost(Milestone.Cost cost) {
        return (int) Math.ceil(cost.count() * costMultiplier());
    }

    public int paid(Milestone m, Milestone.Cost cost) {
        Map<String, Integer> map = paid.get(m.id());
        return map == null ? 0 : Math.min(cost(cost), map.getOrDefault(cost.key(), 0));
    }

    public int needed(Milestone m, Milestone.Cost cost) {
        return cost(cost) - paid(m, cost);
    }

    public void pay(Milestone m, Milestone.Cost cost, int amount) {
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
