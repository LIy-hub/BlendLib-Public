#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:light.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec3 Normal;
in vec2 UV0;

uniform sampler2D Sampler2;

struct X7StaticInstance {
    mat4 ModelView;
    mat3 NormalMatrix;
    vec4 Color;
    // The two packed 16-bit light lanes are carried as exact integer floats. See the Java std140 packer.
    vec4 PackedLight;
};

layout(std140) uniform X7StaticInstances {
    X7StaticInstance Instances[X7_STATIC_MAX_INSTANCES];
};

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 texCoord;

void main() {
    X7StaticInstance instance = Instances[gl_InstanceID];
    vec3 normal = normalize(instance.NormalMatrix * Normal);
    vec4 viewPosition = instance.ModelView * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;

    sphericalVertexDistance = fog_spherical_distance(viewPosition.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(viewPosition.xyz);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, instance.Color);
    lightMapColor = sample_lightmap(Sampler2, ivec2(instance.PackedLight.xy));
    texCoord = UV0;
}
