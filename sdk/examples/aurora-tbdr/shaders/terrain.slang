// Aurora TBDR - Slang Terrain Shader (Compiled directly to Apple Silicon MSL)
module terrain;

struct CompactChunkVertex {
    uint2 packedPositionAndNormal; // 8-byte UMA Zero-Copy 壓縮頂點格式
    uint  packedUvAndLightmap;
};

struct FrameUniforms {
    float4x4 viewProj;
    float3   cameraChunkOffset;
};

ConstantBuffer<FrameUniforms> uFrame;

struct TerrainVaryings {
    float4 clipPos : SV_Position;
    float16_t3 worldNormal : NORMAL0;
    float2 uv : TEXCOORD0;
    float16_t2 lightmap : TEXCOORD1;
};

struct GBufferOutput {
    float16_t4 albedo   : SV_Target0;
    float16_t4 normal   : SV_Target1;
    float16_t4 material : SV_Target2;
};

Texture2D<float16_t4> blockAtlas;
Texture2D<float16_t4> specularAtlas;
SamplerState atlasSampler;

[shader("vertex")]
TerrainVaryings vsTerrain(CompactChunkVertex vin) {
    TerrainVaryings vout;
    float3 localPos = float3(
        float((vin.packedPositionAndNormal.x >>  0) & 0xFFFFu) * (1.0 / 2048.0),
        float((vin.packedPositionAndNormal.x >> 16) & 0xFFFFu) * (1.0 / 2048.0),
        float((vin.packedPositionAndNormal.y >>  0) & 0xFFFFu) * (1.0 / 2048.0)
    );
    vout.clipPos = mul(uFrame.viewProj, float4(localPos + uFrame.cameraChunkOffset, 1.0));
    vout.worldNormal = float16_t3(0.0h, 1.0h, 0.0h);
    vout.uv = float2(
        float((vin.packedUvAndLightmap >>  0) & 0xFFFFu) * (1.0 / 65535.0),
        float((vin.packedUvAndLightmap >> 16) & 0xFFFFu) * (1.0 / 65535.0)
    );
    vout.lightmap = float16_t2(1.0h, 1.0h);
    return vout;
}

// 純不透明方塊入口：嚴禁 discard，100% 啟用 Apple GPU 硬體隱藏面消除 (HSR)
[shader("fragment")]
GBufferOutput fsTerrainSolid(TerrainVaryings fin) {
    GBufferOutput out;
    out.albedo   = blockAtlas.Sample(atlasSampler, fin.uv);
    out.normal   = float16_t4(fin.worldNormal * 0.5h + 0.5h, 1.0h);
    out.material = specularAtlas.Sample(atlasSampler, fin.uv);
    return out;
}

// 鏤空方塊入口：僅樹葉、草叢走此 PSO，避免污染不透明地形批次的 Early-Z
[shader("fragment")]
GBufferOutput fsTerrainCutout(TerrainVaryings fin) {
    float16_t4 baseColor = blockAtlas.Sample(atlasSampler, fin.uv);
    if (baseColor.a < 0.5h) {
        discard;
    }
    GBufferOutput out;
    out.albedo   = baseColor;
    out.normal   = float16_t4(fin.worldNormal * 0.5h + 0.5h, 1.0h);
    out.material = specularAtlas.Sample(atlasSampler, fin.uv);
    return out;
}
