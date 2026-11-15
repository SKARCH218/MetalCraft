// composite 2: horizontal blur of the bright parts.
fragment float4 main0(
    FullscreenVertex in [[stage_in]],
    texture2d<float> source [[texture(0)]], sampler sourceSampler [[sampler(0)]]
) {
    return float4(gaussian9(source, sourceSampler, in.uv, float2(1.5 / float(source.get_width()), 0.0)), 1.0);
}
