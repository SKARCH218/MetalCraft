// Milestone M1 window probe: one triangle with a colour per corner, no vertex buffer.
#include <metal_stdlib>
using namespace metal;

struct ProbeVertex {
    float4 position [[position]];
    float3 color;
};

vertex ProbeVertex probe_vertex(uint vertexId [[vertex_id]]) {
    const float2 positions[3] = { float2(0.0, 0.6), float2(-0.6, -0.6), float2(0.6, -0.6) };
    const float3 colors[3] = { float3(1.0, 0.0, 0.0), float3(0.0, 1.0, 0.0), float3(0.0, 0.0, 1.0) };
    ProbeVertex out;
    out.position = float4(positions[vertexId], 0.0, 1.0);
    out.color = colors[vertexId];
    return out;
}

fragment float4 probe_fragment(ProbeVertex in [[stage_in]]) {
    return float4(in.color, 1.0);
}
