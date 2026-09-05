package com.liy.blendlib.examples.ecosystem;

import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;

/** Canonical semantic keys shared by the common and client source sets. */
public final class ExampleKeys {
    public static final String MOD_ID = "blendlib_ecosystem_example";
    public static final BlendModelKey ACTOR_MODEL = BlendModelKey.parse(MOD_ID + ":actors/example_actor");
    public static final BlendModelKey ITEM_MODEL = BlendModelKey.parse(MOD_ID + ":items/example_item");
    public static final BlendAnimationKey IDLE = BlendAnimationKey.parse(MOD_ID + ":idle");
    public static final BlendAnimationKey ATTACK = BlendAnimationKey.parse(MOD_ID + ":attack");
    public static final BlendResourceId TIP_SOCKET = BlendResourceId.parse(MOD_ID + ":tip");
    public static final BlendResourceId ATTACK_WHOOSH = BlendResourceId.parse(MOD_ID + ":attack_whoosh");

    private ExampleKeys() {
    }
}
