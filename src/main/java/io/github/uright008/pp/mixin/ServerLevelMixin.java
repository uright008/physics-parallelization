package io.github.uright008.pp.mixin;

import io.github.uright008.pc.EntityGrid;
import io.github.uright008.pc.EntityGridManager;
import io.github.uright008.pc.ParallelThreadPool;
import io.github.uright008.pc.SafeLevelAccess;
import io.github.uright008.pp.PhysicsParallelConfig;
import io.github.uright008.pp.PhysicsParallelization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Redirect(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"
            )
    )
    private void physicsParallel$redirectEntityTick(EntityTickList tickList, Consumer<Entity> entityConsumer) {
        if (!PhysicsParallelConfig.isEnabled()) {
            tickList.forEach(entityConsumer);
            return;
        }
        physicsParallel$tickEntitiesParallel(tickList, entityConsumer);
    }

    private void physicsParallel$tickEntitiesParallel(EntityTickList tickList, Consumer<Entity> entityConsumer) {
        List<Entity> allEntities = new ArrayList<>();
        List<Entity> heavyEntities = new ArrayList<>();
        boolean lightParallel = PhysicsParallelConfig.isLightEntitiesParallel();

        tickList.forEach(entity -> {
            if (!entity.isRemoved()) {
                allEntities.add(entity);
                if (needsParallelTick(entity, lightParallel)) {
                    heavyEntities.add(entity);
                }
            }
        });

        int heavyCount = heavyEntities.size();
        if (heavyCount < PhysicsParallelConfig.getMinParallelEntities()) {
            for (Entity entity : allEntities) {
                entityConsumer.accept(entity);
            }
            return;
        }

        EntityGrid grid = new EntityGrid(allEntities);
        SafeLevelAccess.enterSafeZone();
        EntityGridManager.setActiveGrid(grid);
        try {
            // Phase 1: tick light entities on main thread (grid provides consistent snapshot)
            for (Entity entity : allEntities) {
                if (!needsParallelTick(entity, lightParallel)) {
                    entityConsumer.accept(entity);
                }
            }

            // Phase 2: parallel tick heavy entities (grid provides consistent snapshot)
            ExecutorService pool = ParallelThreadPool.getPool();
            int workers = Math.max(1, Math.min(heavyCount, ParallelThreadPool.getParallelism()));
            int batchSize = (heavyCount + workers - 1) / workers;

            List<CompletableFuture<Void>> futures = new ArrayList<>(workers);
            for (int w = 0; w < workers; w++) {
                final int from = w * batchSize;
                final int to = Math.min(from + batchSize, heavyCount);
                if (from >= to) break;

                futures.add(CompletableFuture.runAsync(() -> {
                    for (int i = from; i < to; i++) {
                        try {
                            entityConsumer.accept(heavyEntities.get(i));
                        } catch (Exception e) {
                            PhysicsParallelization.LOGGER.error(
                                    "Error ticking entity {} in parallel worker",
                                    heavyEntities.get(i), e);
                        }
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            EntityGridManager.setActiveGrid(null);
            SafeLevelAccess.leaveSafeZone();
        }
    }

    private static boolean needsParallelTick(Entity entity, boolean lightParallel) {
        if (lightParallel) return true;
        return entity instanceof Mob;
    }
}
