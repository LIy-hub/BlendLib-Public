package com.liy.blendlib.showcase;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.showcase.client.ShowcaseAnimatedActorStateSchedule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShowcaseAnimatedActorStateScheduleTest {
    private static final BlendAnimationKey IDLE = BlendAnimationKey.parse("blendlib_showcase:idle");
    private static final BlendAnimationKey WALK = BlendAnimationKey.parse("blendlib_showcase:walk");
    private static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("blendlib_showcase:attack");

    @Test
    void selectsEachCanonicalStateAtDocumentedWindowBoundaries() {
        assertEquals(IDLE, ShowcaseAnimatedActorStateSchedule.stateAt(0.0F));
        assertEquals(IDLE, ShowcaseAnimatedActorStateSchedule.stateAt(
                ShowcaseAnimatedActorStateSchedule.IDLE_WINDOW_TICKS - 0.01F));
        assertEquals(WALK, ShowcaseAnimatedActorStateSchedule.stateAt(
                ShowcaseAnimatedActorStateSchedule.IDLE_WINDOW_TICKS));
        assertEquals(WALK, ShowcaseAnimatedActorStateSchedule.stateAt(
                ShowcaseAnimatedActorStateSchedule.IDLE_WINDOW_TICKS
                        + ShowcaseAnimatedActorStateSchedule.WALK_WINDOW_TICKS - 0.01F));
        assertEquals(ATTACK, ShowcaseAnimatedActorStateSchedule.stateAt(
                ShowcaseAnimatedActorStateSchedule.IDLE_WINDOW_TICKS
                        + ShowcaseAnimatedActorStateSchedule.WALK_WINDOW_TICKS));
        assertEquals(ATTACK, ShowcaseAnimatedActorStateSchedule.stateAt(
                ShowcaseAnimatedActorStateSchedule.CYCLE_TICKS - 0.01F));
        assertEquals(IDLE, ShowcaseAnimatedActorStateSchedule.stateAt(
                ShowcaseAnimatedActorStateSchedule.CYCLE_TICKS));
    }

    @Test
    void scheduleIsPeriodicAndEveryReturnValueIsOneOfTheThreeCanonicalKeys() {
        assertTrue(ShowcaseAnimatedActorStateSchedule.IDLE_WINDOW_TICKS > 0.0F);
        assertTrue(ShowcaseAnimatedActorStateSchedule.WALK_WINDOW_TICKS > 0.0F);
        assertTrue(ShowcaseAnimatedActorStateSchedule.ATTACK_WINDOW_TICKS > 0.0F);

        Set<BlendAnimationKey> observed = new HashSet<>();
        for (int tick = 0; tick < (int) ShowcaseAnimatedActorStateSchedule.CYCLE_TICKS; tick++) {
            BlendAnimationKey state = ShowcaseAnimatedActorStateSchedule.stateAt(tick);
            observed.add(state);
            assertTrue(Set.of(IDLE, WALK, ATTACK).contains(state));
            assertEquals(state, ShowcaseAnimatedActorStateSchedule.stateAt(
                    tick + ShowcaseAnimatedActorStateSchedule.CYCLE_TICKS));
        }

        assertEquals(Set.of(IDLE, WALK, ATTACK), observed);
    }

    @Test
    void rejectsNonFiniteAndNegativeAges() {
        assertThrows(IllegalArgumentException.class, () -> ShowcaseAnimatedActorStateSchedule.stateAt(-0.01F));
        assertThrows(IllegalArgumentException.class, () -> ShowcaseAnimatedActorStateSchedule.stateAt(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> ShowcaseAnimatedActorStateSchedule.stateAt(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> ShowcaseAnimatedActorStateSchedule.stateAt(Float.NEGATIVE_INFINITY));
    }

    @Test
    void sourceRemainsAClientOnlyPureSemanticSchedule() throws IOException {
        Path source = Path.of(
                System.getProperty("blendlib.projectDir"),
                "src", "client", "java", "com", "liy", "blendlib", "showcase", "client",
                "ShowcaseAnimatedActorStateSchedule.java");
        String contents = Files.readString(source).toLowerCase();

        assertTrue(contents.contains("showcaseskinnedanimationbinding.idle"));
        assertTrue(contents.contains("showcaseskinnedanimationbinding.walk"));
        assertTrue(contents.contains("showcaseskinnedanimationbinding.attack"));
        for (String forbidden : List.of(
                "import com.liy.blendlib.core.",
                "import com.liy.blendlib.fabric.client.reload.",
                "import com.liy.blendlib.fabric.client.render.",
                "import net.minecraft.",
                "import net.fabricmc.",
                "import java.io.",
                "import java.nio.file.",
                "network",
                "payload",
                "controller",
                "renderer",
                "files.",
                "resourcemanager",
                "glb",
                "json"
        )) {
            assertTrue(!contents.contains(forbidden), () -> "schedule must not reference " + forbidden);
        }
    }
}
