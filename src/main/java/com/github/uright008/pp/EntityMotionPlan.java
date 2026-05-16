package com.github.uright008.pp;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Pre-computed per-entity motion state from the parallel read-phase.
 *
 * <p>Contains only the result of {@code moveRelative} + onClimbable
 * clamping.  The entity's {@code deltaMovement} has already been
 * decayed, dead-zoned, and optionally modified by jump handling
 * by vanilla {@code aiStep()} before the {@code travel()} redirect.
 */
public record EntityMotionPlan(LivingEntity entity, Vec3 preMoveDelta) {}
