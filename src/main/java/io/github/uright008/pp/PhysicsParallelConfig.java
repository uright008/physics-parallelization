package io.github.uright008.pp;

import com.google.gson.JsonObject;
import io.github.uright008.pc.ParallelConfig;

public final class PhysicsParallelConfig extends ParallelConfig {

    private static final PhysicsParallelConfig INSTANCE = new PhysicsParallelConfig();

    private volatile boolean enabled;

    private PhysicsParallelConfig() {
        super("physics-parallelization.json");
        reload();
    }

    public static PhysicsParallelConfig instance() {
        return INSTANCE;
    }

    @Override
    protected void read(JsonObject json) {
        if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
        logger().info("Parallel entity physics: {}", enabled ? "ON" : "OFF");
    }

    @Override
    protected JsonObject write() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        return json;
    }

    @Override
    protected void applyDefaults() {
        enabled = true;
        save();
    }

    public static boolean isEnabled() { return INSTANCE.enabled; }
    public static void setEnabled(boolean v) { INSTANCE.enabled = v; INSTANCE.save(); }

    public static void reloadConfig() { INSTANCE.reload(); }
    public static void ensureConfigSaved() { INSTANCE.save(); }
}
