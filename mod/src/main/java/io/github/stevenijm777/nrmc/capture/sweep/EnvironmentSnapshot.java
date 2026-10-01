package io.github.stevenijm777.nrmc.capture.sweep;

import com.mojang.blaze3d.platform.GlUtil;
import io.github.stevenijm777.nrmc.capture.NrmcCaptureMod;
import io.github.stevenijm777.nrmc.capture.io.Hashing;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Records everything that changes pixels besides the pose: versions, shader pack and its
 * options, and the client video options. Read from files so no Iris API is needed.
 */
public final class EnvironmentSnapshot {

    /** State from config/iris.properties. {@code enabled} is null when it cannot be read. */
    public record IrisState(Boolean enabled, String shaderPack) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("enable_shaders", enabled);
            map.put("shader_pack", shaderPack);
            return map;
        }
    }

    private EnvironmentSnapshot() {
    }

    public static IrisState readIris(Path gameDir) {
        Properties properties = readProperties(gameDir.resolve("config").resolve("iris.properties"));
        if (properties == null) {
            return new IrisState(null, null);
        }
        String enabled = properties.getProperty("enableShaders");
        String pack = properties.getProperty("shaderPack");
        return new IrisState(
                enabled == null ? null : Boolean.valueOf(enabled.trim()),
                pack == null || pack.isBlank() ? null : pack.trim());
    }

    /** Pack file hash plus the option overrides Iris stores next to it as {@code <pack>.txt}. */
    public static Map<String, Object> shaderPack(Path gameDir, IrisState iris) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (iris.shaderPack() == null) {
            return out;
        }
        Path packs = gameDir.resolve("shaderpacks");
        Path pack = packs.resolve(iris.shaderPack());
        out.put("id", iris.shaderPack());
        String hash = null;
        if (Files.isRegularFile(pack)) {
            try {
                hash = Hashing.sha256(pack);
            } catch (IOException e) {
                NrmcCaptureMod.LOG.warn("Could not hash shader pack {}", pack, e);
            }
        }
        out.put("sha256", hash);
        Properties options = readProperties(packs.resolve(iris.shaderPack() + ".txt"));
        Map<String, String> sorted = new TreeMap<>();
        if (options != null) {
            options.stringPropertyNames().forEach(key -> sorted.put(key, options.getProperty(key)));
        }
        out.put("options", sorted);
        return out;
    }

    /** Raw key/value pairs from options.txt (FOV, render distance, mipmaps, biome blend, ...). */
    public static Map<String, String> clientOptions(Path gameDir) {
        Map<String, String> out = new TreeMap<>();
        Path file = gameDir.resolve("options.txt");
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int split = line.indexOf(':');
                if (split > 0) {
                    out.put(line.substring(0, split), line.substring(split + 1));
                }
            }
        } catch (IOException e) {
            NrmcCaptureMod.LOG.warn("Could not read {}", file, e);
        }
        return out;
    }

    /** Must run on the render thread (queries OpenGL strings). */
    public static Map<String, Object> environment() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("minecraft_version", version("minecraft"));
        env.put("loader", Map.of("name", "fabric", "version", version("fabricloader")));
        env.put("capture_mod_version", version(NrmcCaptureMod.MOD_ID));
        env.put("mods", topLevelMods());
        env.put("gpu", GlUtil.getRenderer());
        env.put("gpu_vendor", GlUtil.getVendor());
        env.put("opengl", GlUtil.getOpenGLVersion());
        env.put("java", System.getProperty("java.version"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        return env;
    }

    private static List<Map<String, String>> topLevelMods() {
        return FabricLoader.getInstance().getAllMods().stream()
                .filter(mod -> mod.getContainingMod().isEmpty())
                .map(EnvironmentSnapshot::describe)
                .sorted(Comparator.comparing((Map<String, String> m) -> m.get("id")))
                .toList();
    }

    private static Map<String, String> describe(ModContainer mod) {
        return Map.of(
                "id", mod.getMetadata().getId(),
                "version", mod.getMetadata().getVersion().getFriendlyString());
    }

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static Properties readProperties(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException e) {
            NrmcCaptureMod.LOG.warn("Could not read {}", file, e);
            return null;
        }
    }
}
