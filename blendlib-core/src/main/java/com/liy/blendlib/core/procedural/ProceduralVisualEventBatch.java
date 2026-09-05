package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deep-frozen presentation-only event batch, constructible only after runtime validation. */
public final class ProceduralVisualEventBatch {
    private static final ProceduralVisualEventBatch EMPTY = new ProceduralVisualEventBatch(List.of());
    private final List<ResolvedProceduralVisualEvent> events;

    private ProceduralVisualEventBatch(List<ResolvedProceduralVisualEvent> events) {
        this.events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    public static ProceduralVisualEventBatch empty() {
        return EMPTY;
    }

    public List<ResolvedProceduralVisualEvent> events() {
        return events;
    }

    static Resolution resolveTrusted(
            ProceduralVisualEventTimelineRegistry.Resolution trusted,
            Map<BlendResourceId, Transform> sockets,
            ProceduralDiagnosticCollector diagnostics) {
        Objects.requireNonNull(trusted, "trusted");
        Objects.requireNonNull(sockets, "sockets");
        if (!trusted.accepted()) {
            throw new IllegalArgumentException("rejected event provenance resolution cannot form a visual event batch");
        }
        List<ProceduralVisualEvent> sorted = new ArrayList<>(trusted.events().size());
        for (ProceduralVisualEventTimelineRegistry.TrustedEvent event : trusted.events()) {
            if (event.authority() != trusted.authority()) {
                throw new IllegalArgumentException("visual event resolution contains an event from a foreign registry authority");
            }
            sorted.add(event.event());
        }
        sorted.sort(Comparator.comparingInt(ProceduralVisualEvent::priority)
                .thenComparing(event -> event.id().value())
                .thenComparing(event -> event.provenance().canonicalOrderKey())
                .thenComparing(ProceduralVisualEventBatch::canonicalEventKey));
        LinkedHashMap<EventIdentity, ProceduralVisualEvent> acceptedByIdentity = new LinkedHashMap<>();
        Set<EventIdentity> conflictingIdentities = new HashSet<>();
        for (ProceduralVisualEvent event : sorted) {
            EventIdentity identity = new EventIdentity(event.id(), ProceduralVisualEventReplayIdentity.from(event));
            if (conflictingIdentities.contains(identity)) {
                continue;
            }
            ProceduralVisualEvent existing = acceptedByIdentity.putIfAbsent(identity, event);
            if (existing == null) {
                continue;
            }
            if (canonicalEventKey(existing).equals(canonicalEventKey(event))) {
                diagnostics.add(ProceduralDiagnosticSeverity.INFO, ProceduralDiagnosticCode.VISUAL_EVENT_DEDUPLICATED,
                        event.id(), "duplicate visual event was retained once");
            } else {
                acceptedByIdentity.remove(identity);
                conflictingIdentities.add(identity);
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_CONFLICT,
                        event.id(), "conflicting visual events share one immutable marker identity");
            }
        }
        List<ResolvedProceduralVisualEvent> resolved = new ArrayList<>();
        List<ProceduralVisualEventReplayIdentity> identities = new ArrayList<>();
        for (Map.Entry<EventIdentity, ProceduralVisualEvent> entry : acceptedByIdentity.entrySet()) {
            ProceduralVisualEvent event = entry.getValue();
            if (resolved.size() >= ProceduralLimits.MAX_VISUAL_EVENTS_PER_FRAME) {
                diagnostics.add(ProceduralDiagnosticSeverity.ERROR, ProceduralDiagnosticCode.VISUAL_EVENT_OVERFLOW,
                        event.id(), "visual event frame budget exceeded");
                continue;
            }
            Transform socketTransform = null;
            if (event.payload() instanceof ProceduralVisualEventPayload.SocketEffect socketEffect) {
                socketTransform = sockets.get(socketEffect.socketId());
                if (socketTransform == null) {
                    diagnostics.add(ProceduralDiagnosticSeverity.WARN, ProceduralDiagnosticCode.VISUAL_EVENT_REJECTED,
                            event.id(), "socket effect target is absent or hidden");
                    continue;
                }
            }
            resolved.add(new ResolvedProceduralVisualEvent(event.id(), event.priority(), event.provenance(),
                    event.payload(), socketTransform));
            identities.add(entry.getKey().replayIdentity());
        }
        ProceduralVisualEventBatch batch = resolved.isEmpty() ? EMPTY : new ProceduralVisualEventBatch(resolved);
        return new Resolution(batch, List.copyOf(identities));
    }

    /** Complete semantic comparison key; custom map insertion order can never change batch sort or duplicate outcome. */
    static String canonicalEventKey(ProceduralVisualEvent event) {
        Objects.requireNonNull(event, "event");
        return canonicalText(event.id().value()) + ':' + event.priority() + ':' + event.provenance().canonicalOrderKey()
                + ':' + canonicalPayloadKey(event.payload());
    }

    static String canonicalPayloadKey(ProceduralVisualEventPayload payload) {
        Objects.requireNonNull(payload, "payload");
        return switch (payload) {
            case ProceduralVisualEventPayload.Sound sound -> "sound:" + canonicalText(sound.soundId().value()) + ':'
                    + ProceduralSupport.canonicalFloatKey(sound.volume()) + ':'
                    + ProceduralSupport.canonicalFloatKey(sound.pitch());
            case ProceduralVisualEventPayload.Particle particle -> "particle:" + canonicalText(particle.particleId().value()) + ':'
                    + particle.count() + ':' + canonicalTransformKey(particle.localTransform());
            case ProceduralVisualEventPayload.CameraShake shake -> "shake:"
                    + ProceduralSupport.canonicalFloatKey(shake.amplitude()) + ':'
                    + ProceduralSupport.canonicalFloatKey(shake.durationSeconds());
            case ProceduralVisualEventPayload.TrailStart start -> "trail-start:" + canonicalText(start.trailId().value());
            case ProceduralVisualEventPayload.TrailStop stop -> "trail-stop:" + canonicalText(stop.trailId().value());
            case ProceduralVisualEventPayload.SocketEffect effect -> "socket-effect:" + canonicalText(effect.effectId().value()) + ':'
                    + canonicalText(effect.socketId().value());
            case ProceduralVisualEventPayload.CustomClientEvent custom -> {
                List<Map.Entry<String, String>> fields = new ArrayList<>(custom.fields().entrySet());
                fields.sort(Map.Entry.comparingByKey());
                StringBuilder builder = new StringBuilder("custom:").append(canonicalText(custom.eventId().value()));
                for (Map.Entry<String, String> field : fields) {
                    builder.append(':').append(canonicalText(field.getKey())).append('=').append(canonicalText(field.getValue()));
                }
                yield builder.toString();
            }
        };
    }

    private static String canonicalTransformKey(Transform transform) {
        return ProceduralSupport.canonicalFloatKey(transform.translation().x()) + ':'
                + ProceduralSupport.canonicalFloatKey(transform.translation().y()) + ':'
                + ProceduralSupport.canonicalFloatKey(transform.translation().z()) + ':'
                + ProceduralSupport.canonicalQuaternionKey(transform.rotation()) + ':'
                + ProceduralSupport.canonicalFloatKey(transform.scale().x()) + ':'
                + ProceduralSupport.canonicalFloatKey(transform.scale().y()) + ':'
                + ProceduralSupport.canonicalFloatKey(transform.scale().z());
    }

    private static String canonicalText(String value) {
        return value.length() + ":" + value;
    }

    static Resolution retainUnseen(
            Resolution resolution,
            Set<ProceduralVisualEventReplayIdentity> replayFence,
            ProceduralDiagnosticCollector diagnostics) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(replayFence, "replayFence");
        List<ResolvedProceduralVisualEvent> retainedEvents = new ArrayList<>();
        List<ProceduralVisualEventReplayIdentity> retainedIdentities = new ArrayList<>();
        for (int index = 0; index < resolution.identities().size(); index++) {
            ProceduralVisualEventReplayIdentity identity = resolution.identities().get(index);
            ResolvedProceduralVisualEvent event = resolution.batch().events().get(index);
            if (replayFence.contains(identity)) {
                diagnostics.add(ProceduralDiagnosticSeverity.INFO, ProceduralDiagnosticCode.VISUAL_EVENT_REPLAYED,
                        event.id(), "visual event marker was already published for this runtime scope and loop");
                continue;
            }
            retainedEvents.add(event);
            retainedIdentities.add(identity);
        }
        ProceduralVisualEventBatch retained = retainedEvents.isEmpty() ? EMPTY : new ProceduralVisualEventBatch(retainedEvents);
        return new Resolution(retained, retainedIdentities);
    }

    record Resolution(ProceduralVisualEventBatch batch, List<ProceduralVisualEventReplayIdentity> identities) {
        Resolution {
            batch = Objects.requireNonNull(batch, "batch");
            identities = List.copyOf(Objects.requireNonNull(identities, "identities"));
            if (batch.events().size() != identities.size()) {
                throw new IllegalArgumentException("resolved event identities must match the frozen batch cardinality");
            }
        }
    }

    private record EventIdentity(BlendResourceId id, ProceduralVisualEventReplayIdentity replayIdentity) {
    }
}
