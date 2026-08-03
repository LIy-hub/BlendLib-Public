package com.liy.blendlib.spi.experimental;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Identity-based shared ownership for provider instances across generations and adapter control. */
final class ProviderOwnership {
    private static final Object LOCK = new Object();
    private static final ReferenceQueue<BlendProvider> STALE_PROVIDERS = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, Entry> ENTRIES = new HashMap<>();

    private ProviderOwnership() {
    }

    static Handle acquire(BlendProvider provider) {
        return acquireAll(List.of(provider)).getFirst();
    }

    static List<Handle> acquireAll(Collection<? extends BlendProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        List<BlendProvider> requested = new ArrayList<>();
        IdentityHashMap<BlendProvider, Boolean> distinct = new IdentityHashMap<>();
        for (BlendProvider provider : providers) {
            provider = Objects.requireNonNull(provider, "providers contains null");
            if (distinct.put(provider, Boolean.TRUE) != null) {
                throw new OwnershipConflictException(provider,
                        "The same provider instance was requested more than once");
            }
            requested.add(provider);
        }

        synchronized (LOCK) {
            reapStaleEntries();
            List<Entry> entries = new ArrayList<>(requested.size());
            for (BlendProvider provider : requested) {
                Entry entry = findOrCreate(provider);
                if (entry.closeInvoked) {
                    throw new OwnershipConflictException(provider,
                            "Provider instance is already terminally closed");
                }
                entries.add(entry);
            }

            List<Handle> handles = new ArrayList<>(requested.size());
            for (int index = 0; index < requested.size(); index++) {
                Entry entry = entries.get(index);
                entry.references++;
                handles.add(new Handle(entry, requested.get(index)));
            }
            return List.copyOf(handles);
        }
    }

    private static Entry findOrCreate(BlendProvider provider) {
        IdentityWeakReference lookup = new IdentityWeakReference(provider, null);
        Entry entry = ENTRIES.get(lookup);
        if (entry != null) {
            return entry;
        }
        IdentityWeakReference stored = new IdentityWeakReference(provider, STALE_PROVIDERS);
        entry = new Entry();
        ENTRIES.put(stored, entry);
        return entry;
    }

    private static void reapStaleEntries() {
        IdentityWeakReference stale;
        while ((stale = (IdentityWeakReference) STALE_PROVIDERS.poll()) != null) {
            ENTRIES.remove(stale);
        }
    }

    static final class Handle {
        private final Entry entry;
        private BlendProvider provider;
        private boolean released;

        private Handle(Entry entry, BlendProvider provider) {
            this.entry = entry;
            this.provider = provider;
        }

        Throwable release() {
            BlendProvider providerToClose = null;
            synchronized (LOCK) {
                if (released) {
                    return null;
                }
                released = true;
                entry.references--;
                if (entry.references < 0) {
                    throw new IllegalStateException("Provider ownership reference count underflow");
                }
                if (entry.references == 0 && !entry.closeInvoked) {
                    entry.closeInvoked = true;
                    providerToClose = provider;
                }
                provider = null;
            }

            if (providerToClose == null) {
                return null;
            }
            try {
                ExperimentalControlBoundary.runExternal(providerToClose::close);
                return null;
            } catch (Throwable exception) {
                return exception;
            }
        }
    }

    static final class OwnershipConflictException extends IllegalStateException {
        @SuppressWarnings("serial")
        private static final long serialVersionUID = 1L;
        private final transient BlendProvider provider;

        private OwnershipConflictException(BlendProvider provider, String message) {
            super(message);
            this.provider = Objects.requireNonNull(provider, "provider");
        }

        BlendProvider provider() {
            return provider;
        }
    }

    private static final class Entry {
        private int references;
        private boolean closeInvoked;
    }

    private static final class IdentityWeakReference extends WeakReference<BlendProvider> {
        private final int identityHash;

        private IdentityWeakReference(BlendProvider provider, ReferenceQueue<BlendProvider> queue) {
            super(Objects.requireNonNull(provider, "provider"), queue);
            identityHash = System.identityHashCode(provider);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityWeakReference that)) {
                return false;
            }
            BlendProvider provider = get();
            return provider != null && provider == that.get();
        }
    }
}
