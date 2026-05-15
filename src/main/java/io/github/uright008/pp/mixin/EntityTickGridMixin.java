package io.github.uright008.pp.mixin;

import io.github.uright008.pc.EntityGrid;
import io.github.uright008.pc.EntityGridManager;
import io.github.uright008.pp.PhysicsParallelConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an immutable {@link EntityGrid} snapshot before the entity tick
 * loop and clears it after, so every {@code getEntities()} call during
 * entity AI / sensing / pushEntities uses the snapshot instead of
 * iterating the live {@code EntitySectionStorage}.
 */
@Mixin(ServerLevel.class)
public abstract class EntityTickGridMixin {

    @Shadow
    @Final
    private EntityTickList entityTickList;

    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"
            )
    )
    private void buildEntityGrid(CallbackInfo ci) {
        if (!PhysicsParallelConfig.isEnabled()) {
            return;
        }
        List<Entity> snapshot = new ArrayList<>();
        entityTickList.forEach(snapshot::add);
        EntityGrid grid = new EntityGrid(snapshot);
        EntityGridManager.setActiveGrid(grid);
    }

}
