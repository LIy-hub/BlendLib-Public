package com.liy.blendlib.showcase.client;

import com.liy.blendlib.api.BlendAnimationKey;

/**
 * Deterministic local presentation schedule for the Showcase animated actor.
 *
 * <p>Each 132-tick cycle has nonzero idle [0, 80), walk [80, 120), and short single-display
 * attack [120, 132) windows. This is a client-only demonstration timeline; callers provide the
 * finite local age and receive only one of the canonical Showcase state keys.</p>
 */
public final class ShowcaseAnimatedActorStateSchedule {
    public static final float IDLE_WINDOW_TICKS = 80.0F;
    public static final float WALK_WINDOW_TICKS = 40.0F;
    public static final float ATTACK_WINDOW_TICKS = 12.0F;
    public static final float CYCLE_TICKS = IDLE_WINDOW_TICKS + WALK_WINDOW_TICKS + ATTACK_WINDOW_TICKS;

    private ShowcaseAnimatedActorStateSchedule() {
    }

    /**
     * Selects one canonical demonstration state for a finite, non-negative local age.
     *
     * @throws IllegalArgumentException when the age is non-finite or negative
     */
    public static BlendAnimationKey stateAt(float ageInTicks) {
        if (!Float.isFinite(ageInTicks) || ageInTicks < 0.0F) {
            throw new IllegalArgumentException("ageInTicks must be finite and non-negative");
        }

        float phase = ageInTicks % CYCLE_TICKS;
        if (phase < IDLE_WINDOW_TICKS) {
            return ShowcaseSkinnedAnimationBinding.IDLE;
        }
        if (phase < IDLE_WINDOW_TICKS + WALK_WINDOW_TICKS) {
            return ShowcaseSkinnedAnimationBinding.WALK;
        }
        return ShowcaseSkinnedAnimationBinding.ATTACK;
    }
}
