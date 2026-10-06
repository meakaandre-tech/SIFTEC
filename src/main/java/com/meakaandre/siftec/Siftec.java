package com.meakaandre.siftec;

import com.meakaandre.siftec.command.SiftecCommands;
import com.meakaandre.siftec.company.Companies;
import com.meakaandre.siftec.hub.Locks;
import com.meakaandre.siftec.hub.Milestones;
import com.meakaandre.siftec.node.NodePlacer;
import com.meakaandre.siftec.registry.ModBlockEntities;
import com.meakaandre.siftec.registry.ModBlocks;
import com.meakaandre.siftec.registry.ModItems;
import com.meakaandre.siftec.registry.ModTab;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** SIFTEC: Spatial Interlink and Factory Technology Engineering Corporation. */
public class Siftec implements ModInitializer {
    public static final String MOD_ID = "siftec";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Milestones.load();
        ModBlocks.register();
        ModBlockEntities.register();
        ModItems.register();
        ModTab.register();
        NodePlacer.register();
        SiftecCommands.register();
        Locks.register();
        Companies.register();
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
