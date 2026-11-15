// deferred: darkens what the shadow map says the light does not reach.
fragment float4 main0(
    FullscreenVertex in [[stage_in]],
    constant FrameUniforms& frame [[buffer(0)]],
    texture2d<float> scene [[texture(0)]], sampler sceneSampler [[sampler(0)]],
    texture2d<float> normals [[texture(1)]], sampler normalSampler [[sampler(1)]],
    depth2d<float> depthTexture [[texture(2)]], sampler depthSampler [[sampler(2)]],
    depth2d<float> shadowMap [[texture(3)]], sampler shadowSampler [[sampler(3)]]
) {
    float4 color = scene.sample(sceneSampler, in.uv);
    float depth = depthTexture.sample(depthSampler, in.uv);
    float lightStrength = frame.shadowLightDirection.w;
    // The game's depth runs from 1 (near) to 0 (far); 0 is the sky.
    if (depth <= 0.0 || lightStrength <= 0.0) {
        return color;
    }

    float4 view = frame.gbufferProjectionInverse * mc_clip_from_uv(in.uv, depth);
    view /= view.w;
    float3 relative = (frame.gbufferModelViewInverse * float4(view.xyz, 1.0)).xyz;

    float3 light = frame.shadowLightDirection.xyz;
    float4 packedNormal = normals.sample(normalSampler, in.uv);
    float facing = 1.0;
    float blockLight = 0.0;
    if (packedNormal.a > 0.5) {
        float3 normal = normalize(packedNormal.xyz * 2.0 - 1.0);
        blockLight = saturate((packedNormal.a - 0.51) / 0.49);
        facing = dot(normal, light);
        // Faces turned away from the light are already dark from the game's own face shading.
        if (facing <= 0.0) {
            return color;
        }
        relative += normal * 0.08;
    }

    float4 shadowClip = frame.shadowProjection * frame.shadowModelView * float4(relative, 1.0);
    float2 shadowUv = mc_uv_from_clip(shadowClip);
    if (any(shadowUv < 0.0) || any(shadowUv > 1.0) || shadowClip.z >= 1.0) {
        return color;
    }

    float texel = 1.0 / frame.misc.y;
    // The shadow map's depth spans about 512 blocks. The bias is a few hundredths of a block for surfaces
    // facing the light and grows as they turn away from it; larger values detach shadows from what casts them.
    float slope = sqrt(max(1.0 - facing * facing, 0.0)) / max(facing, 0.05);
    float biasBlocks = packedNormal.a > 0.5 ? clamp(0.03 + 0.12 * slope, 0.03, 0.35) : 0.15;
    float bias = biasBlocks / 512.0;
    float lit = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float stored = shadowMap.sample(shadowSampler, shadowUv + float2(x, y) * texel);
            lit += stored < shadowClip.z - bias ? 0.0 : 1.0;
        }
    }
    lit /= 9.0;

    // Fade out towards the edge of the shadow map instead of ending in a hard line.
    float2 fromCentre = abs(shadowUv * 2.0 - 1.0);
    float edgeFade = 1.0 - smoothstep(0.85, 1.0, max(fromCentre.x, fromCentre.y));
    float darkness = 0.45 * lightStrength * edgeFade * smoothstep(0.0, 0.15, facing);
    // A shadow only takes away sunlight: next to a torch or another light block the ground stays lit.
    darkness *= 1.0 - smoothstep(0.25, 0.85, blockLight);
    color.rgb *= 1.0 - darkness * (1.0 - lit);
    return color;
}
