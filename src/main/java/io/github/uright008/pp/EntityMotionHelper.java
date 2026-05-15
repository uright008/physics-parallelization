package io.github.uright008.pp;

import io.github.uright008.pc.EntityGridManager;
import io.github.uright008.pc.ParallelThreadPool;
import io.github.uright008.pc.SafeLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Two-phase parallel entity motion engine for {@code travelInAir}.
 *
 * <p>Only defers {@code travel()} — {@code pushEntities()} remains
 * per-entity in vanilla to keep the tick pipeline lean.</p>
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li><b>Phase 1 (parallel, read-only)</b> — compute
 *       {@code moveRelative} + onClimbable clamping.</li>
 *   <li><b>Phase 2 (sequential, main thread)</b> — set pre-computed
 *       deltaMovement, run {@code move()}, apply gravity + friction.</li>
 * </ol>
 */
public final class EntityMotionHelper {

    private EntityMotionHelper() {}

    private static final List<CapturedEntity> BATCH = new ArrayList<>();

    /** Entities with deltaMovement length below this skip move(). */
    private static final double STATIONARY_THRESHOLD = 1.0E-6;

    private record CapturedEntity(LivingEntity entity, Vec3 travelInput) {}

    // ── Mixin-facing API ────────────────────────

    public static void enqueue(LivingEntity entity, Vec3 travelInput) {
        BATCH.add(new CapturedEntity(entity, travelInput));
    }

    public static void flush(ServerLevel level) {
        if (BATCH.isEmpty()) return;

        List<CapturedEntity> batch;
        synchronized (BATCH) {
            batch = new ArrayList<>(BATCH);
            BATCH.clear();
        }

        if (batch.size() < PhysicsParallelConfig.getMinParallelEntities()) {
            for (CapturedEntity ce : batch) {
                ce.entity.travel(ce.travelInput);
            }
            return;
        }

        List<EntityMotionPlan> plans;
        if (batch.size() == 1) {
            CapturedEntity c = batch.getFirst();
            SafeLevelAccess.enterSafeZone();
            try {
                plans = List.of(computePhase1(c.entity, c.travelInput));
            } finally {
                SafeLevelAccess.leaveSafeZone();
            }
        } else {
            plans = computePhase1Parallel(batch);
        }

        executePhase2(plans);

        // Global SAP-based entity push (replaces per-entity pushEntities)
        List<LivingEntity> pushed = new ArrayList<>(plans.size());
        for (EntityMotionPlan plan : plans) {
            if (plan.entity().isAlive()) pushed.add(plan.entity());
        }
        EntityPushSAP.pushAll(level, pushed);

        // Clear entity grid now that all entity processing is complete
        EntityGridManager.setActiveGrid(null);
    }

    // ── Phase 1: parallel moveRelative + climbable ─

    private static List<EntityMotionPlan> computePhase1Parallel(List<CapturedEntity> batch) {
        int count = batch.size();
        EntityMotionPlan[] results = new EntityMotionPlan[count];
        Executor pool = ParallelThreadPool.getPool("Physics");

        List<CompletableFuture<Void>> futures = new ArrayList<>(count - 1);
        for (int i = 0; i < count - 1; i++) {
            final int idx = i;
            final CapturedEntity ce = batch.get(i);
            futures.add(CompletableFuture.runAsync(() -> {
                SafeLevelAccess.enterSafeZone();
                try {
                    results[idx] = computePhase1(ce.entity, ce.travelInput);
                } finally {
                    SafeLevelAccess.leaveSafeZone();
                }
            }, pool));
        }

        SafeLevelAccess.enterSafeZone();
        try {
            CapturedEntity last = batch.getLast();
            results[count - 1] = computePhase1(last.entity, last.travelInput);
        } finally {
            SafeLevelAccess.leaveSafeZone();
        }

        for (CompletableFuture<Void> f : futures) {
            f.join();
        }

        List<EntityMotionPlan> plans = new ArrayList<>(count);
        for (EntityMotionPlan r : results) {
            plans.add(r);
        }
        return plans;
    }

    private static EntityMotionPlan computePhase1(LivingEntity entity, Vec3 travelInput) {
        Vec3 delta = entity.getDeltaMovement();

        if (entity.canSimulateMovement() && entity.isEffectiveAi()) {
            BlockPos posBelow = entity.getBlockPosBelowThatAffectsMyMovement();
            float speed;
            if (entity.onGround()) {
                BlockState stateBelow = entity.level().getBlockState(posBelow);
                float blockFriction = stateBelow.getBlock().getFriction();
                speed = entity.getSpeed() * (0.21600002F / (blockFriction * blockFriction * blockFriction));
            } else {
                speed = entity.getControllingPassenger() instanceof Player
                        ? entity.getSpeed() * 0.1F
                        : 0.02F;
            }

            delta = delta.add(getInputVector(travelInput, speed, entity.getYRot()));

            if (entity.onClimbable()) {
                delta = new Vec3(
                        Mth.clamp(delta.x, -0.15, 0.15),
                        Math.max(delta.y, -0.15),
                        Mth.clamp(delta.z, -0.15, 0.15));
            }
        }

        return new EntityMotionPlan(entity, delta);
    }

    // ── Phase 2: sequential move + post-move effects ─

    private static void executePhase2(List<EntityMotionPlan> plans) {
        for (EntityMotionPlan plan : plans) {
            LivingEntity entity = plan.entity();
            if (!entity.isAlive() || entity.isRemoved()) continue;

            Vec3 preMove = plan.preMoveDelta();
            entity.setDeltaMovement(preMove);

            if (preMove.lengthSqr() > STATIONARY_THRESHOLD) {
                entity.move(MoverType.SELF, entity.getDeltaMovement());
            }
            Vec3 postMove = entity.getDeltaMovement();

            double movementY = postMove.y;
            var levitation = entity.getEffect(net.minecraft.world.effect.MobEffects.LEVITATION);
            if (levitation != null) {
                movementY += (0.05 * (levitation.getAmplifier() + 1) - movementY) * 0.2;
            } else {
                movementY -= getEffectiveGravity(entity);
            }

            if (!entity.shouldDiscardFriction()) {
                BlockPos posBelow = entity.getBlockPosBelowThatAffectsMyMovement();
                float blockFriction = entity.onGround()
                        ? entity.level().getBlockState(posBelow).getBlock().getFriction()
                        : 1.0F;
                float friction = blockFriction * 0.91F;
                entity.setDeltaMovement(
                        postMove.x * friction,
                        movementY * 0.98F,
                        postMove.z * friction);
            } else {
                entity.setDeltaMovement(postMove.x, movementY, postMove.z);
            }
        }
    }

    // ── Vanilla-equivalent helpers ───────────────

    static Vec3 getInputVector(Vec3 input, float speed, float yRot) {
        double lenSq = input.lengthSqr();
        if (lenSq < 1.0E-7) return Vec3.ZERO;
        Vec3 movement = (lenSq > 1.0 ? input.normalize() : input).scale(speed);
        float sin = Mth.sin(yRot * (float) (Math.PI / 180.0));
        float cos = Mth.cos(yRot * (float) (Math.PI / 180.0));
        return new Vec3(
                movement.x * cos - movement.z * sin,
                movement.y,
                movement.z * cos + movement.x * sin);
    }

    static double getEffectiveGravity(LivingEntity entity) {
        boolean falling = entity.getDeltaMovement().y <= 0.0;
        if (falling && entity.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)) {
            return Math.min(entity.getGravity(), 0.01);
        }
        return entity.getGravity();
    }
}
