package com.liy.blendlib.fabric.client.host.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.host.X4HostAdapter;
import com.liy.blendlib.fabric.client.host.X4HostAdapterRegistry;
import com.liy.blendlib.fabric.client.host.X4HostConfigurations;
import com.liy.blendlib.fabric.client.host.X4HostFrame;
import com.liy.blendlib.fabric.client.host.X4HostFrames;
import com.liy.blendlib.fabric.client.host.X4HostIdentity;
import com.liy.blendlib.fabric.client.host.X4HostKind;
import com.liy.blendlib.fabric.client.host.X4HostLeaseDiagnostics;
import com.liy.blendlib.fabric.client.host.X4HostLifecycleState;
import com.liy.blendlib.fabric.client.host.X4HostRegistrationReceipt;
import com.liy.blendlib.fabric.client.host.X4HostSpec;
import com.liy.blendlib.fabric.client.host.X4ManagedHostAdapter;
import com.liy.blendlib.fabric.client.host.X4PreparedSnapshot;
import com.liy.blendlib.fabric.client.render.RenderSubmissionContext;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A package-external consumer proves the public managed registration migration path compiles and runs. */
class X4ExternalManagedRegistrationContractTest {
    @Test
    void externalLegacyAdapterUsesTheVersionedManagedHandleForExactRegistrationAndLifecycleRevocation() {
        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        X4HostIdentity identity = identity("external");
        LegacyAdapter original = new LegacyAdapter(identity);
        try {
            Class<?>[] managedInterfaces = X4ManagedHostAdapter.class.getInterfaces();
            assertEquals(1, managedInterfaces.length);
            assertSame(X4HostAdapter.class, managedInterfaces[0]);
            assertFalse(Arrays.stream(X4ManagedHostAdapter.class.getMethods())
                    .anyMatch(method -> method.getName().equals("freezeAndReserveRegistration")),
                    "the public managed migration handle must not expose the internal reservation SPI");
            IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> registry.register(original));
            assertTrue(rejected.getMessage().contains("X4ManagedHostAdapter"));
            assertEquals(X4HostLifecycleState.CONFIGURING, original.state());

            X4HostAdapter<X4HostFrames.WorldObject> managed = X4ManagedHostAdapter.takeOwnership(original);
            X4HostRegistrationReceipt<X4HostFrames.WorldObject> stale = registry.register(managed);
            managed.close();
            assertEquals(X4HostLifecycleState.CLOSED, original.state());
            assertTrue(registry.registrations().isEmpty());

            LegacyAdapter replacementDelegate = new LegacyAdapter(identity);
            X4HostAdapter<X4HostFrames.WorldObject> replacement = X4ManagedHostAdapter.takeOwnership(replacementDelegate);
            X4HostRegistrationReceipt<X4HostFrames.WorldObject> current = registry.register(replacement);
            assertFalse(registry.retire(stale));
            assertTrue(registry.retire(current));
            assertEquals(X4HostLifecycleState.RETIRED, replacementDelegate.state());
        } finally {
            registry.close();
        }
    }

    @Test
    void nestedManagedOwnershipIsRejectedAndTheOriginalHandleStillRevokesItsExactMembership() {
        X4HostAdapterRegistry registry = new X4HostAdapterRegistry();
        LegacyAdapter raw = new LegacyAdapter(identity("nested"));
        try {
            X4ManagedHostAdapter<X4HostFrames.WorldObject> inner = X4ManagedHostAdapter.takeOwnership(raw);
            assertThrows(IllegalStateException.class, () -> X4ManagedHostAdapter.takeOwnership(raw));
            IllegalStateException nested = assertThrows(
                    IllegalStateException.class, () -> X4ManagedHostAdapter.takeOwnership(inner));
            assertTrue(nested.getMessage().contains("managed"));
            assertThrows(IllegalStateException.class, () -> X4ManagedHostAdapter.takeOwnership(raw),
                    "rejecting a nested handle must not release the inner raw claim");

            X4HostRegistrationReceipt<X4HostFrames.WorldObject> receipt = registry.register(inner);
            inner.close();
            assertTrue(registry.registrations().isEmpty(),
                    "the sole permitted managed owner must revoke its own exact registry membership");
            assertFalse(registry.retire(receipt));
            assertEquals(X4HostLifecycleState.CLOSED, raw.state());
        } finally {
            registry.close();
        }
    }

    @Test
    void failedDrainObservationRollsBackOnlyItsTentativeRawClaim() {
        LegacyAdapter raw = new LegacyAdapter(identity("drain-observation"));
        IllegalStateException observationFailure = new IllegalStateException("drain-observation");
        raw.nextDrainFailure.set(observationFailure);

        assertSame(observationFailure, assertThrows(
                IllegalStateException.class, () -> X4ManagedHostAdapter.takeOwnership(raw)));
        X4ManagedHostAdapter<X4HostFrames.WorldObject> retry = X4ManagedHostAdapter.takeOwnership(raw);
        assertThrows(IllegalStateException.class, () -> X4ManagedHostAdapter.takeOwnership(raw));
        retry.close();
    }

    private static X4HostIdentity identity(String local) {
        return new X4HostIdentity(
                BlendResourceId.parse("x4_external:scope"), BlendResourceId.parse("x4_external:host/" + local));
    }

    private static final class LegacyAdapter implements X4HostAdapter<X4HostFrames.WorldObject> {
        private final X4HostSpec<X4HostFrames.WorldObject> specification;
        private final CompletableFuture<X4HostLifecycleState> completion = new CompletableFuture<>();
        private final AtomicReference<RuntimeException> nextDrainFailure = new AtomicReference<>();
        private X4HostLifecycleState state = X4HostLifecycleState.CONFIGURING;

        private LegacyAdapter(X4HostIdentity identity) {
            specification = new X4HostSpec<>(
                    X4HostKind.WORLD_OBJECT,
                    BlendModelKey.parse("x4_external:models/host"),
                    identity,
                    new X4HostConfigurations.WorldObject(
                            identity.scope(), X4HostConfigurations.DEFAULT_MAXIMUM_BOUNDS_EXTENT));
        }

        @Override
        public synchronized X4HostSpec<X4HostFrames.WorldObject> configure() {
            return specification;
        }

        @Override
        public synchronized X4HostLifecycleState state() {
            return state;
        }

        @Override
        public synchronized X4HostLeaseDiagnostics leaseDiagnostics() {
            return new X4HostLeaseDiagnostics(state, 0L, 0, 0, false, state == X4HostLifecycleState.CLOSED, "");
        }

        @Override
        public CompletableFuture<X4HostLifecycleState> drainCompletion() {
            RuntimeException failure = nextDrainFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return completion;
        }

        @Override
        public synchronized void freeze() {
            if (state != X4HostLifecycleState.CONFIGURING) {
                throw new IllegalStateException("external legacy adapter can freeze only once");
            }
            state = X4HostLifecycleState.FROZEN;
        }

        @Override
        public X4PreparedSnapshot prepare(X4HostFrames.WorldObject frame) {
            throw new UnsupportedOperationException("registration contract does not render");
        }

        @Override
        public void submit(X4PreparedSnapshot prepared, RenderSubmissionContext context) {
            throw new UnsupportedOperationException("registration contract does not render");
        }

        @Override
        public synchronized void retire() {
            state = X4HostLifecycleState.RETIRED;
            completion.complete(state);
        }

        @Override
        public synchronized void close() {
            state = X4HostLifecycleState.CLOSED;
            completion.complete(state);
        }
    }
}
