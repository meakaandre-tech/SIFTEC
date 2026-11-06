package com.meakaandre.siftec.mixin.steel;

import com.meakaandre.siftec.compat.Replaced;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every tag, of every mod, loses what SIFTEC replaces ({@link Replaced}): Gunsmithing's steel leaves the common steel
 * tags (c:ingots/steel and the rest), so a recipe that takes steel by its tag takes only SIFTEC's, and Create's
 * industrial iron leaves its tags. Done here rather than in a tag file, which could only take an entry out of a tag that
 * loads before it.
 */
@Mixin(TagLoader.class)
public abstract class TagStripMixin {
    @ModifyVariable(method = "build", at = @At("HEAD"), argsOnly = true)
    private Map<Identifier, List<TagLoader.EntryWithSource>> siftec$strip(Map<Identifier, List<TagLoader.EntryWithSource>> builders) {
        if (Replaced.table().isEmpty()) return builders;
        Map<Identifier, List<TagLoader.EntryWithSource>> out = new HashMap<>(builders.size());
        for (Map.Entry<Identifier, List<TagLoader.EntryWithSource>> e : builders.entrySet()) {
            List<TagLoader.EntryWithSource> kept = new ArrayList<>(e.getValue().size());
            for (TagLoader.EntryWithSource entry : e.getValue()) {
                TagEntryAccessor tagEntry = (TagEntryAccessor) (Object) entry.entry();
                if (!tagEntry.siftec$tag() && Replaced.replaced(tagEntry.siftec$id())) continue;
                kept.add(entry);
            }
            out.put(e.getKey(), kept);
        }
        return out;
    }
}
