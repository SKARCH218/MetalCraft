// gbuffers_terrain, fragment stage. colortex0: the lit, fogged colour. colortex1: the surface normal.
struct TerrainOut {
    float4 color [[color(0)]];
    float4 normal [[color(1)]];
};

fragment TerrainOut main0(
    TerrainVarying in [[stage_in]],
    constant ChunkSection& section [[buffer(2)]],
    constant Fog& fog [[buffer(4)]],
    texture2d<float> atlas [[texture(0)]],
    sampler atlasSampler [[sampler(0)]]
) {
    // Sample texel centres so blocks stay crisp up close, with the original gradients for mip selection.
    float2 atlasSize = float2(section.TextureSize);
    float2 snapped = (floor(in.uv * atlasSize) + 0.5) / atlasSize;
    float4 color = atlas.sample(atlasSampler, snapped, gradient2d(dfdx(in.uv), dfdy(in.uv))) * in.color;
    color = mix(fog.FogColor * float4(1.0, 1.0, 1.0, color.a), color, section.ChunkVisibility);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard_fragment();
    }
#endif

    // Faces drawn from both sides (grass, flowers) show the side that looks at the camera.
    float3 normal = normalize(in.normal);
    if (dot(normal, in.relative) > 0.0) {
        normal = -normal;
    }

    TerrainOut out;
    out.color = mc_apply_fog(color, in.sphericalDistance, in.cylindricalDistance, fog);
    // Alpha above 0.5 marks a pixel that has a normal; the rest of its range carries the block light.
    out.normal = float4(normal * 0.5 + 0.5, 0.51 + 0.49 * in.blockLight);
    return out;
}
