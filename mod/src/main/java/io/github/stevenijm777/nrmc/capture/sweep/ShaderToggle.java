package io.github.stevenijm777.nrmc.capture.sweep;

import io.github.stevenijm777.nrmc.capture.Job;
import io.github.stevenijm777.nrmc.capture.NrmcCaptureMod;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;

/**
 * Turns Iris shaders on or off through Iris' public API ({@code IrisApi.getConfig()
 * .setShadersEnabledAndApply}), then waits until the change is visible both in the API and in
 * config/iris.properties (which the sweep checks) and some frames have been rendered with the new
 * pipeline. Called by reflection so the mod still builds and loads without Iris.
 */
public final class ShaderToggle implements Job {
    private static final int MIN_WAIT_TICKS = 100;
    private static final int MIN_FRAMES = 60;
    private static final int TIMEOUT_TICKS = 2400;

    private final boolean enable;
    private int ticks;
    private long startFrames;
    private boolean requested;
    private boolean done;

    public ShaderToggle(boolean enable) {
        this.enable = enable;
    }

    @Override
    public String name() {
        return "shaders " + (enable ? "on" : "off");
    }

    @Override
    public void tick(Minecraft mc) {
        if (done) {
            return;
        }
        if (!requested) {
            requested = true;
            startFrames = RenderHooks.frames();
            try {
                Object config = irisConfig();
                boolean current = (boolean) configMethod("areShadersEnabled").invoke(config);
                if (current != enable) {
                    configMethod("setShadersEnabledAndApply", boolean.class).invoke(config, enable);
                }
                NrmcCaptureMod.notify("Shaders " + (enable ? "ON" : "OFF") + (current == enable ? " (already)" : ""));
            } catch (ReflectiveOperationException | RuntimeException e) {
                finish("could not switch shaders through the Iris API: " + e);
            }
            return;
        }
        ticks++;
        boolean apiOk;
        try {
            Object config = irisConfig();
            apiOk = (boolean) configMethod("areShadersEnabled").invoke(config) == enable;
        } catch (ReflectiveOperationException | RuntimeException e) {
            apiOk = false;
        }
        Boolean file = EnvironmentSnapshot.readIris(NrmcCaptureMod.gameDir()).enabled();
        boolean fileOk = file != null && file == enable;
        long frames = RenderHooks.frames() - startFrames;
        if (apiOk && fileOk && ticks >= MIN_WAIT_TICKS && frames >= MIN_FRAMES) {
            done = true;
        } else if (ticks > TIMEOUT_TICKS) {
            finish("timed out (api " + apiOk + ", iris.properties " + file + ", frames " + frames + ")");
        }
    }

    /** Looked up on the public interface: the implementing class may not be public. */
    private static Method configMethod(String name, Class<?>... params) throws ReflectiveOperationException {
        return Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig").getMethod(name, params);
    }

    private static Object irisConfig() throws ReflectiveOperationException {
        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
        Object instance = api.getMethod("getInstance").invoke(null);
        return api.getMethod("getConfig").invoke(instance);
    }

    private void finish(String problem) {
        done = true;
        NrmcCaptureMod.notify("Shader switch failed: " + problem
                + ". The next sweep will abort if the state is wrong.");
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void cancel(String reason) {
        done = true;
    }

    @Override
    public String status() {
        return name() + ": waiting " + ticks + " ticks";
    }
}
