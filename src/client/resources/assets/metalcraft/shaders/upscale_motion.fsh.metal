// Motion image for MetalFX temporal upscaling: where each pixel's surface was one frame ago, assuming the
// world stood still and only the camera moved. Output is in pixels of the rendered (small) image.
struct MotionUniforms {
    float4x4 inverseViewProjection;     // this frame, without jitter: clip space to camera-relative position
    float4x4 previousViewProjection;    // last frame, without jitter: last frame's camera-relative position to clip space
    float4 cameraDelta;                 // xyz: this frame's camera position minus last frame's; w: sign applied to the result
    float4 size;                        // width and height of the rendered image
};

fragment float4 main0(
    FullscreenVertex in [[stage_in]],
    constant MotionUniforms& motion [[buffer(0)]],
    depth2d<float> depthTexture [[texture(0)]], sampler depthSampler [[sampler(0)]]
) {
    float depth = depthTexture.sample(depthSampler, in.uv);
    float4 position = motion.inverseViewProjection * mc_clip_from_uv(in.uv, depth);
    position /= position.w;
    // Relative to where the camera was a frame ago, the same point is further away by the camera's own step.
    float4 previousClip = motion.previousViewProjection * float4(position.xyz + motion.cameraDelta.xyz, 1.0);
    float2 previousUv = mc_uv_from_clip(previousClip);
    return float4((previousUv - in.uv) * motion.size.xy * motion.cameraDelta.w, 0.0, 0.0);
}
