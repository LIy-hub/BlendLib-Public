package com.liy.blendlib.fabric.client.animation.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendInstanceKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.rules.LocomotionRuleParser;
import com.liy.blendlib.core.animation.runtime.AnimationController;
import com.liy.blendlib.core.animation.runtime.AnimationControllerDefinition;
import com.liy.blendlib.core.animation.runtime.PoseSampler;
import com.liy.blendlib.core.asset.AssetBytes;
import com.liy.blendlib.core.loader.ModelAssetLoader;
import com.liy.blendlib.core.model.ModelAsset;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Genuine Blender sidebar -> unchanged exported bytes -> existing Java playback.
 * Locomotion in the name includes this fixture in the official 26.3 test source set. */
class BlenderTransitionAuthoringLocomotionAcceptanceTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("blendlib_transitions:actor");
    private static final double EPSILON = 1.0e-6;

    @Test
    void completeOperatorAuthoredExportPreservesStrictTransitionsPrecisionDefaultsAndLocomotion() throws IOException {
        var asset = asset(1);
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        assertEquals(key("idle"), definition.initialState());
        assertEquals(10, definition.states().size());
        assertEquals(4, asset.clips().size());
        assertEquals(key("idle"), definition.state(key("attack")).next());
        assertEquals(2, definition.state(key("attack")).speed());
        assertEquals(.12345678912345678, definition.state(key("precise")).blendSeconds(), 0);
        assertEquals(0, definition.state(key("walk")).blendSeconds(), 0);
        assertEquals(0, definition.state(key("run")).blendSeconds(), 0);
        assertNull(definition.state(key("hold")).next());
        assertEquals(key("self"), definition.state(key("self")).next());
        assertEquals(key("cycle_b"), definition.state(key("cycle_a")).next());
        assertEquals(key("cycle_a"), definition.state(key("cycle_b")).next());
        var rules = LocomotionRuleParser.parse(Files.readAllBytes(path(
                BlendResourceId.parse("blendlib_transitions:blend_animation_rules/actor.json"))), asset.animationDefinition());
        assertEquals(key("idle"), rules.defaultAnimation());
        assertEquals(List.of(key("run"), key("walk")), rules.rules().stream().map(rule -> rule.animation()).toList());
        for (String name : List.of("idle", "walk", "run")) {
            assertTrue(definition.state(key(name)).loop());
            assertNull(definition.state(key(name)).next());
        }
        assertFalse(asset.primitives().isEmpty());
        assertFalse(asset.sockets().entries().isEmpty());
    }

    @Test
    void explicitTriggerUsesEnteredStatesBlendAndRealExportedPosesAtZeroHalfAndCompletion() {
        var asset = asset(1);
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        var sampler = PoseSampler.fromModelAsset(asset);
        var controller = controller(definition);
        controller.trigger(key("attack")); // Attack blend .2, rather than outgoing Idle .4.
        assertEquals(key("idle"), controller.previousState());
        assertEquals(x(asset, sampler.sample(definition.state(key("idle")), 0)), x(asset, controller.sample(sampler)), EPSILON);
        controller.advance(.1);
        assertEquals(.2, controller.currentTimeSeconds(), EPSILON); // Authored speed=2.
        double previous = x(asset, sampler.sample(definition.state(key("idle")), .1));
        double current = x(asset, sampler.sample(definition.state(key("attack")), .2));
        assertEquals((previous + current) / 2, x(asset, controller.sample(sampler)), EPSILON);
        controller.advance(.1);
        assertNull(controller.previousState());
        assertEquals(x(asset, sampler.sample(definition.state(key("attack")), .4)), x(asset, controller.sample(sampler)), EPSILON);
    }

    @Test
    void automaticNextStartsAtClipEndAndUsesDestinationBlendInWallClockSeconds() {
        var asset = asset(1);
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        var sampler = PoseSampler.fromModelAsset(asset);
        var controller = controller(definition);
        controller.trigger(key("attack"));
        var advance = controller.advance(.5); // 1-second exported clip / speed 2.
        assertEquals(1, advance.visualEvents().size());
        assertEquals(BlendResourceId.parse("blendlib_transitions:impact"), advance.visualEvents().getFirst().eventKey());
        assertEquals(key("idle"), controller.currentState());
        assertEquals(0, controller.currentTimeSeconds(), EPSILON);
        assertEquals(key("attack"), controller.previousState());
        assertEquals(x(asset, sampler.sample(definition.state(key("attack")), 1)), x(asset, controller.sample(sampler)), EPSILON);
        controller.advance(.2); // Half of entered Idle's .4, not outgoing Attack's .2.
        assertEquals(key("attack"), controller.previousState());
        double previous = x(asset, sampler.sample(definition.state(key("attack")), 1));
        double current = x(asset, sampler.sample(definition.state(key("idle")), .2));
        assertEquals((previous + current) / 2, x(asset, controller.sample(sampler)), EPSILON);
        controller.advance(.2);
        assertNull(controller.previousState());
        assertEquals(.4, controller.currentTimeSeconds(), EPSILON);
        assertEquals(x(asset, sampler.sample(definition.state(key("idle")), .4)), x(asset, controller.sample(sampler)), EPSILON);
    }

    @Test
    void noNextHoldsItsEndWhileLoopWithNextKeepsLoopingUnderExistingContract() {
        var asset = asset(1);
        var definition = AnimationControllerDefinition.fromModelAsset(asset);
        var controller = controller(definition);
        controller.trigger(key("hold"));
        assertEquals(1, controller.advance(2.25).visualEvents().size());
        assertEquals(key("hold"), controller.currentState());
        assertEquals(1, controller.currentTimeSeconds(), EPSILON);
        assertTrue(controller.advance(2).visualEvents().isEmpty());
        controller.trigger(key("loop_next"));
        assertEquals(2, controller.advance(2.25).visualEvents().size());
        assertEquals(key("loop_next"), controller.currentState());
        assertEquals(.25, controller.currentTimeSeconds(), EPSILON);
        assertNull(controller.previousState());
    }

    @Test
    void legalSelfAndTwoStateCyclesTraverseActualPositiveDurationClips() {
        var definition = AnimationControllerDefinition.fromModelAsset(asset(1));
        var controller = controller(definition);
        controller.trigger(key("self"));
        assertEquals(2, controller.advance(2.25).visualEvents().size());
        assertEquals(key("self"), controller.currentState());
        assertEquals(.25, controller.currentTimeSeconds(), EPSILON);
        controller.trigger(key("cycle_a"));
        controller.advance(1.25);
        assertEquals(key("cycle_b"), controller.currentState());
        assertEquals(.25, controller.currentTimeSeconds(), EPSILON);
        controller.advance(1);
        assertEquals(key("cycle_a"), controller.currentState());
        assertEquals(.25, controller.currentTimeSeconds(), EPSILON);
    }

    @Test
    void explicitZeroAndAbsentBlendBothCutAndNewControllerStartsFreshFromReloadedAsset() {
        var first = asset(1);
        var definition = AnimationControllerDefinition.fromModelAsset(first);
        var controller = controller(definition);
        controller.trigger(key("attack"));
        controller.advance(.1);
        controller.trigger(key("walk"));
        assertNull(controller.previousState());
        controller.trigger(key("run"));
        assertNull(controller.previousState());
        var second = asset(2);
        assertNotSame(first, second);
        var fresh = controller(AnimationControllerDefinition.fromModelAsset(second));
        assertEquals(key("idle"), fresh.currentState());
        assertEquals(0, fresh.currentTimeSeconds());
        assertNull(fresh.previousState());
        assertEquals(2, second.generation());
    }

    private static BlendAnimationKey key(String name) { return BlendAnimationKey.parse("blendlib_transitions:" + name); }
    private static AnimationController controller(AnimationControllerDefinition definition) {
        return new AnimationController(BlendInstanceKey.entity("transition-fixture", 1), definition);
    }
    private static double x(ModelAsset asset, com.liy.blendlib.core.animation.runtime.LocalPose pose) {
        int body = asset.nodes().stream().filter(node -> node.name().equals("Body")).findFirst().orElseThrow().index();
        return pose.transform(body).translation().x();
    }
    private static ModelAsset asset(long generation) {
        return new ModelAssetLoader().load(MODEL.resourceId(), generation, resource(MODEL.descriptorResourceId()),
                BlenderTransitionAuthoringLocomotionAcceptanceTest::resource);
    }
    private static AssetBytes resource(BlendResourceId id) {
        try { return new AssetBytes(id, Files.readAllBytes(path(id))); }
        catch (IOException error) { throw new UncheckedIOException("Missing genuine Blender transition export " + id, error); }
    }
    private static Path path(BlendResourceId id) {
        return Path.of(System.getProperty("blendlib.projectDir")).getParent()
                .resolve("test-assets/blender-transitions/exported/assets").resolve(id.namespace()).resolve(id.path());
    }
}
