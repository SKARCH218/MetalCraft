// shadow, vertex stage. ChunkSection.ModelViewMat holds the light's view matrix during this pass.
vertex ShadowVarying main0(
    TerrainIn in [[stage_in]],
    constant FrameUniforms& frame [[buffer(0)]],
    constant ChunkSection& section [[buffer(2)]]
) {
    float3 relative = in.Position + float3(section.ChunkPosition - frame.cameraBlockPos.xyz) + frame.cameraOffset.xyz;
    ShadowVarying out;
    out.position = mc_clip(frame.shadowProjection * section.ModelViewMat * float4(relative, 1.0));
    out.uv = in.UV0;
    return out;
}
