package io.github.uright008.pp;

import com.google.gson.JsonObject;
import io.github.uright008.pc.ParallelConfig;

public final class PhysicsParallelConfig extends ParallelConfig {

    private static final PhysicsParallelConfig INSTANCE = new PhysicsParallelConfig();

    private volatile boolean enabled;
    private volatile int minParallelEntities;
    private volatile boolean lightEntitiesParallel;

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
        if (json.has("minParallelEntities")) minParallelEntities = json.get("minParallelEntities").getAsInt();
        else minParallelEntities = 4;
        if (json.has("lightEntitiesParallel")) lightEntitiesParallel = json.get("lightEntitiesParallel").getAsBoolean();
        else lightEntitiesParallel = true;
        logger().info("Parallel entity physics: {}  |  minEntities: {}  |  lightEnt: {}",
                enabled ? "ON" : "OFF", minParallelEntities, lightEntitiesParallel ? "parallel" : "main");
    }

    @Override
    protected JsonObject write() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("minParallelEntities", minParallelEntities);
        json.addProperty("lightEntitiesParallel", lightEntitiesParallel);
        return json;
    }

    @Override
    protected void applyDefaults() {
        enabled = true;
        minParallelEntities = 4;
        lightEntitiesParallel = true;
        save();
    }

    public static boolean isEnabled() { return INSTANCE.enabled; }
    public static void setEnabled(boolean v) { INSTANCE.enabled = v; INSTANCE.save(); }

    public static int getMinParallelEntities() { return INSTANCE.minParallelEntities; }
    public static void setMinParallelEntities(int v) { INSTANCE.minParallelEntities = Math.max(1, v); INSTANCE.save(); }

    public static boolean isLightEntitiesParallel() { return INSTANCE.lightEntitiesParallel; }
    public static void setLightEntitiesParallel(boolean v) { INSTANCE.lightEntitiesParallel = v; INSTANCE.save(); }

    public static void reloadConfig() { INSTANCE.reload(); }
    public static void ensureConfigSaved() { INSTANCE.save(); }
}
