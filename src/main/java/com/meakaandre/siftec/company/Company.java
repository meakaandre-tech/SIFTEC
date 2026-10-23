package com.meakaandre.siftec.company;

import com.meakaandre.siftec.config.SiftecConfig;
import com.meakaandre.siftec.hub.Milestone;
import com.meakaandre.siftec.hub.Milestones;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
    /** Overworld game time at which the HUB's freighter is back and the next milestone can be paid. */
    public long lockUntil;
    /** The company's Wormhole Gateway structure, as "dimension|packed position" of its terminal; empty when it has none. */
    public String gateway = "";
    /** Mercer Spheres, Somersloops and crash sites already collected, as "x,z". */
    public Set<String> collected = new LinkedHashSet<>();
    /** Crash sites a Radar Tower has already shown the company, as "x,z". */
    public Set<String> revealed = new LinkedHashSet<>();
    /** MAM trees the company has started (by collecting what starts them, or bringing it to the MAM): tree ids. */
    public Set<String> trees = new LinkedHashSet<>();
    /** AWESOME Sink points not yet printed as coupons. */
    public long points;
    /** Every point the AWESOME Sink has paid out, for the Sink screen. */
    public long pointsEver;
    /** FICSIT Coupons printed so far (the next one costs more, on Satisfactory's curve). */
    public int couponsPrinted;
    /** What the company has bought in the AWESOME Shop: entry ids (a swatch slot counts in {@link #swatches}). */
    public Set<String> shop = new LinkedHashSet<>();
    /** The company's banner (the AWESOME Shop's banner designer): the banner item, as JSON; empty for none. */
    public String banner = "";
    /**
     * The company's colour swatches, each "primary/secondary" dye names ("industrial" for the dark grey panels as
     * built). The first is the default every company has; the AWESOME Shop sells more slots.
     */
    public List<String> swatches = new ArrayList<>(List.of("orange/industrial"));
    /** The alternates a finished Hard Drive is offering; empty when there is nothing to choose. */
    public List<String> offer = new ArrayList<>();
    /** The MAM node being researched, and the game time it finishes. Empty when nothing is running. */
    public String research = "";
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
        long now = System.currentTimeMillis(), cooldown = SiftecConfig.memberCooldownMillis();
        leftAt.values().removeIf(at -> now - at >= cooldown);
        int n = 0;
        for (String id : leftAt.keySet()) if (!members.contains(id)) n++;
        return n;
    }

    /** True if the player left this company too recently to join it again. */
    public boolean coolingDown(String playerId) {
        Long at = leftAt.get(playerId);
        return at != null && System.currentTimeMillis() - at < SiftecConfig.memberCooldownMillis();
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
        paid.forEach((id, parts) -> {
            // a MAM node costs the same whatever the company's size: its parts are never scaled
            Milestone m = Milestones.get(id);
            if (m == null || !m.isResearch()) parts.replaceAll((item, count) -> Math.round(count * ratio));
        });
        paidAt = now;
    }

    public boolean has(String id) {
        return done.contains(id);
    }

    /**
     * Milestone and Wormhole phase costs rise 50% for each member after the first; someone who left recently still
     * counts. MAM nodes are not affected ({@link #cost(Milestone, Milestone.Cost)}).
     */
    public float costMultiplier() {
        return 1f + 0.5f * Math.max(0, members.size() + recentLeavers() - 1);
    }

    /** What a milestone or Wormhole phase cost line asks of the company at its size. */
    public int cost(Milestone.Cost cost) {
        syncPaid();
        return (int) Math.ceil(cost.count() * paidAt);
    }

    /** What a cost line of this milestone, phase or MAM node asks of the company: a MAM node's never grows with the members. */
    public int cost(Milestone m, Milestone.Cost cost) {
        return m.isResearch() ? cost.count() : cost(cost);
    }

    public int paid(Milestone m, Milestone.Cost cost) {
        Map<String, Integer> map = paid.get(m.id());
        return map == null ? 0 : Math.min(cost(m, cost), map.getOrDefault(cost.key(), 0));
    }

    public int needed(Milestone m, Milestone.Cost cost) {
        return cost(m, cost) - paid(m, cost);
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

    /** How many finished milestones carry a token, such as "scanner:copper". */
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
            Milestone m = Milestones.get(id);
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
            Milestone m = Milestones.get(id);
            if (m == null) continue;
            for (String token : m.tokens()) {
                if (token.startsWith(prefix)) best = Math.max(best, Integer.parseInt(token.substring(prefix.length())));
            }
        }
        return best;
    }
}
