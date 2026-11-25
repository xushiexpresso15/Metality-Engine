#version 130

#include "/lib/common.glsl"

varying vec2 lmcoord;
varying vec2 texcoord;
varying vec4 glcolor;
varying vec3 worldNormal;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    glcolor = gl_Color;
    worldNormal = normalize(gl_NormalMatrix * gl_Normal);
}
