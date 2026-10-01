package io.github.stevenijm777.nrmc.capture.sweep;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.stevenijm777.nrmc.capture.io.DepthFrame;
import java.nio.FloatBuffer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Hooks the end of world rendering (before the GUI pass clears the depth buffer) to:
 * count rendered frames, record the camera matrices of the last frame, and read the depth
 * buffer when a sweep requests it. Everything here runs on the render thread.
 */
public final class RenderHooks {

    /** Matrices are column-major (JOML layout). {@code view} is rotation only; see captureMeta. */
    public record CameraSnapshot(float[] projection, float[] view, double[] position, long frame) {
    }

    private static volatile long frames;
    private static volatile boolean seen;
    private static CameraSnapshot lastCamera;
    private static boolean depthRequested;
    private static DepthFrame depthResult;
    private static String depthError;

    private RenderHooks() {
    }

    public static void register() {
        WorldRenderEvents.END.register(RenderHooks::onWorldRenderEnd);
    }

    private static void onWorldRenderEnd(WorldRenderContext context) {
        frames++;
        seen = true;
        float[] projection = new float[16];
        float[] view = new float[16];
        context.projectionMatrix().get(projection);
        context.positionMatrix().get(view);
        Vec3 position = context.camera().getPosition();
        lastCamera = new CameraSnapshot(projection, view, new double[] {position.x, position.y, position.z}, frames);

        if (depthRequested) {
            depthRequested = false;
            try {
                depthResult = readDepth(Minecraft.getInstance().getMainRenderTarget());
            } catch (RuntimeException e) {
                depthError = e.toString();
            }
        }
    }

    private static DepthFrame readDepth(RenderTarget target) {
        if (!target.useDepth) {
            throw new IllegalStateException("main render target has no depth attachment");
        }
        int width = target.width;
        int height = target.height;
        FloatBuffer buffer = MemoryUtil.memAllocFloat(width * height);
        try {
            RenderSystem.bindTexture(target.getDepthTextureId());
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buffer);
            float[] values = new float[width * height];
            // OpenGL rows start at the bottom; flip so row 0 is the top, like the PNG.
            for (int y = 0; y < height; y++) {
                buffer.get((height - 1 - y) * width, values, y * width, width);
            }
            return new DepthFrame(width, height, values);
        } finally {
            RenderSystem.bindTexture(0);
            MemoryUtil.memFree(buffer);
        }
    }

    /** Reads the depth buffer at the end of the next rendered world frame. */
    public static void requestDepth() {
        depthRequested = true;
        depthResult = null;
        depthError = null;
    }

    public static void cancelDepth() {
        depthRequested = false;
        depthResult = null;
        depthError = null;
    }

    /** Returns the requested depth frame once, or null if it has not been read yet. */
    public static DepthFrame takeDepth() {
        DepthFrame result = depthResult;
        depthResult = null;
        return result;
    }

    public static String depthError() {
        return depthError;
    }

    public static CameraSnapshot lastCamera() {
        return lastCamera;
    }

    public static long frames() {
        return frames;
    }

    /** False if the event never fired (e.g. a renderer that skips it); sweeps then gate on ticks only. */
    public static boolean seen() {
        return seen;
    }
}
