package com.liy.blendlib.fabric.client.blockentity;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.runtime.MorphFrameOverrides;
import com.liy.blendlib.fabric.client.api.BlendRenderer;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

class StaticMorphBlockEntitySnapshotFactoryTest {
    @Test void controlsRequireTheStaticMorphPathAndSnapshotPathsAreExclusive() {
        BlendBlockEntityMorphControls<BlockEntity> controls = (block, request) -> MorphFrameOverrides.empty();
        assertThrows(IllegalStateException.class, () -> builder().morphControls(controls));
        assertThrows(IllegalStateException.class, () -> builder().build());
        for (var other : java.util.List.of(builder().staticRestPose(),
                builder().syncedSkinnedAnimation(BlendAnimationKey.parse("test:idle")),
                builder().snapshotFactory((block, request) -> null))) {
            assertThrows(IllegalStateException.class, other::staticMorph);
            assertThrows(IllegalStateException.class, () -> other.morphControls(controls));
        }
        var stat = builder().staticMorph();
        assertSame(stat, stat.morphControls(controls));
        assertThrows(NullPointerException.class, () -> stat.morphControls(null));
        assertThrows(IllegalStateException.class, stat::staticMorph);
        assertThrows(IllegalStateException.class, stat::staticRestPose);
        assertThrows(IllegalStateException.class, () -> stat.syncedSkinnedAnimation(BlendAnimationKey.parse("test:idle")));
        assertThrows(IllegalStateException.class, () -> stat.snapshotFactory((block, request) -> null));
        assertDoesNotThrow(stat::build);
        assertDoesNotThrow(stat::build);
        assertDoesNotThrow(() -> builder().staticMorph().build());
    }

    @Test void staleOwnerSkipsCallbackAndReplacementInsideCallbackDiscardsBatch() {
        assertTrue(StaticMorphBlockEntitySnapshotFactory.<BlockEntity>captureControls(null, null,
                (block, request) -> fail("Stale owner must not call controls"), () -> false).isEmpty());
        var current = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        assertTrue(StaticMorphBlockEntitySnapshotFactory.<BlockEntity>captureControls(null, null,
                (block, request) -> {
                    calls.incrementAndGet(); current.set(false); return MorphFrameOverrides.empty();
                }, current::get).isEmpty());
        assertEquals(1, calls.get());
    }

    @Test void controlsAreCapturedOnceAndOmissionDoesNotReuseThePreviousFrame() {
        var calls = new AtomicInteger();
        var source = new java.util.HashMap<BlendResourceId, Float>();
        var smile = BlendResourceId.parse("test:smile");
        source.put(smile, 1F);
        var first = StaticMorphBlockEntitySnapshotFactory.<BlockEntity>captureControls(null, null,
                (block, request) -> { calls.incrementAndGet(); return new MorphFrameOverrides(source); },
                () -> true).orElseThrow();
        source.clear();
        var next = StaticMorphBlockEntitySnapshotFactory.<BlockEntity>captureControls(null, null,
                null, () -> true).orElseThrow();
        assertEquals(1, calls.get());
        assertEquals(Map.of(smile, 1F), first.values());
        assertTrue(next.values().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> first.values().clear());
        assertThrows(NullPointerException.class, () -> StaticMorphBlockEntitySnapshotFactory.<BlockEntity>captureControls(
                null, null, (block, request) -> null, () -> true));
    }

    private static BlendBlockEntityRendererBuilder<BlockEntity> builder() {
        try {
            Class<?> type = Class.forName("sun.misc.Unsafe");
            Field field = type.getDeclaredField("theUnsafe"); field.setAccessible(true);
            var context = (BlockEntityRendererProvider.Context) type.getMethod("allocateInstance", Class.class)
                    .invoke(field.get(null), BlockEntityRendererProvider.Context.class);
            return new BlendBlockEntityRendererBuilder<>(context, BlendModelKey.parse("test:morph"),
                    new BlendRenderer((snapshot, renderContext) -> { }));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
