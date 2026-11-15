// composite 3: vertical blur of the bright parts.
fragment float4 main0(
    FullscreenVertex in [[stage_in]],
    texture2d<float> source [[texture(0)]], sampler sourceSampler [[sampler(0)]]
) {
    return float4(gaussian9(source, sourceSampler, in.uv, float2(0.0, 1.5 / float(source.get_height()))), 1.0);
}
