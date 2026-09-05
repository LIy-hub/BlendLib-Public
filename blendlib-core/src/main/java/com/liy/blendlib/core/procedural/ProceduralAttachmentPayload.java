package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.Objects;

/** Semantic attachment payloads deliberately exclude ItemStack, BlockState, worlds, and renderers. */
public sealed interface ProceduralAttachmentPayload permits ProceduralAttachmentPayload.Item,
        ProceduralAttachmentPayload.Block, ProceduralAttachmentPayload.ChildModel {

    record Item(BlendResourceId itemId) implements ProceduralAttachmentPayload {
        public Item {
            itemId = Objects.requireNonNull(itemId, "itemId");
            ProceduralSupport.requireId(itemId, "item id");
        }
    }

    record Block(BlendResourceId blockId) implements ProceduralAttachmentPayload {
        public Block {
            blockId = Objects.requireNonNull(blockId, "blockId");
            ProceduralSupport.requireId(blockId, "block id");
        }
    }

    record ChildModel(BlendModelKey modelKey) implements ProceduralAttachmentPayload {
        public ChildModel {
            modelKey = Objects.requireNonNull(modelKey, "modelKey");
            ProceduralSupport.requireId(modelKey.resourceId(), "child model key");
        }
    }
}
