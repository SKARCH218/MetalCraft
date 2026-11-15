// final: adds the bloom and applies a filmic tone curve.
fragment float4 main0(
    FullscreenVertex in [[stage_in]],
    texture2d<float> scene [[texture(0)]], sampler sceneSampler [[sampler(0)]],
    texture2d<float> bloom [[texture(1)]], sampler bloomSampler [[sampler(1)]]
) {
    float3 color = scene.sample(sceneSampler, in.uv).rgb + bloom.sample(bloomSampler, in.uv).rgb * 0.45;
    // The game's colours are display-encoded: work on linear light, then encode again.
    float3 linearColor = pow(max(color, 0.0), float3(2.2)) * 1.12;
    float3 mapped = (linearColor * (2.51 * linearColor + 0.03)) / (linearColor * (2.43 * linearColor + 0.59) + 0.14);
    return float4(pow(saturate(mapped), float3(1.0 / 2.2)), 1.0);
}
