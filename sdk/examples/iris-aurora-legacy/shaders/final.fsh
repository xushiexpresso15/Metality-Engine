#version 130

#include "/lib/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex3;

varying vec2 texcoord;

/* RENDERTARGETS: 0 */
void main() {
    vec3 hdr = texture2D(colortex0, texcoord).rgb;
#if BLOOM_ENABLED > 0
    hdr += texture2D(colortex3, texcoord).rgb;
#endif
    vec3 mapped = metalityAcesToneMap(hdr * TONEMAP_EXPOSURE);
    gl_FragData[0] = vec4(mapped, 1.0);
}
