package com.meakaandre.siftec.mixin.steel;

import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a tag entry names, and whether that is a tag or an element ({@link TagStripMixin}). */
@Mixin(TagEntry.class)
public interface TagEntryAccessor {
    @Accessor("id")
    Identifier siftec$id();

    @Accessor("tag")
    boolean siftec$tag();
}
