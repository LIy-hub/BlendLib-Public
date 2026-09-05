package com.liy.blendlib.fabric.v262.host;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.api.HostKind;
import com.liy.blendlib.api.HostRegistrationSpec;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262Diagnostic;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262DiagnosticCode;
import com.liy.blendlib.fabric.v262.diagnostic.Fabric262PlatformException;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Translates ordinary entity, block-entity, and item registrations at the Fabric 26.2 boundary.
 *
 * <p><strong>Platform target:</strong> Fabric on Minecraft 26.2. It verifies that the opaque host
 * accepted by the stable API is the native type appropriate for its semantic category, resolves a
 * registered native identity, and immediately reduces it to {@link BlendResourceId}. The owning
 * {@link Fabric262HostRenderDispatcher} installs the corresponding public native renderer after
 * this translation succeeds. Neither collaborator reflects on host objects.</p>
 */
public final class Fabric262HostRegistrationTranslator {
    /**
     * Converts one stable semantic registration to the independent Fabric host-binding carrier.
     *
     * @param specification complete stable registration specification
     * @return immutable native-host/model binding
     * @throws Fabric262PlatformException if host kind and native token do not agree
     */
    public Fabric262HostBinding translate(HostRegistrationSpec<?> specification) {
        return translateInstalled(specification).binding();
    }

    /**
     * Translates one specification while retaining its full typed source for the production
     * extraction dispatcher. The returned carrier is package-private by design so public binding
     * metadata cannot expose or rewrite the opaque registration token.
     */
    Fabric262RegisteredHost translateInstalled(HostRegistrationSpec<?> specification) {
        HostRegistrationSpec<?> checked = Objects.requireNonNull(specification, "specification");
        Fabric262HostTarget target = switch (checked.hostKind()) {
            case ENTITY -> entityTarget(checked.host());
            case BLOCK_ENTITY -> blockEntityTarget(checked.host());
            case ITEM -> itemTarget(checked.host());
        };
        return new Fabric262RegisteredHost(target, checked);
    }

    private static Fabric262HostTarget entityTarget(Object host) {
        if (!(host instanceof EntityType<?> entityType)) {
            throw wrongHost(HostKind.ENTITY, host);
        }
        return new Fabric262HostTarget(HostKind.ENTITY, idOf(BuiltInRegistries.ENTITY_TYPE.getKey(entityType), HostKind.ENTITY));
    }

    private static Fabric262HostTarget blockEntityTarget(Object host) {
        if (!(host instanceof BlockEntityType<?> blockEntityType)) {
            throw wrongHost(HostKind.BLOCK_ENTITY, host);
        }
        return new Fabric262HostTarget(
                HostKind.BLOCK_ENTITY,
                idOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType), HostKind.BLOCK_ENTITY));
    }

    private static Fabric262HostTarget itemTarget(Object host) {
        if (!(host instanceof Item item)) {
            throw wrongHost(HostKind.ITEM, host);
        }
        return new Fabric262HostTarget(HostKind.ITEM, idOf(BuiltInRegistries.ITEM.getKey(item), HostKind.ITEM));
    }

    private static BlendResourceId idOf(Identifier identifier, HostKind kind) {
        if (identifier == null) {
            throw new Fabric262PlatformException(Fabric262Diagnostic.error(
                    Fabric262DiagnosticCode.HOST_TRANSLATION_FAILURE,
                    "Fabric " + kind + " host is not registered and cannot receive a renderer binding"));
        }
        return BlendResourceId.of(identifier.getNamespace(), identifier.getPath());
    }

    private static Fabric262PlatformException wrongHost(HostKind expected, Object actual) {
        String actualType = actual == null ? "null" : actual.getClass().getName();
        return new Fabric262PlatformException(Fabric262Diagnostic.error(
                Fabric262DiagnosticCode.HOST_TRANSLATION_FAILURE,
                "Fabric " + expected + " registration requires its matching native token, received " + actualType));
    }
}
