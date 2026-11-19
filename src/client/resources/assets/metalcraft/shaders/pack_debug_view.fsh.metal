// Shows one of a pack's buffers on screen as it is (METALCRAFT_DEBUG_VIEW), for finding the pass that goes wrong.
// Not-a-number shows as magenta, infinity as cyan, anything negative as blue.
fragment float4 main0(FullscreenVertex in [[stage_in]], texture2d<float> source [[texture(0)]], sampler sourceSampler [[sampler(0)]]) {
    float3 color = source.sample(sourceSampler, in.uv).rgb;
    if (any(isnan(color))) return float4(1.0, 0.0, 1.0, 1.0);
    if (any(isinf(color))) return float4(0.0, 1.0, 1.0, 1.0);
    if (any(color < float3(0.0))) return float4(0.0, 0.0, 1.0, 1.0);
    return float4(color, 1.0);
}
