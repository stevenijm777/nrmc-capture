package io.github.stevenijm777.nrmc.capture.sweep;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Resizes the game window so every capture has the same framebuffer size. */
public final class WindowSize {
    private WindowSize() {
    }

    /** Returns null on success, or the reason the resize was not attempted. */
    public static String request(Minecraft mc, int width, int height) {
        Window window = mc.getWindow();
        if (window.isFullscreen()) {
            return "fullscreen is on; switch to windowed mode (F11)";
        }
        long handle = window.getWindow();
        if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) {
            GLFW.glfwRestoreWindow(handle);
        }
        GLFW.glfwSetWindowSize(handle, width, height);
        return null;
    }

    public static boolean matches(Minecraft mc, int width, int height) {
        return mc.getMainRenderTarget().width == width && mc.getMainRenderTarget().height == height;
    }
}
