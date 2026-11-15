// MetalCraft shader pack header. The engine puts this in front of every pack shader.
//
// Binding convention
//   buffer(0)  FrameUniforms    every program
//   buffer(1)  Projection       geometry programs (the game's projection for the current draw)
//   buffer(2)  ChunkSection     gbuffers_terrain and shadow (per chunk section)
//   buffer(3)  Globals          geometry programs
//   buffer(4)  Fog              geometry programs
//   buffer(30) vertex data      geometry programs, attributes in the order of the game's vertex format
//   texture(0)/sampler(0)  Sampler0, the block atlas     (geometry programs)
//   texture(2)/sampler(2)  Sampler2, the lightmap        (gbuffers programs)
//   Full-screen passes get their inputs in the order pack.json lists them: texture(i)/sampler(i).
//
// Images keep the game's orientation: row 0 is the bottom of the picture. A geometry program must pass its
// clip position through mc_clip(), and a full-screen pass gets `uv` that already matches the stored rows.
#include <metal_stdlib>
using namespace metal;

struct FrameUniforms {
    float4x4 gbufferModelView;          // camera rotation; positions are relative to the camera
    float4x4 gbufferProjection;
    float4x4 gbufferModelViewInverse;
    float4x4 gbufferProjectionInverse;
    float4x4 shadowModelView;
    float4x4 shadowProjection;
    float4 cameraPosition;              // world position of the camera
    float4 sunDirection;                // unit vector towards the sun, world axes; w = sun angle in radians
    float4 shadowLightDirection;        // unit vector towards the light that casts shadows (sun or moon); w = its strength 0..1
    float4 viewSize;                    // width, height, 1 / width, 1 / height of the game image
    float4 timing;                      // seconds since the pack was loaded, world time 0..24000, rain strength, frame number
    float4 misc;                        // shadow distance, shadow map resolution, 0, 0
    int4 cameraBlockPos;                // floor(cameraPosition)
    float4 cameraOffset;                // cameraBlockPos - cameraPosition
};

// The game's own uniform blocks (std140), as the vanilla shaders declare them.
struct Projection { float4x4 ProjMat; };
struct ChunkSection { float4x4 ModelViewMat; float ChunkVisibility; int2 TextureSize; int3 ChunkPosition; };
struct Globals { int3 CameraBlockPos; float3 CameraOffset; float2 ScreenSize; float GlintAlpha; float GameTime; int MenuBlurRadius; int UseRgss; };
struct Fog { float4 FogColor; float FogEnvironmentalStart; float FogEnvironmentalEnd; float FogRenderDistanceStart; float FogRenderDistanceEnd; float FogSkyEnd; float FogCloudsEnd; };

/// Converts a clip position computed the way the game's shaders do into what Metal expects.
static inline float4 mc_clip(float4 position) {
    position.y = -position.y;
    return position;
}

/// The game's clip-space position of a pixel of a full-screen pass (x, y in -1..1), given its uv and depth.
static inline float4 mc_clip_from_uv(float2 uv, float depth) {
    return float4(uv * 2.0 - 1.0, depth, 1.0);
}

/// Where a game clip-space position lands in an image, as uv.
static inline float2 mc_uv_from_clip(float4 clip) {
    return clip.xy / clip.w * 0.5 + 0.5;
}

static inline float mc_linear_fog(float distance, float start, float end) {
    return distance <= start ? 0.0 : (distance >= end ? 1.0 : (distance - start) / (end - start));
}

static inline float4 mc_apply_fog(float4 color, float sphericalDistance, float cylindricalDistance, Fog fog) {
    float amount = max(mc_linear_fog(sphericalDistance, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd),
                       mc_linear_fog(cylindricalDistance, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd));
    return float4(mix(color.rgb, fog.FogColor.rgb, amount * fog.FogColor.a), color.a);
}

static inline float4 mc_sample_lightmap(texture2d<float> lightmap, sampler lightmapSampler, int2 uv) {
    float2 coord = clamp(float2(uv) / 256.0 + 0.5 / 16.0, float2(0.5 / 16.0), float2(15.5 / 16.0));
    return lightmap.sample(lightmapSampler, coord, level(0));
}

struct FullscreenVertex {
    float4 position [[position]];
    float2 uv;
};
