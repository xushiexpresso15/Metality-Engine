module aurora_pathtraced_terrain;
import metality.std;

struct TerrainVertexIn {
    float3 pos;
    float3 normal;
    float2 uv;
    float4 color;
};

struct TerrainVaryings {
    float4 position : SV_Position;
    float3 normal;
    float2 uv;
    float4 color;
};

struct FrameData {
    float4x4 viewProj;
    float4 edrParams;
};

ConstantBuffer<FrameData> gFrame;
Texture2D<float4> gAlbedoAtlas;
SamplerState gBlockSampler;

[shader("vertex")]
TerrainVaryings vsTerrainMain(TerrainVertexIn v) {
    TerrainVaryings o;
    o.position = mul(gFrame.viewProj, float4(v.pos, 1.0));
    o.normal = v.normal;
    o.uv = v.uv;
    o.color = v.color;
    return o;
}

struct GBufferOut {
    float16_t4 albedo : SV_Target0;
    float16_t4 normal : SV_Target1;
};

[shader("fragment")]
GBufferOut fsTerrainSolid(TerrainVaryings in) {
    GBufferOut out;
    float16_t4 texel = (float16_t4)gAlbedoAtlas.Sample(gBlockSampler, in.uv);
    out.albedo = texel * (float16_t4)in.color;
    out.normal = float16_t4((float16_t3)in.normal * 0.5h + 0.5h, 1.0h);
    return out;
}
