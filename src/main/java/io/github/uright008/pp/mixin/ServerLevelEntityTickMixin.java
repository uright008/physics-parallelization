package io.github.uright008.pp.mixin;

import io.github.uright008.pp.EntityMotionHelper;
import io.github.uright008.pp.PhysicsParallelConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flushes deferred entity physics inside the entity-tick section,
 * before block-entity ticking begins.
 */
@Mixin(Level.class)
public abstract class ServerLevelEntityTickMixin {

    @Inject(method = "tickBlockEntities", at = @At("HEAD"))
    private void beforeTickBlockEntities(CallbackInfo ci) {
        if (!PhysicsParallelConfig.isEnabled()) return;

        Level self = (Level) (Object) this;
        if (!(self instanceof ServerLevel serverLevel)) return;

        ProfilerFiller profiler = Profiler.get();
        profiler.push("tick");
        EntityMotionHelper.flush(serverLevel);
        profiler.pop();
    }
}
