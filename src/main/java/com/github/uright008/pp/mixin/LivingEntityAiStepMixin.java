package com.github.uright008.pp.mixin;

import com.github.uright008.pp.EntityMotionHelper;
import com.github.uright008.pp.PhysicsParallelConfig;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects {@link LivingEntity#travel(Vec3)} within {@code aiStep()}
 * to batch-defer it for parallel processing.
 *
 * <p>Only air-mode, non-flying, non-fluid entities are batched.
 * {@code pushEntities()} is left to vanilla per-entity processing.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityAiStepMixin {

    @Redirect(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;travel(Lnet/minecraft/world/phys/Vec3;)V")
    )
    private void redirectTravel(LivingEntity self, Vec3 input) {
        if (shouldBatch(self)) {
            EntityMotionHelper.enqueue(self, input);
        } else {
            self.travel(input);
        }
    }

    private static boolean shouldBatch(LivingEntity self) {
        return PhysicsParallelConfig.isEnabled()
                && PhysicsParallelConfig.isLightEntitiesParallel()
                && !(self instanceof Player)
                && !self.isInWater()
                && !self.isInLava()
                && !self.isFallFlying();
    }
}
