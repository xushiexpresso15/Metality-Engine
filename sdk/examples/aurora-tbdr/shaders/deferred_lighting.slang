// Aurora TBDR - Tile-Fused Deferred Lighting (FP16 Optimized + EDR Output)
module deferred_lighting;

struct LightingUniforms {
    float16_t3 sunDirection;
    float16_t  edrHeadroom; // MacBook Pro XDR 峰值亮度倍率 (1.0 ~ 4.0+)
    float16_t3 sunRadiance;
    float16_t  ambientStrength;
};

ConstantBuffer<LightingUniforms> uLight;

// 當 TBDR Render Graph 融合生效時，編譯器自動將這些輸入重映射為晶片內 Tile Imageblock 讀取 (0 DRAM 頻寬)
Texture2D<float16_t4> gbufferAlbedo;
Texture2D<float16_t4> gbufferNormal;
Texture2D<float16_t4> gbufferMaterial;

[shader("fragment")]
float16_t4 fsDeferredMain(float4 pixelPos : SV_Position) : SV_Target0 {
    int3 coord = int3(int2(pixelPos.xy), 0);
    float16_t3 albedo = gbufferAlbedo.Load(coord).rgb;
    float16_t3 normal = normalize(gbufferNormal.Load(coord).xyz * 2.0h - 1.0h);
    float16_t4 labPbr = gbufferMaterial.Load(coord);

    float16_t ndotl = max(dot(normal, uLight.sunDirection), 0.0h);
    float16_t emissive = labPbr.a < 0.996h ? labPbr.a * 4.0h : 0.0h;

    // 原生半精度 (FP16) 光照累積：在 Apple Silicon ALU 上具備 2 倍吞吐量與減半暫存器壓力
    float16_t3 direct = albedo * uLight.sunRadiance * ndotl;
    float16_t3 ambient = albedo * uLight.ambientStrength;
    float16_t3 glow = albedo * emissive * uLight.edrHeadroom;

    return float16_t4(direct + ambient + glow, 1.0h);
}
