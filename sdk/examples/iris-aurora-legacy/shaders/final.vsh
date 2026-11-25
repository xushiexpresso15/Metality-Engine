#version 130

#include "/lib/common.glsl"

varying vec2 texcoord;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}
