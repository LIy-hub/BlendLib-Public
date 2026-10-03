package com.liy.blendlib.fabric.client.render;

import static org.junit.jupiter.api.Assertions.*;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlendLibModelSkinsTest {
    private static final BlendModelKey MODEL = BlendModelKey.parse("skins:model");
    private static final BlendResourceId SKIN = BlendResourceId.parse("skins:blue");
    private static final BlendResourceId TEXTURE = BlendResourceId.parse("skins:textures/blue.png");

    @Test void deepCopiesDefinitionsAndSnapshotsAndAllowsExactIdempotence() {
        var registry = new BlendLibModelSkins.Registry();
        var slots = new HashMap<>(Map.of("Body", TEXTURE));
        var definitions = new HashMap<>(Map.of(SKIN, (Map<String, BlendResourceId>) slots));
        registry.register(MODEL, definitions);
        slots.clear();
        definitions.clear();
        var equivalent = Map.of(SKIN, Map.of("Body", TEXTURE));
        registry.register(MODEL, equivalent);
        var snapshot = registry.snapshotForReload();
        assertEquals(equivalent, snapshot.get(MODEL));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get(MODEL).clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get(MODEL).get(SKIN).clear());
        registry.register(MODEL, equivalent);
        assertEquals(snapshot, registry.snapshotForReload());
        assertThrows(IllegalStateException.class, () -> registry.register(BlendModelKey.parse("skins:late"), equivalent));
        assertThrows(IllegalStateException.class, () -> registry.register(MODEL,
                Map.of(SKIN, Map.of("body", TEXTURE))));
        assertEquals(equivalent, snapshot.get(MODEL), "Slot spelling stays exact");
    }

    @Test void conflictsAreRejectedAtomicallyBeforeFreezeAndModelsAreIndependent() {
        var registry = new BlendLibModelSkins.Registry();
        var original = Map.of(SKIN, Map.of("body", TEXTURE));
        registry.register(MODEL, original);
        assertThrows(IllegalStateException.class, () -> registry.register(MODEL,
                Map.of(SKIN, Map.of("body", BlendResourceId.parse("skins:textures/gold.png")))));
        var other = BlendModelKey.parse("skins:other");
        registry.register(other, Map.of(SKIN, Map.of("head", TEXTURE)));
        var snapshot = registry.snapshotForReload();
        assertEquals(original, snapshot.get(MODEL));
        assertEquals(Map.of("head", TEXTURE), snapshot.get(other).get(SKIN));
    }

    @Test void modelSkinAndReplacementBoundsAcceptExactLimitAndRejectNextEntry() {
        var registry = new BlendLibModelSkins.Registry();
        var definitions = Map.of(SKIN, Map.of("body", TEXTURE));
        for (int i = 0; i < BlendLibModelSkins.MAX_MODELS; i++) {
            registry.register(BlendModelKey.parse("skins:model_" + i), definitions);
        }
        assertThrows(IllegalStateException.class, () -> registry.register(MODEL, definitions));
        registry.register(BlendModelKey.parse("skins:model_0"), definitions);
        assertEquals(128, registry.snapshotForReload().size());

        var slots = new LinkedHashMap<String, BlendResourceId>();
        for (int i = 0; i < BlendLibModelSkins.MAX_REPLACEMENTS_PER_SKIN; i++) slots.put("slot_" + i, TEXTURE);
        var manySkins = new LinkedHashMap<BlendResourceId, Map<String, BlendResourceId>>();
        for (int i = 0; i < BlendLibModelSkins.MAX_SKINS_PER_MODEL; i++)
            manySkins.put(BlendResourceId.parse("skins:skin_" + i), slots);
        var bounded = new BlendLibModelSkins.Registry();
        bounded.register(MODEL, manySkins);
        var snapshot = bounded.snapshotForReload();
        assertEquals(32, snapshot.get(MODEL).size());
        assertEquals(64, snapshot.get(MODEL).values().iterator().next().size());
        manySkins.put(SKIN, Map.of("body", TEXTURE));
        assertThrows(IllegalArgumentException.class, () -> new BlendLibModelSkins.Registry().register(MODEL, manySkins));
        slots.put("overflow", TEXTURE);
        assertThrows(IllegalArgumentException.class, () -> new BlendLibModelSkins.Registry().register(MODEL, Map.of(SKIN, slots)));
        assertEquals(64, snapshot.get(MODEL).values().iterator().next().size());
    }

    @Test void rejectsNullEmptyAndBlankDefinitionsWithoutFreezingOrPartiallyRegistering() {
        var registry = new BlendLibModelSkins.Registry();
        var valid = Map.of(SKIN, Map.of("body", TEXTURE));
        assertThrows(NullPointerException.class, () -> registry.register(null, valid));
        assertThrows(NullPointerException.class, () -> registry.register(MODEL, null));
        assertThrows(IllegalArgumentException.class, () -> registry.register(MODEL, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(MODEL, Map.of(SKIN, Map.of())));
        for (String blank : new String[]{"", " ", "\t\n"})
            assertThrows(IllegalArgumentException.class, () -> registry.register(MODEL, Map.of(SKIN, Map.of(blank, TEXTURE))));
        var nullName = new HashMap<BlendResourceId, Map<String, BlendResourceId>>();
        nullName.put(null, Map.of("body", TEXTURE));
        assertThrows(NullPointerException.class, () -> registry.register(MODEL, nullName));
        nullName.clear();
        nullName.put(SKIN, null);
        assertThrows(NullPointerException.class, () -> registry.register(MODEL, nullName));
        var nullSlot = new HashMap<String, BlendResourceId>();
        nullSlot.put(null, TEXTURE);
        assertThrows(NullPointerException.class, () -> registry.register(MODEL, Map.of(SKIN, nullSlot)));
        nullSlot.clear();
        nullSlot.put("body", null);
        assertThrows(NullPointerException.class, () -> registry.register(MODEL, Map.of(SKIN, nullSlot)));
        registry.register(MODEL, valid);
        assertEquals(Map.of(MODEL, valid), registry.snapshotForReload());
    }

    @Test void firstEmptySnapshotStillFreezesNewRegistration() {
        var registry = new BlendLibModelSkins.Registry();
        assertTrue(registry.snapshotForReload().isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.register(MODEL, Map.of(SKIN, Map.of("body", TEXTURE))));
    }
}
