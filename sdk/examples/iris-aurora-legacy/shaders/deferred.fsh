#version 130

#include "/lib/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D depthtex0;
uniform vec3 sunPosition;
uniform vec3 skyColor;

varying vec2 texcoord;

/* RENDERTARGETS: 0 */
void main() {
    vec4 base = texture2D(colortex0, texcoord);
    vec3 nrm = texture2D(colortex1, texcoord).rgb * 2.0 - 1.0;
    vec2 lm = texture2D(colortex2, texcoord).rg;
    float depth = texture2D(depthtex0, texcoord).r;
    if (depth >= 1.0) {
        gl_FragData[0] = vec4(skyColor, 1.0);
        return;
    }
    float sunDot = max(dot(normalize(nrm), normalize(sunPosition)), 0.0);
    vec3 lit = base.rgb * (0.28 * skyColor + vec3(1.15, 1.05, 0.92) * sunDot + lm.x * vec3(1.0, 0.72, 0.4));
    gl_FragData[0] = vec4(lit, base.a);
}
