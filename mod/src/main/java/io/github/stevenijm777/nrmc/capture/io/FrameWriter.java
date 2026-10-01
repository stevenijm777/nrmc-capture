package io.github.stevenijm777.nrmc.capture.io;

import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes captured frames to PNG off the render thread. The metadata file is written last,
 * so its presence marks a complete capture (used to resume interrupted sweeps).
 */
public final class FrameWriter implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("nrmc-capture");

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "nrmc-frame-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger pending = new AtomicInteger();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

    /** Takes ownership of {@code image} and closes it. {@code meta} must not be touched afterwards. */
    public void submit(NativeImage image, Path png, Path metaFile, Map<String, Object> meta) {
        submit(image, png, metaFile, meta, null, null);
    }

    /** As above, plus an optional depth frame written as {@code depthFile} (.npz, array "depth"). */
    public void submit(NativeImage image, Path png, Path metaFile, Map<String, Object> meta,
                       DepthFrame depth, Path depthFile) {
        pending.incrementAndGet();
        executor.execute(() -> {
            try (image) {
                Files.createDirectories(png.getParent());
                Path tmp = png.resolveSibling(png.getFileName() + ".tmp");
                image.writeToFile(tmp);
                Files.move(tmp, png, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                meta.put("sha256", Hashing.sha256(png));
                if (depth != null) {
                    meta.put("depth", writeDepth(depth, depthFile));
                }
                Json.writeAtomic(metaFile, meta);
            } catch (Exception e) {
                LOG.error("Failed to write {}", png, e);
                errors.add(png + ": " + e);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private static Map<String, Object> writeDepth(DepthFrame depth, Path file) throws java.io.IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        NpyWriter.writeFloat32Npz(tmp, "depth", depth.height(), depth.width(), depth.values());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("file", file.getFileName().toString());
        info.put("sha256", Hashing.sha256(file));
        info.put("array", "depth");
        info.put("dtype", "float32");
        info.put("shape", List.of(depth.height(), depth.width()));
        info.put("origin", "top_left");
        info.put("convention", "OpenGL window-space depth in [0,1], 1 = far plane/sky; linearize with camera.projection_matrix");
        return info;
    }

    public int pending() {
        return pending.get();
    }

    public List<String> errors() {
        synchronized (errors) {
            return List.copyOf(errors);
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
