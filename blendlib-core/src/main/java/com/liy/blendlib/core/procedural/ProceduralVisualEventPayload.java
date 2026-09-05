package com.liy.blendlib.core.procedural;

import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Transform;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Typed, bounded presentation data. None of these values is a gameplay acknowledgement or wire-format data. */
public sealed interface ProceduralVisualEventPayload permits ProceduralVisualEventPayload.Sound,
        ProceduralVisualEventPayload.Particle, ProceduralVisualEventPayload.CameraShake,
        ProceduralVisualEventPayload.TrailStart, ProceduralVisualEventPayload.TrailStop,
        ProceduralVisualEventPayload.SocketEffect, ProceduralVisualEventPayload.CustomClientEvent {

    ProceduralVisualEventType type();

    record Sound(BlendResourceId soundId, float volume, float pitch) implements ProceduralVisualEventPayload {
        public Sound {
            soundId = Objects.requireNonNull(soundId, "soundId");
            ProceduralSupport.requireId(soundId, "sound id");
            if (!Float.isFinite(volume) || volume < 0.0F || volume > 4.0F
                    || !Float.isFinite(pitch) || pitch <= 0.0F || pitch > 4.0F) {
                throw new IllegalArgumentException("sound volume and pitch are outside bounded presentation ranges");
            }
        }

        @Override
        public ProceduralVisualEventType type() {
            return ProceduralVisualEventType.SOUND;
        }
    }

    record Particle(BlendResourceId particleId, int count, Transform localTransform) implements ProceduralVisualEventPayload {
        public Particle {
            particleId = Objects.requireNonNull(particleId, "particleId");
            ProceduralSupport.requireId(particleId, "particle id");
            if (count < 1 || count > 256) {
                throw new IllegalArgumentException("particle count is outside X3 bounds");
            }
            localTransform = Objects.requireNonNull(localTransform, "localTransform");
        }

        @Override
        public ProceduralVisualEventType type() {
            return ProceduralVisualEventType.PARTICLE;
        }
    }

    record CameraShake(float amplitude, float durationSeconds) implements ProceduralVisualEventPayload {
        public CameraShake {
            if (!Float.isFinite(amplitude) || amplitude < 0.0F || amplitude > 8.0F
                    || !Float.isFinite(durationSeconds) || durationSeconds <= 0.0F || durationSeconds > 10.0F) {
                throw new IllegalArgumentException("camera shake is outside bounded presentation ranges");
            }
        }

        @Override
        public ProceduralVisualEventType type() {
            return ProceduralVisualEventType.CAMERA_SHAKE;
        }
    }

    record TrailStart(BlendResourceId trailId) implements ProceduralVisualEventPayload {
        public TrailStart {
            trailId = Objects.requireNonNull(trailId, "trailId");
            ProceduralSupport.requireId(trailId, "trail id");
        }

        @Override
        public ProceduralVisualEventType type() {
            return ProceduralVisualEventType.TRAIL_START;
        }
    }

    record TrailStop(BlendResourceId trailId) implements ProceduralVisualEventPayload {
        public TrailStop {
            trailId = Objects.requireNonNull(trailId, "trailId");
            ProceduralSupport.requireId(trailId, "trail id");
        }

        @Override
        public ProceduralVisualEventType type() {
            return ProceduralVisualEventType.TRAIL_STOP;
        }
    }

    record SocketEffect(BlendResourceId effectId, BlendResourceId socketId) implements ProceduralVisualEventPayload {
        public SocketEffect {
            effectId = Objects.requireNonNull(effectId, "effectId");
            socketId = Objects.requireNonNull(socketId, "socketId");
            ProceduralSupport.requireId(effectId, "socket effect id");
            ProceduralSupport.requireId(socketId, "socket event socket id");
        }

        @Override
        public ProceduralVisualEventType type() {
            return ProceduralVisualEventType.SOCKET_EFFECT;
        }
    }

    record CustomClientEvent(BlendResourceId eventId, Map<String, String> fields) implements ProceduralVisualEventPayload {
        public CustomClientEvent {
            eventId = Objects.requireNonNull(eventId, "eventId");
            ProceduralSupport.requireId(eventId, "custom event id");
            Objects.requireNonNull(fields, "fields");
            if (fields.size() > ProceduralLimits.MAX_CUSTOM_EVENT_FIELDS) {
                throw new IllegalArgumentException("custom event field count exceeds X3 bounds");
            }
            List<CustomField> checkedFields = new ArrayList<>(fields.size());
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String key = ProceduralSupport.requireBoundedText(Objects.requireNonNull(entry.getKey(), "custom field key"),
                        ProceduralLimits.MAX_CUSTOM_EVENT_FIELD_UTF16_CODE_UNITS, "custom field key");
                String value = ProceduralSupport.requireBoundedText(Objects.requireNonNull(entry.getValue(), "custom field value"),
                        ProceduralLimits.MAX_CUSTOM_EVENT_FIELD_UTF16_CODE_UNITS, "custom field value");
                if (key.isBlank()) {
                    throw new IllegalArgumentException("custom event fields must have unique non-blank bounded keys");
                }
                checkedFields.add(new CustomField(key, value));
            }
            checkedFields.sort(Comparator.comparing(CustomField::key));
            LinkedHashMap<String, String> copied = new LinkedHashMap<>();
            for (CustomField field : checkedFields) {
                if (copied.putIfAbsent(field.key(), field.value()) != null) {
                    throw new IllegalArgumentException("custom event fields must have unique non-blank bounded keys");
                }
            }
            fields = Collections.unmodifiableMap(copied);
        }

        @Override
        public ProceduralVisualEventType type() {
            return ProceduralVisualEventType.CUSTOM_CLIENT_EVENT;
        }

        private record CustomField(String key, String value) {
        }
    }
}
