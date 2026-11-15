// Copies the game's depth buffer into a colour image as OpenGL depth (0 at the near plane, 1 at the far
// plane), which is what converted shader packs expect from depthtex0 and depthtex1. The game stores the reverse.
fragment float4 main0(FullscreenVertex in [[stage_in]], depth2d<float> gameDepth [[texture(0)]], sampler gameDepthSampler [[sampler(0)]]) {
    return float4(1.0 - gameDepth.sample(gameDepthSampler, in.uv), 0.0, 0.0, 1.0);
}
