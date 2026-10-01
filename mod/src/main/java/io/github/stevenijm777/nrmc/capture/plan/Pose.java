package io.github.stevenijm777.nrmc.capture.plan;

/** One camera pose of a plan. Serialized as-is into the plan JSON. */
public final class Pose {
    public int index;
    public String sceneId;
    /**
     * Camera location this pose belongs to. In an all-times plan, consecutive poses share a
     * location and differ only in time of day; otherwise every pose is its own location.
     * -1 in plans made before mod 0.4.0.
     */
    public int location = -1;
    /** Feet position; the camera is 1.62 blocks above. */
    public double x;
    public double y;
    public double z;
    public float yaw;
    /** Positive looks down. */
    public float pitch;
    public int timeOfDay;
    public String weather = "clear";
    public String biome;
    public int surfaceY;
}
