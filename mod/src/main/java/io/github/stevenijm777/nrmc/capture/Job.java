package io.github.stevenijm777.nrmc.capture;

import net.minecraft.client.Minecraft;

/** A long-running task advanced once per client tick, on the render thread. */
public interface Job {
    String name();

    void tick(Minecraft mc);

    boolean isDone();

    /** Requests a stop. The job may need further ticks to flush and restore state. */
    void cancel(String reason);

    String status();
}
