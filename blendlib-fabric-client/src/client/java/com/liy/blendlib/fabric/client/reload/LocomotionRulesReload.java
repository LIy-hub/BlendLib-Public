package com.liy.blendlib.fabric.client.reload;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.animation.rules.LocomotionRuleParser;
import com.liy.blendlib.core.animation.rules.LocomotionRules;
import com.liy.blendlib.core.diagnostic.BlendDiagnostic;
import com.liy.blendlib.core.diagnostic.DiagnosticSeverity;
import com.liy.blendlib.core.model.ModelAsset;
import com.liy.blendlib.fabric.BlendFabricResourceIds;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Reload-prepare-only resource boundary for optional, untrusted locomotion sidecars. */
final class LocomotionRulesReload {
    static final String DIAGNOSTIC_CODE = "LOCOMOTION_001";
    static final int MAX_WARNING_CHARACTERS = 1024;

    private LocomotionRulesReload() {
    }

    static Optional<LocomotionRules> prepare(ResourceManager resources, BlendModelKey model, ModelAsset asset,
            List<BlendDiagnostic> diagnostics) {
        BlendResourceId resourceId = BlendResourceId.of(
                model.namespace(), "blend_animation_rules/" + model.path() + ".json");
        try {
            Optional<Resource> selected = resources.getResource(BlendFabricResourceIds.toIdentifier(resourceId));
            if (selected.isEmpty()) {
                return Optional.empty();
            }
            byte[] bytes;
            try (InputStream stream = selected.orElseThrow().open()) {
                // A single extra byte proves oversize without draining an untrusted stream.
                bytes = stream.readNBytes(LocomotionRuleParser.MAX_INPUT_BYTES + 1);
            }
            if (bytes.length > LocomotionRuleParser.MAX_INPUT_BYTES) {
                throw new IllegalArgumentException("Locomotion sidecar byte limit exceeded (64 KiB)");
            }
            if (asset.animationDefinition() == null) {
                throw new IllegalArgumentException("Model has no declared animation states");
            }
            return Optional.of(LocomotionRuleParser.parse(bytes, asset.animationDefinition()));
        } catch (IOException | RuntimeException exception) {
            String prefix = "Optional locomotion rules disabled: ";
            String explanation = exception.getMessage();
            if (explanation == null || explanation.isBlank()) {
                explanation = exception.getClass().getSimpleName();
            }
            int available = MAX_WARNING_CHARACTERS - prefix.length();
            if (explanation.length() > available) {
                explanation = explanation.substring(0, available);
            }
            String message = prefix + explanation;
            diagnostics.add(new BlendDiagnostic(DiagnosticSeverity.WARN, DIAGNOSTIC_CODE,
                    model.resourceId(), resourceId, "/", message, ""));
            return Optional.empty();
        }
    }
}
