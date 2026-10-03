package com.liy.blendlib.fabric.client.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.v2.*;
import com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridge;
import com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntime;
import com.liy.blendlib.fabric.client.reload.ClientModelRegistry;
import java.util.List;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

class BlendEntityLayerWeightsCaptureTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("capture:actor");
    private static final BlendResourceId LAYER = BlendResourceId.parse("capture:upper");
    private static final BlendAnimationKey STATE = BlendAnimationKey.parse("capture:attack");
    private static final List<ModelAnimationLayers.Layer> LAYERS = List.of(new ModelAnimationLayers.Layer(
            LAYER, 0, AnimationV2LayerMode.OVERRIDE, 1F, List.of(), STATE));

    @Test
    void rejectedWeightFrameNeverFreezesCuePlayheadOrSequenceAndRetryUsesSuccessfulTick() {
        var runtime = new SkinnedAnimationRuntime(new ClientModelRegistry(), new ClientAnimationLifecycleBridge(4));
        runtime.onPlayInit();
        Object source = new Object(), owner = new Object();
        int[] calls = {0};
        long[] sequence = {7};
        BlendEntityLayerCommands<Entity> commands = (entity, request) -> {
            calls[0]++;
            return runtime.captureEntityLayerCues(source, owner, 42, MODEL, 1, request.clientGameTick(),
                    List.of(new BlendEntityLayerCue(LAYER, STATE, sequence[0], 0, 1)));
        };
        BlendEntityLayerWeights<Entity> bad = (entity, request) -> new AnimationV2LayerWeights(Map.of(
                new AnimationV2LayerWeights.Key(LAYER, BlendResourceId.parse("capture:unknown")), 0F));
        assertThrows(IllegalArgumentException.class, () -> SkinnedAnimationEntitySnapshotFactory.captureLayerFrame(
                LAYERS, bad, commands, null, request(10)));
        assertEquals(0, calls[0], "rejected weights must not invoke/capture commands");
        int[] weightsCalls = {0};
        BlendEntityLayerWeights<Entity> good = (entity, request) -> {
            weightsCalls[0]++;
            return new AnimationV2LayerWeights(Map.of(new AnimationV2LayerWeights.Key(LAYER, LAYER), 0.5F));
        };
        var successful = SkinnedAnimationEntitySnapshotFactory.captureLayerFrame(LAYERS, good, commands, null, request(20));
        assertEquals(1, calls[0]);
        assertEquals(1, weightsCalls[0]);
        assertEquals(1.0, successful.commands().getFirst().requestedPlayheadSeconds());
        assertEquals(7, successful.commands().getFirst().sequence());
        var repeated = SkinnedAnimationEntitySnapshotFactory.captureLayerFrame(LAYERS, good, commands, null, request(30));
        assertSame(successful.commands().getFirst(), repeated.commands().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> successful.commands().clear());
        runtime.onEntityUnload(42);
        sequence[0] = 6;
        assertThrows(IllegalArgumentException.class, () -> SkinnedAnimationEntitySnapshotFactory.captureLayerFrame(
                LAYERS, bad, commands, null, request(40)));
        sequence[0] = 5;
        var earlierSequence = SkinnedAnimationEntitySnapshotFactory.captureLayerFrame(LAYERS, null, commands, null, request(50));
        assertEquals(5, earlierSequence.commands().getFirst().sequence(), "rejected frame cannot advance cue watermark");
        assertEquals(2.5, earlierSequence.commands().getFirst().requestedPlayheadSeconds());
        assertSame(AnimationV2LayerWeights.empty(), earlierSequence.weights());
    }

    private static BlendEntitySnapshotRequest request(long tick) {
        return new BlendEntitySnapshotRequest(MODEL, 0, 0, 0, 0, 0, 0, tick, true, 0);
    }
}
