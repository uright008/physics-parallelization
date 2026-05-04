package io.github.uright008.pp.mixin;

import io.github.uright008.pc.ChunkEntityGrouper;
import io.github.uright008.pc.ParallelThreadPool;
import io.github.uright008.pc.SafeLevelAccess;
import io.github.uright008.pp.PhysicsParallelConfig;
import io.github.uright008.pp.PhysicsParallelization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
        List<Entity> snapshot = new ArrayList<>();
        tickList.forEach(snapshot::add);

        if (snapshot.isEmpty()) {
            return;
        }

        Map<ChunkPos, List<Entity>> chunkGroups = ChunkEntityGrouper.groupByChunk(snapshot);

        SafeLevelAccess.enterSafeZone();
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (List<Entity> chunkEntities : chunkGroups.values()) {
                if (chunkEntities.isEmpty()) continue;
                futures.add(CompletableFuture.runAsync(() -> {
                    for (Entity entity : chunkEntities) {
                        try {
                            entityConsumer.accept(entity);
                        } catch (Exception e) {
                            PhysicsParallelization.LOGGER.error("Error ticking entity {} in parallel worker", entity, e);
                        }
                    }
                }, ParallelThreadPool.getPool()));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            SafeLevelAccess.leaveSafeZone();
        }
    }
}
