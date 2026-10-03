package com.liy.blendlib.examples.attachments;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.entity.BlendEntityAttachment;
import com.liy.blendlib.fabric.client.entity.BlendEntityAttachmentComposition;
import com.liy.blendlib.fabric.client.entity.BlendEntitySocket;
import com.liy.blendlib.fabric.client.entity.BlendEntitySocketPose;
import com.liy.blendlib.fabric.client.entity.BlendEntitySockets;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Extraction-only character -> weapon -> ornament composition using prepared public snapshots.
 * The host must provide a conservative character culling envelope covering all three models.
 * This helper neither samples animation nor resolves resources or manages controller lifecycles.
 */
public final class ExampleNestedEntityAttachments {
    public static final BlendResourceId HAND = BlendResourceId.parse("example:hand");
    public static final BlendResourceId ORNAMENT_MOUNT = BlendResourceId.parse("example:ornament_mount");

    private ExampleNestedEntityAttachments() {}

    /**
     * Builds the nested immutable graph during extraction. Both socket sets must come from the
     * final modified poses used to prepare their respective snapshots in this same frame.
     * Existing attachments are retained. Missing required sockets are configuration errors here.
     */
    public static ModelRenderSnapshot compose(
            ModelRenderSnapshot character, BlendEntitySockets characterSockets,
            ModelRenderSnapshot weapon, BlendEntitySockets weaponSockets,
            ModelRenderSnapshot ornament) {
        BlendEntitySocket hand = requireSocket(character, characterSockets, HAND);
        BlendEntitySocket ornamentMount = requireSocket(weapon, weaponSockets, ORNAMENT_MOUNT);
        Objects.requireNonNull(ornament, "ornament");

        // Offsets are socket-oriented blocks, not the owning asset's authored model units.
        BlendEntitySocketPose ornamentOffset = new BlendEntitySocketPose(
                0.0, 0.05, 0.0, BlendEntitySocketPose.IDENTITY.rotation(), 1.0F);
        ModelRenderSnapshot decoratedWeapon = append(weapon,
                BlendEntityAttachment.at(ornamentMount, ornamentOffset, ornament));

        // ornamentMount already includes weapon.rootTransform(). Do not apply that root again.
        return append(character, BlendEntityAttachment.at(hand, decoratedWeapon));
    }

    /**
     * Optional reusable capture for a custom host. The standard entity renderer captures its
     * configured attachments itself; do not flatten twice or submit both nested and flat lists.
     */
    public static BlendEntityAttachmentComposition capture(
            ModelRenderSnapshot character, BlendEntitySockets characterSockets,
            ModelRenderSnapshot weapon, BlendEntitySockets weaponSockets,
            ModelRenderSnapshot ornament) {
        return BlendEntityAttachmentComposition.capture(
                compose(character, characterSockets, weapon, weaponSockets, ornament));
    }

    private static ModelRenderSnapshot append(ModelRenderSnapshot parent, BlendEntityAttachment child) {
        var attachments = new ArrayList<>(parent.attachments());
        attachments.add(child);
        return parent.withAttachments(attachments);
    }

    private static BlendEntitySocket requireSocket(
            ModelRenderSnapshot snapshot, BlendEntitySockets sockets, BlendResourceId key) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(sockets, "sockets");
        if (sockets.generation() != snapshot.generation()) {
            throw new IllegalArgumentException("Final sockets must match their snapshot generation");
        }
        return sockets.socket(key).orElseThrow(() ->
                new IllegalArgumentException("Required example socket is absent: " + key));
    }
}
