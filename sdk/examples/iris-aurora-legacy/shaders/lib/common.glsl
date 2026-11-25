// Metality Aurora Legacy Common Configuration
#define SHADOW_QUALITY 2 // [0 1 2]
#define SSAO_SAMPLES 8 // [0 4 8 12]
#define BLOOM_ENABLED 1 // [0 1]
#define BLOOM_STRENGTH 0.42 // [0.00 0.25 0.42 0.65 0.85]
#define GODRAY_QUALITY 1 // [0 1 2]
#define TONEMAP_EXPOSURE 1.15 // [0.85 1.00 1.15 1.35]

float metalityLuminance(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 metalityAcesToneMap(vec3 x) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.43;
    float d = 0.59;
    float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), vec3(0.0), vec3(1.0));
}
