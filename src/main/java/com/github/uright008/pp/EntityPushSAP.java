package com.github.uright008.pp;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.*;

/**
 * Sweep-and-prune entity push pass, modelled after the
 * {@code AcceleratedRecoiling} C++ native library.
 *
 * <p>Groups entities by quantized horizontal grid, sorts by
 * grid key + X coordinate, then checks collisions only within
 * the same or adjacent grid runs.  This reduces push-entity
 * collision from O(n²) to ~O(n × k) where k = entities/run.</p>
 */
public final class EntityPushSAP {

    private EntityPushSAP() {}

    /** Quantization scale (same as C++ SCALE=64: 1/64 block precision). */
    private static final int SCALE = 64;

    static final class SapEntry {
        int id;
        int qxMin, qyMin, qzMin;
        int qxMax, qyMax, qzMax;
    }

    /**
     * Run a global SAP-based push for the given entities.
     * Skips entities that are not alive, removed, non-pushable, or nobPhysics.
     */
    public static void pushAll(ServerLevel level, List<LivingEntity> entities) {
        int n = entities.size();
        if (n < 2) return;

        // Quantize and build entries
        SapEntry[] entries = new SapEntry[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            LivingEntity e = entities.get(i);
            if (!e.isAlive() || e.isRemoved() || !e.isPushable() || e.noPhysics) continue;
            var bb = e.getBoundingBox();
            SapEntry s = new SapEntry();
            s.id = i;
            s.qxMin = (int)(bb.minX * SCALE);
            s.qyMin = (int)(bb.minY * SCALE);
            s.qzMin = (int)(bb.minZ * SCALE);
            s.qxMax = (int)(bb.maxX * SCALE);
            s.qyMax = (int)(bb.maxY * SCALE);
            s.qzMax = (int)(bb.maxZ * SCALE);
            entries[count++] = s;
        }
        if (count < 2) return;

        // Sort by Z-grid then X-min: entries with same Z-grid become contiguous runs.
        // Grid size = 16 blocks (matching chunk/section boundaries).
        final int gridShift = 4; // 16 blocks per grid
        int[][] indexed = new int[count][2];
        for (int i = 0; i < count; i++) {
            int gz = entries[i].qzMin >> gridShift;
            int key = (gz << 20) | (entries[i].qxMin & 0xFFFFF);
            indexed[i][0] = key;
            indexed[i][1] = i;
        }
        Arrays.sort(indexed, Comparator.comparingInt(a -> a[0]));

        // Group into runs (contiguous entries with same Z-grid)
        List<Integer> runStarts = new ArrayList<>();
        runStarts.add(0);
        int prevGz = -999999;
        for (int i = 0; i < count; i++) {
            int gz = entries[indexed[i][1]].qzMin >> gridShift;
            if (gz != prevGz) {
                runStarts.add(i);
                prevGz = gz;
            }
        }
        runStarts.add(count);
        int numRuns = runStarts.size() - 1;

        // Pairwise collision within each run and with next run
        Random random = new Random();
        int maxCramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        int[] cramCounts = new int[count];

        for (int run = 0; run < numRuns; run++) {
            int start = runStarts.get(run);
            int end = runStarts.get(run + 1);

            // Within same run
            for (int p = start; p < end; p++) {
                SapEntry a = entries[indexed[p][1]];
                if (a.qxMin > a.qxMax) continue; // invalidated
                int aEntityIdx = indexed[p][1];

                for (int q = p + 1; q < end; q++) {
                    SapEntry b = entries[indexed[q][1]];
                    if (b.qxMin > a.qxMax) break; // sorted by X: no more possible overlaps
                    if (overlaps(a, b)) {
                        doPush(entities, aEntityIdx, indexed[q][1], cramCounts, p, q);
                    }
                }

                // Check next run too (adjacent grid may have overlapping entities)
                if (run + 1 < numRuns) {
                    int nextStart = runStarts.get(run + 1);
                    int nextEnd = runStarts.get(run + 2);
                    for (int q = nextStart; q < nextEnd; q++) {
                        SapEntry b = entries[indexed[q][1]];
                        if (b.qxMin > a.qxMax + SCALE) break; // X gap too large
                        if (overlaps(a, b)) {
                            doPush(entities, aEntityIdx, indexed[q][1], cramCounts, p, q);
                        }
                    }
                }
            }
        }

        // Cramming damage
        if (maxCramming <= 0) return;

        for (int i = 0; i < count; i++) {
            if (cramCounts[i] > maxCramming - 1 && random.nextInt(4) == 0) {
                LivingEntity e = entities.get(indexed[i][1]);
                if (e.isAlive()) {
                    e.hurtServer(level, e.damageSources().cramming(), 6.0F);
                }
            }
        }
    }

    private static boolean overlaps(SapEntry a, SapEntry b) {
        return a.qxMin < b.qxMax && a.qxMax > b.qxMin
            && a.qyMin < b.qyMax && a.qyMax > b.qyMin
            && a.qzMin < b.qzMax && a.qzMax > b.qzMin;
    }

    private static void doPush(List<LivingEntity> entities, int aIdx, int bIdx,
                                int[] cramCounts, int posA, int posB) {
        LivingEntity a = entities.get(aIdx);
        LivingEntity b = entities.get(bIdx);
        if (!a.isAlive() || !b.isAlive()) return;
        if (a.isPassengerOfSameVehicle(b)) return;
        boolean aClimbing = a.onClimbable();
        boolean bClimbing = b.onClimbable();
        if (aClimbing && bClimbing) return;
        if (!aClimbing) a.push(b);
        if (!bClimbing) b.push(a);
        if (!a.isPassenger()) cramCounts[posA]++;
        if (!b.isPassenger()) cramCounts[posB]++;
    }
}
