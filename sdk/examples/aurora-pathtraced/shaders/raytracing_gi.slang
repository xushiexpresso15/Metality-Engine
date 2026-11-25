module aurora_raytracing_gi;

struct RtParams {
    float4x4 invViewProj;
    float4 sunDir;
};

ConstantBuffer<RtParams> uParams;
RaytracingAccelerationStructure sceneTlas;
RWTexture2D<float4> outRadiance;

[shader("compute")]
void csPathtraceGi(uint3 tid : SV_DispatchThreadID) {
    uint2 gid = tid.xy;
    float2 dirXy = float2(gid) * 0.001f;
    ray r;
    r.origin = float3(0.0f);
    r.direction = normalize(float3(dirXy, -1.0f));
    r.min_distance = 0.01f;
    r.max_distance = 128.0f;

    intersector<triangle_data, instancing> inter;
    auto hit = inter.intersect(r, sceneTlas);
    float3 col = (hit.type != intersection_type::none)
        ? clamp(uParams.sunDir.xyz * 0.5f + 0.5f, 0.0f, 1.0f)
        : float3(0.15f, 0.35f, 0.75f);
    outRadiance.write(float4(col, 1.0f), gid);
}
