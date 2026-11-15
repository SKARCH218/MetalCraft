// shadow, fragment stage: only depth is kept. Holes in leaves and similar blocks let the light through.
fragment void main0(ShadowVarying in [[stage_in]], texture2d<float> atlas [[texture(0)]], sampler atlasSampler [[sampler(0)]]) {
    if (atlas.sample(atlasSampler, in.uv, level(0)).a < 0.5) {
        discard_fragment();
    }
}
