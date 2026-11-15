// Shared by the test pack's shaders: the terrain vertex layout and what the vertex stage hands on.

struct TerrainIn {
    float3 Position [[attribute(0)]];
    float4 Color [[attribute(1)]];
    float2 UV0 [[attribute(2)]];
    int2 UV2 [[attribute(3)]];
};

struct TerrainVarying {
    float4 position [[position]];
    float4 color;
    float2 uv;
    float3 relative;        // position relative to the camera, world axes
    float sphericalDistance;
    float cylindricalDistance;
    float blockLight;       // light from torches and other blocks, 0..1
};

struct ShadowVarying {
    float4 position [[position]];
    float2 uv;
};

static inline float3 gaussian9(texture2d<float> source, sampler sourceSampler, float2 uv, float2 step) {
    const float weights[5] = { 0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216 };
    float3 sum = source.sample(sourceSampler, uv).rgb * weights[0];
    for (int i = 1; i < 5; i++) {
        sum += source.sample(sourceSampler, uv + step * float(i)).rgb * weights[i];
        sum += source.sample(sourceSampler, uv - step * float(i)).rgb * weights[i];
    }
    return sum;
}
