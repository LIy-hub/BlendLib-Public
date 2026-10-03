package com.liy.blendlib.core.procedural;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AttachmentTopologyTest {
    @Test
    void sharedNodesLongestPathAndDuplicatesRemainWellDefined() {
        assertEquals(2, AttachmentTopology.validate(Map.of(0, List.of(1, 2, 2), 1, List.of(2), 2, List.of()), Comparator.naturalOrder()));
    }
    @Test
    void rejectsCycleMissingOwnerAndOverlongPath() {
        assertThrows(IllegalArgumentException.class, () -> AttachmentTopology.validate(Map.of(0, List.of(1), 1, List.of(0)), Comparator.naturalOrder()));
        assertThrows(IllegalArgumentException.class, () -> AttachmentTopology.validate(Map.of(0, List.of(1)), Comparator.naturalOrder()));
        var map = new LinkedHashMap<Integer, List<Integer>>();
        for (int n = 0; n < 8; n++) map.put(n, List.of(n + 1));
        map.put(8, List.of());
        assertEquals(8, AttachmentTopology.validate(map, Comparator.naturalOrder()));
        map.put(8, List.of(9)); map.put(9, List.of());
        assertThrows(IllegalArgumentException.class, () -> AttachmentTopology.validate(map, Comparator.naturalOrder()));
    }
    @Test
    void boundsRawEdgesAndOwners() {
        assertThrows(IllegalArgumentException.class, () -> AttachmentTopology.validate(Map.of(), Comparator.<Integer>naturalOrder()));
        assertThrows(IllegalArgumentException.class, () -> AttachmentTopology.validate(
                Map.of(0, Collections.nCopies(4097, 1), 1, List.of()), Comparator.naturalOrder()));
    }
}
