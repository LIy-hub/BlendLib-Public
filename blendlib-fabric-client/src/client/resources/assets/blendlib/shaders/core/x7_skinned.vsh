#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:light.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec2 UV0;

uniform sampler2D Sampler2;
uniform isamplerBuffer SkinSourceBytes;

layout(std140) uniform BonePalette {
    mat4 BonePosition[X7_SKINNED_MAX_BONES];
    mat4 BoneNormal[X7_SKINNED_MAX_BONES];
};

layout(std140) uniform X7SkinnedInstance {
    mat4 ModelView;
    mat3 NormalMatrix;
    vec4 Color;
    // The two packed 16-bit light lanes are carried as exact integer floats. See the Java std140 packer.
    vec4 PackedLight;
};

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 texCoord;

uint readUnsignedByte(int address) {
    // RED8I exposes a signed byte. Converting through a bit mask preserves the exact little-endian source record.
    return uint(texelFetch(SkinSourceBytes, address).r & 255);
}

uint readUnsignedShortLE(int address) {
    return readUnsignedByte(address) | (readUnsignedByte(address + 1) << 8);
}

float readFloatLE(int address) {
    uint bits = readUnsignedByte(address)
            | (readUnsignedByte(address + 1) << 8)
            | (readUnsignedByte(address + 2) << 16)
            | (readUnsignedByte(address + 3) << 24);
    return uintBitsToFloat(bits);
}

void main() {
    int sourceBase = gl_VertexID * 36;
    vec3 sourceNormal = vec3(
            readFloatLE(sourceBase + 24),
            readFloatLE(sourceBase + 28),
            readFloatLE(sourceBase + 32));
    vec3 skinnedPosition = vec3(0.0);
    vec3 skinnedNormal = vec3(0.0);

    for (int influence = 0; influence < 4; influence++) {
        int joint = int(readUnsignedShortLE(sourceBase + influence * 2));
        float weight = readFloatLE(sourceBase + 8 + influence * 4);
        vec4 transformed = BonePosition[joint] * vec4(Position, 1.0);
        skinnedPosition += weight * (transformed.xyz / transformed.w);
        skinnedNormal += weight * mat3(BoneNormal[joint]) * sourceNormal;
    }

    float skinnedNormalLength = length(skinnedNormal);
    vec3 normal = skinnedNormalLength > 1.0e-8 ? skinnedNormal / skinnedNormalLength : vec3(0.0);
    vec4 viewPosition = ModelView * vec4(skinnedPosition, 1.0);
    gl_Position = ProjMat * viewPosition;

    sphericalVertexDistance = fog_spherical_distance(viewPosition.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(viewPosition.xyz);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, NormalMatrix * normal, Color);
    lightMapColor = sample_lightmap(Sampler2, ivec2(PackedLight.xy));
    texCoord = UV0;
}
