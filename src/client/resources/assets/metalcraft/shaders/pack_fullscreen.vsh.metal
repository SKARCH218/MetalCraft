// Vertex shader of every full-screen pass: one triangle that covers the target.
vertex FullscreenVertex main0(uint vertexId [[vertex_id]]) {
    float2 corner = float2((vertexId << 1) & 2, vertexId & 2);
    FullscreenVertex out;
    // Row 0 of the image is at the top of Metal's clip space.
    out.position = float4(corner.x * 2.0 - 1.0, 1.0 - corner.y * 2.0, 0.0, 1.0);
    out.uv = corner;
    return out;
}
