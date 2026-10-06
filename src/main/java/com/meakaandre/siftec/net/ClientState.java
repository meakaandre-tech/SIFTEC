package com.meakaandre.siftec.net;

import java.util.HashSet;
import java.util.Set;

/** The client's copy of its company's progress. Null until the server has sent it. */
public final class ClientState {
    public static volatile Set<String> done;
    public static volatile int backpackSlots;
    /** The company's speed limit in RPM. */
    public static volatile int speedLimit = 32;

    private ClientState() {
    }

    public static void accept(StatePayload payload) {
        done = new HashSet<>(payload.done());
        backpackSlots = com.meakaandre.siftec.backpack.Backpack.unlocked(done);
        speedLimit = payload.speedCap();
    }
}
