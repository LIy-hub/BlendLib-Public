package com.liy.blendlib.fabric.client.render;

import com.liy.blendlib.core.descriptor.MaterialDefinition;
import java.util.Objects;

/**
 * Compatibility facade for the default reload-time material resolver.
 *
 * <p>Mapping remains deliberately separate from render submit: immutable {@link RenderMaterial}
 * instances are prepared while a generation is built, then submit only consumes those instances.</p>
 */
public final class MaterialRenderMapper {
    /** Fixed alpha cutoff used by the ordinary public 26.1.2 cutout paths. */
    static final double PUBLIC_CUTOUT_THRESHOLD = DefaultRenderLayerProvider.PUBLIC_CUTOUT_THRESHOLD;

    private static final MaterialResolver DEFAULT_RESOLVER = DefaultMaterialResolver.standard2612();

    private MaterialRenderMapper() {
    }

    public static MaterialMapping map(MaterialDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return DEFAULT_RESOLVER.resolve(definition);
    }

    /** Returns the immutable default resolver used by static and skinned handle preparation. */
    static MaterialResolver defaultResolver() {
        return DEFAULT_RESOLVER;
    }

    /** Internal reload-only seam that maps validated descriptor intent to immutable prepared material data. */
    @FunctionalInterface
    interface MaterialResolver {
        MaterialMapping resolve(MaterialDefinition definition);
    }

    /** Internal exact-path policy; it selects no Minecraft render object and never runs in submit. */
    @FunctionalInterface
    interface RenderLayerProvider {
        RenderLayerResolution resolveLayer(MaterialDefinition definition);
    }

    /** Package-private result of the reload-time exact standard layer selection. */
    sealed interface RenderLayerResolution permits RenderLayerResolution.Supported, RenderLayerResolution.Rejected {
        record Supported(RenderLayer layer) implements RenderLayerResolution {
            public Supported {
                layer = Objects.requireNonNull(layer, "layer");
            }
        }

        record Rejected(MaterialRejectionReason reason, String message) implements RenderLayerResolution {
            public Rejected {
                reason = Objects.requireNonNull(reason, "reason");
                message = Objects.requireNonNull(message, "message");
                if (message.isBlank()) {
                    throw new IllegalArgumentException("message must not be blank");
                }
            }
        }
    }

    /** Accepted exact 26.1.2 material-to-layer policy from ADR-014. */
    static final class DefaultRenderLayerProvider implements RenderLayerProvider {
        /** Fixed alpha cutoff used by the ordinary public 26.1.2 cutout paths. */
        static final double PUBLIC_CUTOUT_THRESHOLD = 0.1D;

        /** Shared immutable provider for the default client adapter. */
        static final DefaultRenderLayerProvider INSTANCE = new DefaultRenderLayerProvider();

        private DefaultRenderLayerProvider() {
        }

        @Override
        public RenderLayerResolution resolveLayer(MaterialDefinition definition) {
            Objects.requireNonNull(definition, "definition");
            return switch (definition.mode()) {
                case OPAQUE -> definition.doubleSided()
                        ? rejected(
                                MaterialRejectionReason.OPAQUE_DOUBLE_SIDED_UNSUPPORTED,
                                "Minecraft 26.1.2 exposes no verified ordinary-world double-sided opaque public path")
                        : supported(RenderLayer.SOLID);
                case CUTOUT -> resolveCutout(definition);
                case TRANSLUCENT -> definition.doubleSided()
                        ? supported(RenderLayer.TRANSLUCENT)
                        : rejected(
                                MaterialRejectionReason.TRANSLUCENT_SINGLE_SIDED_UNSUPPORTED,
                                "Minecraft 26.1.2 exposes no verified ordinary-world single-sided translucent public path");
                case ADDITIVE -> rejected(
                        MaterialRejectionReason.ADDITIVE_UNSUPPORTED_IN_P4,
                        "P4 has no additive standard RenderType mapping; the material is rejected rather than remapped");
            };
        }

        private static RenderLayerResolution resolveCutout(MaterialDefinition definition) {
            Double threshold = definition.cutoutThreshold();
            if (threshold != null && Double.compare(threshold, PUBLIC_CUTOUT_THRESHOLD) != 0) {
                return rejected(
                        MaterialRejectionReason.CUTOUT_THRESHOLD_UNSUPPORTED,
                        "Minecraft 26.1.2 ordinary cutout paths have the fixed 0.1 alpha cutoff; "
                                + "the descriptor threshold must be absent or exactly 0.1");
            }
            return supported(RenderLayer.CUTOUT);
        }

        private static RenderLayerResolution.Supported supported(RenderLayer layer) {
            layer = Objects.requireNonNull(layer, "layer");
            return new RenderLayerResolution.Supported(layer);
        }

        private static RenderLayerResolution.Rejected rejected(MaterialRejectionReason reason, String message) {
            return new RenderLayerResolution.Rejected(reason, message);
        }
    }

    /** Default internal resolver that materializes exact layer selections into immutable render data. */
    static final class DefaultMaterialResolver implements MaterialResolver {
        private final RenderLayerProvider renderLayerProvider;

        DefaultMaterialResolver(RenderLayerProvider renderLayerProvider) {
            this.renderLayerProvider = Objects.requireNonNull(renderLayerProvider, "renderLayerProvider");
        }

        static DefaultMaterialResolver standard2612() {
            return new DefaultMaterialResolver(DefaultRenderLayerProvider.INSTANCE);
        }

        @Override
        public MaterialMapping resolve(MaterialDefinition definition) {
            Objects.requireNonNull(definition, "definition");
            RenderLayerResolution resolution = renderLayerProvider.resolveLayer(definition);
            if (resolution instanceof RenderLayerResolution.Rejected rejected) {
                return new MaterialMapping.Rejected(rejected.reason(), rejected.message());
            }
            RenderLayerResolution.Supported supported = (RenderLayerResolution.Supported) resolution;
            return new MaterialMapping.Supported(new RenderMaterial(
                    definition.baseColor(),
                    supported.layer(),
                    definition.emissive(),
                    definition.doubleSided(),
                    0xFFFFFFFF,
                    false));
        }
    }
}
