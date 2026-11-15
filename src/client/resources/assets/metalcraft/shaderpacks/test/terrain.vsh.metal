// gbuffers_terrain, vertex stage: the same transform and lighting as the game's terrain shader.
vertex TerrainVarying main0(
    TerrainIn in [[stage_in]],
    constant Projection& projection [[buffer(1)]],
    constant ChunkSection& section [[buffer(2)]],
    constant Globals& globals [[buffer(3)]],
    texture2d<float> lightmap [[texture(2)]],
    sampler lightmapSampler [[sampler(2)]]
) {
    float3 relative = in.Position + float3(section.ChunkPosition - globals.CameraBlockPos) + globals.CameraOffset;
    TerrainVarying out;
    out.position = mc_clip(projection.ProjMat * section.ModelViewMat * float4(relative, 1.0));
    out.color = in.Color * mc_sample_lightmap(lightmap, lightmapSampler, in.UV2);
    out.uv = in.UV0;
    out.blockLight = clamp(float(in.UV2.x) / 240.0, 0.0, 1.0);
    out.relative = relative;
    out.normal = in.McNormal.xyz;
    out.sphericalDistance = length(relative);
    out.cylindricalDistance = max(length(relative.xz), abs(relative.y));
    return out;
}
