package com.liy.blendlib.showcase.perf.scene;

import com.liy.blendlib.showcase.entity.P7BenchmarkRigidEntity;
import com.liy.blendlib.showcase.entity.P7BenchmarkSkinnedEntity;
import com.liy.blendlib.showcase.entity.ShowcaseEntities;
import com.liy.blendlib.showcase.perf.P7ReferenceScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Explicit server-side creation and removal of the isolated P7 benchmark hosts.
 *
 * <p>No lifecycle hook calls this class. It may be invoked only by the administrator-only command
 * registrar, and it refuses to add a second scene to a level that already contains benchmark
 * hosts. All model keys remain in {@link P7BenchmarkScenePlan} for the client binding; no model
 * resource is read, stored, or sent by this server-safe spawner.</p>
 */
public final class P7BenchmarkSceneSpawner {
    private P7BenchmarkSceneSpawner() {
    }

    /** Creates exactly the frozen 100-rigid/25-skinned host set in the supplied isolated level. */
    public static SpawnResult spawn(ServerLevel level) {
        ServerLevel checkedLevel = Objects.requireNonNull(level, "level");
        ActiveCounts existing = countActive(checkedLevel);
        if (existing.total() != 0) {
            throw new IllegalStateException("P7 benchmark scene already exists in this level: " + existing.total()
                    + " host(s); run /blendlib_showcase p7 clear before spawning again");
        }

        P7BenchmarkScenePlan plan = P7BenchmarkScenePlan.standard();
        List<Entity> added = new ArrayList<>(plan.placements().size());
        try {
            for (P7BenchmarkScenePlan.Placement placement : plan.placements()) {
                Entity entity = createHost(checkedLevel, placement.kind());
                entity.setPos(placement.x(), placement.y(), placement.z());
                entity.setYRot(0.0F);
                entity.setXRot(0.0F);
                entity.setNoGravity(true);
                entity.setDeltaMovement(Vec3.ZERO);
                if (!checkedLevel.addFreshEntity(entity)) {
                    throw new IllegalStateException("P7 benchmark host was rejected at ordinal " + placement.ordinal());
                }
                added.add(entity);
            }
        } catch (RuntimeException exception) {
            added.forEach(Entity::discard);
            throw exception;
        }

        ActiveCounts active = countActive(checkedLevel);
        if (active.rigid() != P7ReferenceScenario.RIGID_INSTANCE_COUNT
                || active.skinned() != P7ReferenceScenario.SKINNED_INSTANCE_COUNT) {
            added.forEach(Entity::discard);
            throw new IllegalStateException("P7 benchmark scene did not retain the frozen host counts: " + active);
        }
        return new SpawnResult(active.rigid(), active.skinned(), plan.camera());
    }

    /** Removes only the two opt-in benchmark host types from the supplied isolated level. */
    public static int clear(ServerLevel level) {
        ServerLevel checkedLevel = Objects.requireNonNull(level, "level");
        List<Entity> removable = new ArrayList<>();
        for (Entity entity : checkedLevel.getAllEntities()) {
            if (isBenchmarkHost(entity)) {
                removable.add(entity);
            }
        }
        removable.forEach(Entity::discard);
        return removable.size();
    }

    /** Returns the loaded benchmark counts without creating or changing any entity. */
    public static ActiveCounts countActive(ServerLevel level) {
        ServerLevel checkedLevel = Objects.requireNonNull(level, "level");
        int rigid = 0;
        int skinned = 0;
        for (Entity entity : checkedLevel.getAllEntities()) {
            if (entity instanceof P7BenchmarkRigidEntity) {
                rigid++;
            } else if (entity instanceof P7BenchmarkSkinnedEntity) {
                skinned++;
            }
        }
        return new ActiveCounts(rigid, skinned);
    }

    private static Entity createHost(ServerLevel level, P7ReferenceScenario.Kind kind) {
        return switch (kind) {
            case RIGID -> new P7BenchmarkRigidEntity(ShowcaseEntities.P7_BENCHMARK_RIGID, level);
            case SKINNED -> new P7BenchmarkSkinnedEntity(ShowcaseEntities.P7_BENCHMARK_SKINNED, level);
        };
    }

    private static boolean isBenchmarkHost(Entity entity) {
        return entity instanceof P7BenchmarkRigidEntity || entity instanceof P7BenchmarkSkinnedEntity;
    }

    /** Immutable command result; it is evidence of entity creation, never of render performance. */
    public record SpawnResult(int rigidCount, int skinnedCount, P7ReferenceScenario.CameraPose prescribedCamera) {
        public SpawnResult {
            prescribedCamera = Objects.requireNonNull(prescribedCamera, "prescribedCamera");
            if (rigidCount != P7ReferenceScenario.RIGID_INSTANCE_COUNT
                    || skinnedCount != P7ReferenceScenario.SKINNED_INSTANCE_COUNT) {
                throw new IllegalArgumentException("P7 spawn result must retain the frozen host counts");
            }
        }

        public int totalCount() {
            return Math.addExact(rigidCount, skinnedCount);
        }
    }

    /** Loaded host counts for one server level. */
    public record ActiveCounts(int rigid, int skinned) {
        public ActiveCounts {
            if (rigid < 0 || skinned < 0) {
                throw new IllegalArgumentException("P7 active host counts must not be negative");
            }
        }

        public int total() {
            return Math.addExact(rigid, skinned);
        }
    }
}
