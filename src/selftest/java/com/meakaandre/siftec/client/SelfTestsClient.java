package com.meakaandre.siftec.client;

import com.meakaandre.siftec.SelfTestHooks;
import com.meakaandre.siftec.client.map.MapClientTests;
import com.meakaandre.siftec.client.ponder.GuideClientTests;
import net.fabricmc.loader.api.FabricLoader;

/** The selftest mod's client side: the smoke run's remote controls and the checks that only the client can make. */
public final class SelfTestsClient implements SelfTestHooks.Plugin {
    @Override
    public void register(SelfTestHooks.Stage stage) {
        if (stage != SelfTestHooks.Stage.CLIENT) return;
        GuideClientTests.register();
        MapClientTests.register();
        BuildGunClientTests.register();
        ReadyClientTests.register();
        if (FabricLoader.getInstance().isModLoaded("travelersbackpack")) {
            CloudButtonTests.register();
            CreativeInventoryClientTests.register();
        }
    }
}
