#version 130

#include "/lib/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform float viewWidth;
uniform float viewHeight;

varying vec2 texcoord;

/* RENDERTARGETS: 0,3 */
void main() {
    vec2 texel = vec2(1.0 / max(viewWidth, 1.0), 1.0 / max(viewHeight, 1.0));
    vec4 hdr = texture2D(colortex0, texcoord);
    float centerDepth = texture2D(depthtex0, texcoord).r;
    float occ = 0.0;
#if SSAO_SAMPLES > 0
    for (int i = 0; i < 4; ++i) {
        vec2 off = vec2(float(i - 1), float((i % 2) * 2 - 1)) * texel * 3.0;
        float d = texture2D(depthtex0, texcoord + off).r;
        occ += step(0.0005, centerDepth - d);
    }
    occ = 1.0 - (occ / 4.0) * 0.35;
#else
    occ = 1.0;
#endif
    vec3 color = hdr.rgb * occ;
    float luma = metalityLuminance(color);
    vec3 bloom = max(color - vec3(0.75), vec3(0.0)) * float(BLOOM_ENABLED);
    gl_FragData[0] = vec4(color, max(luma, 1.0));
    gl_FragData[1] = vec4(bloom * BLOOM_STRENGTH, 1.0);
}
