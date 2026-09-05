package com.liy.blendlib.datagen;

/**
 * Public pure-Java facade for deterministic BlendLib descriptor and sidecar generation.
 *
 * <p><strong>Stable boundary:</strong> this module is a development/build tool. It never reads
 * {@code .blend}, FBX, or OBJ, imports no Minecraft/Fabric/NeoForge type, and is not used by the
 * runtime submit path.</p>
 */
public final class BlendLibDatagen {
    private BlendLibDatagen() {
    }

    /**
     * Starts a new explicit pure-Java generation request.
     *
     * @return mutable builder that validates before any output write
     */
    public static BlendLibDatagenBuilder builder() {
        return new BlendLibDatagenBuilder();
    }
}
