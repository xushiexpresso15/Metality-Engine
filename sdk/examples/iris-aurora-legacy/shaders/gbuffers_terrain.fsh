#version 130

#include "/lib/common.glsl"

uniform sampler2D gtexture;
uniform sampler2D lightmap;
uniform float alphaTestRef = 0.5;

varying vec2 lmcoord;
varying vec2 texcoord;
varying vec4 glcolor;
varying vec3 worldNormal;

/* RENDERTARGETS: 0,1,2 */
void main() {
    vec4 albedo = texture2D(gtexture, texcoord) * glcolor;
    if (albedo.a < alphaTestRef) {
        discard;
    }
    vec4 lm = texture2D(lightmap, lmcoord);
    gl_FragData[0] = vec4(albedo.rgb * lm.rgb, albedo.a);
    gl_FragData[1] = vec4(worldNormal * 0.5 + 0.5, 1.0);
    gl_FragData[2] = vec4(lmcoord, 0.0, 1.0);
}
