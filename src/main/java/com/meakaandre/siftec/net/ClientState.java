package com.meakaandre.siftec.net;

import java.util.HashSet;
import java.util.Set;

/** The client's copy of its company's progress. Null until the server has sent it. */
public final class ClientState {
    public static volatile Set<String> done;
    /** False once the server has said the Jetpack is out of fuel. */
    public static volatile boolean jetFuel = true;

    private ClientState() {
    }

    public static void accept(StatePayload payload) {
        done = new HashSet<>(payload.done());
    }
}
