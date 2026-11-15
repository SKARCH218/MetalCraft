// composite 1: keeps the bright parts of the image, at a quarter of the resolution.
fragment float4 main0(
    FullscreenVertex in [[stage_in]],
    constant FrameUniforms& frame [[buffer(0)]],
    texture2d<float> scene [[texture(0)]], sampler sceneSampler [[sampler(0)]]
) {
    float2 pixel = frame.viewSize.zw;
    float3 sum = float3(0.0);
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            float3 color = scene.sample(sceneSampler, in.uv + (float2(x, y) * 2.0 - 1.0) * pixel).rgb;
            float brightness = max(color.r, max(color.g, color.b));
            sum += color * smoothstep(0.72, 1.0, brightness);
        }
    }
    return float4(sum * 0.25, 1.0);
}
