package com.liy.blendlib.core.procedural;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Process-wide ownership index for the client resource-generation namespace.
 *
 * <p>The production client has one resource-generation owner per class loader. X3 therefore permits one active,
 * complete attachment graph for each generation number. Entries are weak as a last-resort leak guard, while normal
 * reload integration must explicitly close the retired graph. Every call is made while the caller holds its graph
 * lock; this class never calls back into a graph, so the only lock order is graph then registry.</p>
 */
final class ProceduralAttachmentGenerationClaims {
    private static final Object LOCK = new Object();
    private static final ReferenceQueue<ProceduralAttachmentGraph> STALE_GRAPHS = new ReferenceQueue<>();
    private static final Map<Long, Claim> CLAIMS = new LinkedHashMap<>();

    private ProceduralAttachmentGenerationClaims() {
    }

    static Claim claim(long generation, ProceduralAttachmentGraph graph) {
        Objects.requireNonNull(graph, "graph");
        synchronized (LOCK) {
            drainStaleClaims();
            Claim existing = CLAIMS.get(generation);
            if (existing != null) {
                ProceduralAttachmentGraph current = existing.get();
                if (current == graph) {
                    return existing;
                }
                if (current != null) {
                    throw new IllegalStateException(
                            "resource generation already has a different active attachment graph");
                }
                CLAIMS.remove(generation, existing);
            }
            if (CLAIMS.size() >= ProceduralLimits.MAX_ACTIVE_ATTACHMENT_GRAPH_CLAIMS) {
                throw new IllegalStateException("active attachment graph generation claim capacity is exhausted");
            }
            Claim created = new Claim(generation, graph, STALE_GRAPHS);
            CLAIMS.put(generation, created);
            return created;
        }
    }

    static void release(long generation, Claim claim) {
        Objects.requireNonNull(claim, "claim");
        synchronized (LOCK) {
            drainStaleClaims();
            CLAIMS.remove(generation, claim);
            claim.clear();
        }
    }

    static int activeClaimCount() {
        synchronized (LOCK) {
            drainStaleClaims();
            return CLAIMS.size();
        }
    }

    private static void drainStaleClaims() {
        Claim stale;
        while ((stale = (Claim) STALE_GRAPHS.poll()) != null) {
            CLAIMS.remove(stale.generation, stale);
        }
        CLAIMS.entrySet().removeIf(entry -> entry.getValue().get() == null);
    }

    static final class Claim extends WeakReference<ProceduralAttachmentGraph> {
        private final long generation;

        private Claim(
                long generation,
                ProceduralAttachmentGraph graph,
                ReferenceQueue<ProceduralAttachmentGraph> queue) {
            super(graph, queue);
            this.generation = generation;
        }
    }
}
