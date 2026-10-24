package com.meakaandre.siftec;

import com.meakaandre.siftec.blueprint.DesignerTests;
import com.meakaandre.siftec.building.BuildingTests;
import com.meakaandre.siftec.building.BuildingsATests;
import com.meakaandre.siftec.building.BuildingsBTests;
import com.meakaandre.siftec.building.ExploitTests;
import com.meakaandre.siftec.building.MachineTests;
import com.meakaandre.siftec.claim.StateSelfTest;
import com.meakaandre.siftec.command.AutomateTests;
import com.meakaandre.siftec.command.BackpackFixTests;
import com.meakaandre.siftec.command.BuildingFixTests;
import com.meakaandre.siftec.command.CoreTests;
import com.meakaandre.siftec.command.DataFixTests;
import com.meakaandre.siftec.command.ExplorationTests;
import com.meakaandre.siftec.command.FoodTests;
import com.meakaandre.siftec.command.LogisticsTests;
import com.meakaandre.siftec.command.NodeTests;
import com.meakaandre.siftec.command.OwnershipFixTests;
import com.meakaandre.siftec.command.PolishTests;
import com.meakaandre.siftec.command.PowerFixTests;
import com.meakaandre.siftec.command.ProgressionTests;
import com.meakaandre.siftec.command.QualityFixTests;
import com.meakaandre.siftec.command.ReviewFixTests;
import com.meakaandre.siftec.command.WorldTests;
import com.meakaandre.siftec.compat.TravelersBackpackSelfTest;
import com.meakaandre.siftec.guide.GuideTests;
import com.meakaandre.siftec.map.MapTests;
import com.meakaandre.siftec.power.PowerTests;
import com.meakaandre.siftec.prefab.PrefabTests;
import com.meakaandre.siftec.sim.AlwaysTests;
import com.meakaandre.siftec.vehicle.VehicleTests;

/** The selftest mod's server side: registers every {@code /siftec selftest} and {@code worldtest} suite at its stage. */
public final class SelfTests implements SelfTestHooks.Plugin {
    @Override
    public void register(SelfTestHooks.Stage stage) {
        switch (stage) {
            case COMMANDS -> {
                WorldTests.register();
                PrefabTests.register();
                BuildingTests.register();
                BuildingsATests.register();
                BuildingsBTests.register();
                AutomateTests.register();
                PowerTests.register();
                NodeTests.register();
                LogisticsTests.register();
                ProgressionTests.register();
                ExplorationTests.register();
                FoodTests.register();
                VehicleTests.register();
                MapTests.register();
                CoreTests.register();
                SmokeWaits.register();
            }
            case CLAIMS -> StateSelfTest.register();
            case BACKPACK -> TravelersBackpackSelfTest.register();
            case CONTENT -> {
                GuideTests.register();
                ReviewFixTests.register();
                BackpackFixTests.register();
                OwnershipFixTests.register();
                DataFixTests.register();
                PowerFixTests.register();
                BuildingFixTests.register();
                MachineTests.register();
                ExploitTests.register();
                DesignerTests.register();
                QualityFixTests.register();
            }
            case SIMULATION -> AlwaysTests.register();
            case LATE -> PolishTests.register();
            case CLIENT -> {
            }
        }
    }
}
