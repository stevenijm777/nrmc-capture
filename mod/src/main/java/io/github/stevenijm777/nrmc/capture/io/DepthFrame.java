package io.github.stevenijm777.nrmc.capture.io;

/**
 * Raw depth-buffer values in [0, 1] (OpenGL window-space depth, 1 = far plane / sky).
 * Row-major with row 0 at the top, so it lines up with the PNG of the same frame.
 */
public record DepthFrame(int width, int height, float[] values) {
}
