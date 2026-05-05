package io.github.uright008.pp.mixin;

import io.github.uright008.pc.EntityGrid;
import io.github.uright008.pc.EntityGridManager;
import io.github.uright008.pc.ParallelThreadPool;
import io.github.uright008.pc.SafeLevelAccess;
import io.github.uright008.pp.PhysicsParallelConfig;
import io.github.uright008.pp.PhysicsParallelization;
import net.minecraft.core.SectionPos;
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
    private void redirectEntityTick(EntityTickList tickList, Consumer<Entity> entityConsumer) {
        if (!PhysicsParallelConfig.isEnabled()) {
            tickList.forEach(entityConsumer);
            return;
        }
        parallelTickEntities(tickList, entityConsumer);
    }

    private void parallelTickEntities(EntityTickList tickList, Consumer<Entity> entityConsumer) {
        List<Entity> allEntities = new ArrayList<>();
        List<Entity> mobEntities = new ArrayList<>();

        tickList.forEach(entity -> {
            if (!entity.isRemoved()) {
                allEntities.add(entity);
                if (entity instanceof Mob) {
                    mobEntities.add(entity);
                }
            }
        });

        int mobCount = mobEntities.size();
        if (mobCount < PhysicsParallelConfig.getMinParallelEntities()) {
            allEntities.forEach(entityConsumer);
            return;
        }

        ServerLevel level = (ServerLevel) (Object) this;

        for (Entity e : allEntities) {
            int cx = SectionPos.blockToSectionCoord(e.getBlockX());
            int cz = SectionPos.blockToSectionCoord(e.getBlockZ());
            level.getChunk(cx, cz);
        }

        EntityGrid grid = new EntityGrid(allEntities);

        SafeLevelAccess.enterSafeZone();
        EntityGridManager.setActiveGrid(grid);
        try {
            for (Entity entity : allEntities) {
                if (!(entity instanceof Mob)) {
                    entityConsumer.accept(entity);
                }
            }

            ExecutorService pool = ParallelThreadPool.getPool("Physics");
            int workers = Math.max(1, Math.min(mobCount, ParallelThreadPool.getParallelism()));
            int batchSize = (mobCount + workers - 1) / workers;

            List<CompletableFuture<Void>> futures = new ArrayList<>(workers);

            for (int w = 0; w < workers - 1; w++) {
                final int from = w * batchSize;
                final int to = Math.min(from + batchSize, mobCount);
                if (from >= to) break;

                futures.add(CompletableFuture.runAsync(() -> {
                    SafeLevelAccess.enterSafeZone();
                    try {
                        for (int i = from; i < to; i++) {
                            Entity entity = mobEntities.get(i);
                            try {
                                entityConsumer.accept(entity);
                            } catch (Exception e) {
                                PhysicsParallelization.LOGGER.error(
                                        "Error ticking entity {} in parallel worker",
                                        entity, e);
                            }
                        }
                    } finally {
                        SafeLevelAccess.leaveSafeZone();
                    }
                }, pool));
            }

            int mainFrom = (workers - 1) * batchSize;
            int mainTo = Math.min(mainFrom + batchSize, mobCount);
            for (int i = mainFrom; i < mainTo; i++) {
                entityConsumer.accept(mobEntities.get(i));
            }

            for (CompletableFuture<Void> f : futures) {
                f.join();
            }
        } finally {
            EntityGridManager.setActiveGrid(null);
            SafeLevelAccess.leaveSafeZone();
        }
    }
}
