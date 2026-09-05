package com.liy.blendlib.fabric.client.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.core.model.Bounds;
import com.liy.blendlib.core.model.Transform;
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.render.CullingMetadata;
import com.liy.blendlib.fabric.client.render.ModelRenderHandle;
import com.liy.blendlib.fabric.client.render.ModelRenderSnapshot;
import com.liy.blendlib.fabric.client.render.PreparedRenderPrimitive;
import com.liy.blendlib.fabric.client.render.RenderLayer;
import com.liy.blendlib.fabric.client.render.RenderMaterial;
import com.liy.blendlib.fabric.client.render.RenderVisibility;
import com.liy.blendlib.fabric.client.render.StaticGeometry;
import com.liy.blendlib.fabric.client.render.X6DrawPrimitive;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.junit.jupiter.api.Test;

/** Pins the only overlay value the T3b direct-flat shader is allowed to omit. */
class X7Minecraft2612OverlayContractTest {
    @Test
    void publicOverlayConstantIsThePinnedNeutralPackAndClinitBuildsItThroughPack() throws IOException {
        assertEquals(OverlayTexture.pack(0, 10), OverlayTexture.NO_OVERLAY);
        assertEquals(655360, OverlayTexture.NO_OVERLAY);

        OverlayClassFile parsed = OverlayClassFile.read(classBytes());
        assertTrue(parsed.hasField("NO_OVERLAY", "I"));
        assertTrue(parsed.clinitStoresNoOverlayFromPack(), "<clinit> must use pack(II)I then store NO_OVERLAY:I");
    }

    @Test
    void zeroIsNotTheNeutralDirectFlatOverlayAndIsRejected() {
        assertFalse(StaticDirectOpaqueMarker.isExactNeutralOverlay(0));
        assertTrue(StaticDirectOpaqueMarker.isExactNeutralOverlay(OverlayTexture.NO_OVERLAY));
        Fixture fixture = Fixture.create(0);
        assertThrows(IllegalArgumentException.class, () -> StaticDirectOpaqueMarker.forExact(fixture.snapshot, fixture.draw));
    }

    private static byte[] classBytes() throws IOException {
        try (InputStream input = OverlayTexture.class.getResourceAsStream("OverlayTexture.class")) {
            assertTrue(input != null, "OverlayTexture.class resource");
            return input.readAllBytes();
        }
    }

    private static final class Fixture {
        private final ModelRenderSnapshot snapshot;
        private final X6DrawPrimitive draw;

        private Fixture(ModelRenderSnapshot snapshot, X6DrawPrimitive draw) {
            this.snapshot = snapshot;
            this.draw = draw;
        }

        private static Fixture create(int overlay) {
            StaticGeometry geometry = StaticGeometry.of(
                    new float[] {0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F},
                    new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                    new float[] {0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 1.0F},
                    new int[] {0, 1, 2});
            RenderMaterial material = new RenderMaterial(
                    BlendResourceId.parse("overlay_contract:textures/direct"),
                    RenderLayer.SOLID,
                    false,
                    false,
                    0xFFFFFFFF,
                    false);
            PreparedRenderPrimitive primitive = new PreparedRenderPrimitive(0, geometry, material);
            TestHandle handle = new TestHandle(primitive);
            ModelRenderSnapshot snapshot = new ModelRenderSnapshot(
                    handle,
                    Transform.IDENTITY,
                    0x00F000F0,
                    overlay,
                    0xFFFFFFFF,
                    RenderVisibility.VISIBLE,
                    new CullingMetadata(handle.bounds(), true));
            return new Fixture(snapshot, new X6DrawPrimitive(
                    BlendResourceId.parse("overlay_contract:draw/direct"), primitive, material, 0xFFFFFFFF));
        }
    }

    private record TestHandle(PreparedRenderPrimitive primitive) implements ModelRenderHandle {
        private static final BlendModelKey KEY = BlendModelKey.parse("overlay_contract:models/direct");
        private static final Bounds BOUNDS = new Bounds(Vec3.ZERO, Vec3.ZERO);

        @Override
        public BlendModelKey modelKey() {
            return KEY;
        }

        @Override
        public long generation() {
            return 1L;
        }

        @Override
        public Bounds bounds() {
            return BOUNDS;
        }

        @Override
        public float unitsToBlocksScale() {
            return 1.0F;
        }

        @Override
        public List<PreparedRenderPrimitive> primitives() {
            return List.of(primitive);
        }

        @Override
        public Transform nodeTransform(int nodeIndex) {
            if (nodeIndex != 0) {
                throw new IndexOutOfBoundsException(nodeIndex);
            }
            return Transform.IDENTITY;
        }

        @Override
        public boolean missingModel() {
            return false;
        }
    }

    private static final class OverlayClassFile {
        private final Object[] constantPool;
        private final List<Field> fields;
        private final byte[] clinitCode;

        private OverlayClassFile(Object[] constantPool, List<Field> fields, byte[] clinitCode) {
            this.constantPool = constantPool;
            this.fields = fields;
            this.clinitCode = clinitCode;
        }

        static OverlayClassFile read(byte[] source) throws IOException {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(source))) {
                assertEquals(0xCAFEBABE, input.readInt(), "class magic");
                input.readUnsignedShort();
                input.readUnsignedShort();
                Object[] constantPool = readConstantPool(input);
                input.readUnsignedShort();
                input.readUnsignedShort();
                input.readUnsignedShort();
                skipInterfaces(input);
                List<Field> fields = readFields(input, constantPool);
                byte[] clinit = readClinit(input, constantPool);
                return new OverlayClassFile(constantPool, fields, clinit);
            }
        }

        boolean hasField(String name, String descriptor) {
            return fields.stream().anyMatch(field -> field.name.equals(name) && field.descriptor.equals(descriptor));
        }

        boolean clinitStoresNoOverlayFromPack() {
            if (clinitCode == null) {
                return false;
            }
            for (int offset = 0; offset + 5 <= clinitCode.length; offset++) {
                if ((clinitCode[offset] & 0xFF) != 0xB8) {
                    continue;
                }
                int methodReference = unsignedShort(clinitCode, offset + 1);
                if (!memberReferenceMatches(methodReference, "pack", "(II)I")) {
                    continue;
                }
                int putStaticOffset = offset + 3;
                if ((clinitCode[putStaticOffset] & 0xFF) == 0xB3
                        && memberReferenceMatches(unsignedShort(clinitCode, putStaticOffset + 1), "NO_OVERLAY", "I")) {
                    return true;
                }
            }
            return false;
        }

        private boolean memberReferenceMatches(int index, String name, String descriptor) {
            Object entry = constantPool[index];
            if (!(entry instanceof MemberReference reference)) {
                return false;
            }
            Object nameAndTypeEntry = constantPool[reference.nameAndTypeIndex];
            if (!(nameAndTypeEntry instanceof NameAndType nameAndType)) {
                return false;
            }
            return name.equals(utf8(nameAndType.nameIndex)) && descriptor.equals(utf8(nameAndType.descriptorIndex));
        }

        private String utf8(int index) {
            Object entry = constantPool[index];
            return entry instanceof String value ? value : null;
        }

        private static Object[] readConstantPool(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            Object[] pool = new Object[count];
            for (int index = 1; index < count; index++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> pool[index] = input.readUTF();
                    case 3, 4 -> input.readInt();
                    case 5, 6 -> {
                        input.readLong();
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> input.readUnsignedShort();
                    case 9, 10, 11 -> pool[index] = new MemberReference(input.readUnsignedShort(), input.readUnsignedShort());
                    case 12 -> pool[index] = new NameAndType(input.readUnsignedShort(), input.readUnsignedShort());
                    case 15 -> {
                        input.readUnsignedByte();
                        input.readUnsignedShort();
                    }
                    case 17, 18 -> {
                        input.readUnsignedShort();
                        input.readUnsignedShort();
                    }
                    default -> throw new IOException("Unsupported class constant-pool tag");
                }
            }
            return pool;
        }

        private static void skipInterfaces(DataInputStream input) throws IOException {
            int count = input.readUnsignedShort();
            for (int index = 0; index < count; index++) {
                input.readUnsignedShort();
            }
        }

        private static List<Field> readFields(DataInputStream input, Object[] pool) throws IOException {
            int count = input.readUnsignedShort();
            java.util.ArrayList<Field> fields = new java.util.ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                input.readUnsignedShort();
                String name = utf8(pool, input.readUnsignedShort());
                String descriptor = utf8(pool, input.readUnsignedShort());
                skipAttributes(input, pool, null);
                fields.add(new Field(name, descriptor));
            }
            return List.copyOf(fields);
        }

        private static byte[] readClinit(DataInputStream input, Object[] pool) throws IOException {
            int count = input.readUnsignedShort();
            byte[] result = null;
            for (int index = 0; index < count; index++) {
                input.readUnsignedShort();
                String name = utf8(pool, input.readUnsignedShort());
                input.readUnsignedShort();
                int attributes = input.readUnsignedShort();
                for (int attribute = 0; attribute < attributes; attribute++) {
                    String attributeName = utf8(pool, input.readUnsignedShort());
                    int length = input.readInt();
                    if ("<clinit>".equals(name) && "Code".equals(attributeName)) {
                        input.readUnsignedShort();
                        input.readUnsignedShort();
                        int codeLength = input.readInt();
                        result = input.readNBytes(codeLength);
                        int exceptionCount = input.readUnsignedShort();
                        input.skipNBytes((long) exceptionCount * 8L);
                        skipAttributes(input, pool, null);
                    } else {
                        input.skipNBytes(length);
                    }
                }
            }
            return result;
        }

        private static void skipAttributes(DataInputStream input, Object[] pool, byte[] ignored) throws IOException {
            int count = input.readUnsignedShort();
            for (int index = 0; index < count; index++) {
                input.readUnsignedShort();
                input.skipNBytes(input.readInt());
            }
        }

        private static String utf8(Object[] pool, int index) {
            return pool[index] instanceof String value ? value : null;
        }

        private static int unsignedShort(byte[] bytes, int offset) {
            return (bytes[offset] & 0xFF) << 8 | bytes[offset + 1] & 0xFF;
        }

        private record Field(String name, String descriptor) {
        }

        private record MemberReference(int classIndex, int nameAndTypeIndex) {
        }

        private record NameAndType(int nameIndex, int descriptorIndex) {
        }
    }
}
