package com.liy.blendlib.fabric.client.entity;

import com.liy.blendlib.fabric.client.render.MaterialSlotAppearance;
import java.util.Map;
import net.minecraft.world.entity.Entity;

/** Extraction-only exact authored material-slot selection; never consulted during submit. */
@FunctionalInterface
public interface BlendEntityMaterialAppearance<E extends Entity> {
    Map<String, MaterialSlotAppearance> select(E entity, BlendEntitySnapshotRequest request);
}
