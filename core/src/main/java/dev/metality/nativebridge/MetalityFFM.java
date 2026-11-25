package dev.metality.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Java 25 Project Panama (FFM API) 零開銷原生橋接層（版本無關，置於 :core 模組）。
 * 所有結構體皆透過 C++ static_assert 強制校驗位元組偏移與對齊。
 */
public final class MetalityFFM {

    public static final int FORMAT_RGBA8_UNORM   = 1;
    public static final int FORMAT_RGBA8_SRGB    = 2;
    public static final int FORMAT_BGRA8_SRGB    = 3;
    public static final int FORMAT_RGB10A2_UNORM = 4;
    public static final int FORMAT_RG11B10_FLOAT = 5;
    public static final int FORMAT_RGBA16_FLOAT  = 6;
    public static final int FORMAT_R32_FLOAT     = 7;
    public static final int FORMAT_DEPTH32_FLOAT = 8;
    public static final int FORMAT_RG16_FLOAT    = 9;
    public static final int FORMAT_R16_FLOAT     = 10;
    public static final int FORMAT_R8_UNORM      = 11;
    public static final int FORMAT_R8_SINT       = 12;
    public static final int FORMAT_R32_SINT      = 13;
    public static final int FORMAT_RGBA32_FLOAT  = 14;
    public static final int FORMAT_RGBA16_SINT   = 15;

    public static final int TEX_USAGE_SAMPLED       = 1 << 0;
    public static final int TEX_USAGE_RENDER_TARGET = 1 << 1;
    public static final int TEX_USAGE_STORAGE_WRITE = 1 << 2;
    public static final int TEX_USAGE_MEMORYLESS    = 1 << 3;
    public static final int TEX_USAGE_CUBEMAP       = 1 << 4;

    public static final int BLEND_OPAQUE        = 0;
    public static final int BLEND_ALPHA         = 1;
    public static final int BLEND_ADDITIVE      = 2;
    public static final int BLEND_PREMULTIPLIED = 3;
    public static final int CUSTOM_BLEND_FLAG   = 0x40000000;
    public static final int EXPLICIT_WRITE_MASK_FLAG = 0x10;

    public static final int DEPTH_ALWAYS   = 0;
    public static final int DEPTH_LESS     = 1;
    public static final int DEPTH_LEQUAL   = 2;
    public static final int DEPTH_EQUAL    = 3;
    public static final int DEPTH_GREATER  = 4;
    public static final int DEPTH_NEVER    = 5;
    public static final int DEPTH_GEQUAL   = 6;
    public static final int DEPTH_NOTEQUAL = 7;

    public static final int CULL_NONE  = 0;
    public static final int CULL_BACK  = 1;
    public static final int CULL_FRONT = 2;

    public static final int VERTEX_BUFFER_SLOT_BASE = 20;
    public static final int PUSH_CONSTANT_SLOT      = 19;

    public static final int VTX_FLOAT        = 1;
    public static final int VTX_FLOAT2       = 2;
    public static final int VTX_FLOAT3       = 3;
    public static final int VTX_FLOAT4       = 4;
    public static final int VTX_UBYTE4_NORM  = 5;
    public static final int VTX_BYTE4_NORM   = 6;
    public static final int VTX_UBYTE4       = 7;
    public static final int VTX_BYTE4        = 8;
    public static final int VTX_USHORT2      = 9;
    public static final int VTX_SHORT2       = 10;
    public static final int VTX_USHORT2_NORM = 11;
    public static final int VTX_SHORT2_NORM  = 12;
    public static final int VTX_INT          = 13;
    public static final int VTX_INT2         = 14;
    public static final int VTX_INT3         = 15;
    public static final int VTX_INT4         = 16;
    public static final int VTX_UINT         = 17;
    public static final int VTX_UINT2        = 18;
    public static final int VTX_UINT3        = 19;
    public static final int VTX_UINT4        = 20;
    public static final int VTX_UBYTE3_NORM  = 21;
    public static final int VTX_BYTE3_NORM   = 22;
    public static final int VTX_UBYTE3       = 23;
    public static final int VTX_BYTE3        = 24;
    public static final int VTX_USHORT       = 25;
    public static final int VTX_SHORT        = 26;
    public static final int VTX_UBYTE        = 27;
    public static final int VTX_BYTE         = 28;

    public static final int TOPOLOGY_TRIANGLES      = 0;
    public static final int TOPOLOGY_LINES          = 1;
    public static final int TOPOLOGY_LINE_STRIP     = 2;
    public static final int TOPOLOGY_POINTS         = 3;
    public static final int TOPOLOGY_TRIANGLE_STRIP = 4;

    public static final int LAYER_EMPTY       = 0;
    public static final int LAYER_SOLID       = 1;
    public static final int LAYER_CUTOUT      = 2;
    public static final int LAYER_TRANSLUCENT = 3;

    public static final int UPSCALE_OFF               = 0;
    public static final int UPSCALE_SPATIAL           = 1;
    public static final int UPSCALE_TEMPORAL          = 2;
    public static final int UPSCALE_TEMPORAL_DENOISED = 3;

    public static final MemoryLayout CONFIG_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("cache_directory_utf8"),
            ValueLayout.JAVA_INT.withName("frames_in_flight"),
            ValueLayout.JAVA_BYTE.withName("enable_validation"),
            ValueLayout.JAVA_BYTE.withName("enable_edr_hdr"),
            MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_BYTE).withName("reserved")
    );

    public static final MemoryLayout DEVICE_CAPS_LAYOUT = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(128, ValueLayout.JAVA_BYTE).withName("device_name"),
            ValueLayout.JAVA_LONG.withName("recommended_max_working_set_bytes"),
            ValueLayout.JAVA_LONG.withName("current_allocated_bytes"),
            ValueLayout.JAVA_INT.withName("apple_gpu_family"),
            ValueLayout.JAVA_INT.withName("max_threads_per_threadgroup"),
            ValueLayout.JAVA_INT.withName("max_tile_memory_bytes"),
            ValueLayout.JAVA_BYTE.withName("has_unified_memory"),
            ValueLayout.JAVA_BYTE.withName("supports_argument_buffers_tier2"),
            ValueLayout.JAVA_BYTE.withName("supports_hardware_raytracing"),
            ValueLayout.JAVA_BYTE.withName("supports_mesh_shaders"),
            ValueLayout.JAVA_BYTE.withName("supports_metalfx_temporal"),
            ValueLayout.JAVA_BYTE.withName("supports_metalfx_spatial"),
            ValueLayout.JAVA_BYTE.withName("supports_barycentric_coords"),
            ValueLayout.JAVA_BYTE.withName("supports_metal4_command_queue"),
            ValueLayout.JAVA_INT.withName("reserved_pad")
    ); // 168 bytes

    public static final MemoryLayout TEXTURE_DESC_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.JAVA_INT.withName("mip_levels"),
            ValueLayout.JAVA_INT.withName("format"),
            ValueLayout.JAVA_INT.withName("usage_flags")
    ); // 20 bytes

    public static final MemoryLayout SAMPLER_DESC_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_BYTE.withName("linear_min_mag"),
            ValueLayout.JAVA_BYTE.withName("linear_mipmap"),
            ValueLayout.JAVA_BYTE.withName("repeat_wrap"),
            ValueLayout.JAVA_BYTE.withName("max_anisotropy")
    ); // 4 bytes

    public static final MemoryLayout PIPELINE_DESC_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("shader_library"),
            ValueLayout.ADDRESS.withName("vertex_entry"),
            ValueLayout.ADDRESS.withName("fragment_entry"),
            ValueLayout.JAVA_INT.withName("color_format"),
            ValueLayout.JAVA_INT.withName("depth_format"),
            ValueLayout.JAVA_INT.withName("blend_mode"),
            ValueLayout.JAVA_INT.withName("depth_compare"),
            ValueLayout.JAVA_INT.withName("cull_mode"),
            ValueLayout.JAVA_BYTE.withName("depth_write_enabled"),
            ValueLayout.JAVA_BYTE.withName("color_write_mask"),
            MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_BYTE).withName("reserved")
    ); // 48 bytes

    public static final MemoryLayout VERTEX_BUFFER_LAYOUT_DESC = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("slot"),
            ValueLayout.JAVA_INT.withName("stride"),
            ValueLayout.JAVA_INT.withName("step_rate")
    ); // 12 bytes

    public static final MemoryLayout VERTEX_ATTRIBUTE_DESC = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("location"),
            ValueLayout.JAVA_INT.withName("buffer_slot"),
            ValueLayout.JAVA_INT.withName("offset"),
            ValueLayout.JAVA_INT.withName("format")
    ); // 16 bytes

    public static final MemoryLayout PIPELINE_DESC_EX_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("vertex_library"),
            ValueLayout.JAVA_LONG.withName("fragment_library"),
            ValueLayout.ADDRESS.withName("vertex_entry"),
            ValueLayout.ADDRESS.withName("fragment_entry"),
            ValueLayout.JAVA_INT.withName("color_format"),
            ValueLayout.JAVA_INT.withName("depth_format"),
            ValueLayout.JAVA_INT.withName("blend_mode"),
            ValueLayout.JAVA_INT.withName("depth_compare"),
            ValueLayout.JAVA_INT.withName("cull_mode"),
            ValueLayout.JAVA_BYTE.withName("depth_write_enabled"),
            ValueLayout.JAVA_BYTE.withName("color_write_mask"),
            ValueLayout.JAVA_BYTE.withName("wireframe"),
            ValueLayout.JAVA_BYTE.withName("topology"),
            ValueLayout.JAVA_INT.withName("vb_count"),
            ValueLayout.JAVA_INT.withName("attr_count"),
            ValueLayout.ADDRESS.withName("vb_layouts"),
            ValueLayout.ADDRESS.withName("attributes")
    ); // 80 bytes

    public static final MemoryLayout PIPELINE_MRT_DESC_LAYOUT = MemoryLayout.structLayout(
            PIPELINE_DESC_EX_LAYOUT.withName("base"),
            ValueLayout.JAVA_INT.withName("color_attachment_count"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_INT).withName("extra_color_formats"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_INT).withName("extra_blend_modes"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_BYTE).withName("extra_write_masks"),
            ValueLayout.JAVA_BYTE.withName("pad0")
    ); // 112 bytes

    public record VertexBufferLayoutDesc(int slot, int stride, int stepRate) {}
    public record VertexAttributeDesc(int location, int bufferSlot, int offset, int format) {}

    public static final MemoryLayout RENDER_PASS_BEGIN_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("color_target"),
            ValueLayout.JAVA_LONG.withName("depth_target"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("clear_color"),
            ValueLayout.JAVA_DOUBLE.withName("clear_depth"),
            ValueLayout.JAVA_BYTE.withName("should_clear_color"),
            ValueLayout.JAVA_BYTE.withName("should_clear_depth"),
            ValueLayout.JAVA_BYTE.withName("store_color"),
            ValueLayout.JAVA_BYTE.withName("store_depth"),
            ValueLayout.JAVA_INT.withName("reserved_pad")
    ); // 48 bytes

    public static final MemoryLayout MRT_RENDER_PASS_BEGIN_LAYOUT = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_LONG).withName("color_targets"),
            ValueLayout.JAVA_LONG.withName("depth_target"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("clear_colors"),
            ValueLayout.JAVA_DOUBLE.withName("clear_depth"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_BYTE).withName("should_clear_color"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_BYTE).withName("store_color"),
            ValueLayout.JAVA_BYTE.withName("color_count"),
            ValueLayout.JAVA_BYTE.withName("should_clear_depth"),
            ValueLayout.JAVA_BYTE.withName("store_depth"),
            MemoryLayout.sequenceLayout(5, ValueLayout.JAVA_BYTE).withName("reserved")
    ); // 128 bytes

    public static final MemoryLayout CHUNK_SECTION_INPUT_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("block_material_ids"),
            ValueLayout.ADDRESS.withName("packed_light"),
            ValueLayout.ADDRESS.withName("biome_tint_rgb"),
            ValueLayout.ADDRESS.withName("palette_table"),
            ValueLayout.JAVA_INT.withName("palette_size"),
            ValueLayout.JAVA_INT.withName("enable_greedy_merge")
    ); // 40 bytes

    public static final MemoryLayout MESHED_SECTION_OUTPUT_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("solid_quads"),
            ValueLayout.JAVA_INT.withName("solid_quad_capacity"),
            ValueLayout.JAVA_INT.withName("solid_quad_count"),
            ValueLayout.ADDRESS.withName("cutout_quads"),
            ValueLayout.JAVA_INT.withName("cutout_quad_capacity"),
            ValueLayout.JAVA_INT.withName("cutout_quad_count"),
            ValueLayout.ADDRESS.withName("translucent_quads"),
            ValueLayout.JAVA_INT.withName("translucent_quad_capacity"),
            ValueLayout.JAVA_INT.withName("translucent_quad_count"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("aabb_min"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("aabb_max")
    ); // 72 bytes

    public static final MemoryLayout SECTION_GPU_RECORD_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("chunk_sec_x"),
            ValueLayout.JAVA_INT.withName("chunk_sec_y"),
            ValueLayout.JAVA_INT.withName("chunk_sec_z"),
            ValueLayout.JAVA_INT.withName("solid_quad_offset"),
            ValueLayout.JAVA_INT.withName("solid_quad_count"),
            ValueLayout.JAVA_INT.withName("cutout_quad_offset"),
            ValueLayout.JAVA_INT.withName("cutout_quad_count"),
            ValueLayout.JAVA_INT.withName("translucent_quad_offset"),
            ValueLayout.JAVA_INT.withName("translucent_quad_count"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("aabb_min"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_FLOAT).withName("aabb_max"),
            ValueLayout.JAVA_INT.withName("pad")
    ); // 64 bytes

    public static final MemoryLayout UPSCALER_DESC_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("mode"),
            ValueLayout.JAVA_INT.withName("input_width"),
            ValueLayout.JAVA_INT.withName("input_height"),
            ValueLayout.JAVA_INT.withName("output_width"),
            ValueLayout.JAVA_INT.withName("output_height"),
            ValueLayout.JAVA_INT.withName("color_format"),
            ValueLayout.JAVA_INT.withName("depth_format"),
            ValueLayout.JAVA_INT.withName("motion_format"),
            ValueLayout.JAVA_INT.withName("output_format"),
            ValueLayout.JAVA_BYTE.withName("auto_exposure"),
            ValueLayout.JAVA_BYTE.withName("depth_reversed"),
            MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_BYTE).withName("reserved")
    ); // 40 bytes

    public static final MemoryLayout UPSCALE_DISPATCH_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("color_tex"),
            ValueLayout.JAVA_LONG.withName("depth_tex"),
            ValueLayout.JAVA_LONG.withName("motion_tex"),
            ValueLayout.JAVA_LONG.withName("output_tex"),
            ValueLayout.JAVA_INT.withName("content_width"),
            ValueLayout.JAVA_INT.withName("content_height"),
            ValueLayout.JAVA_FLOAT.withName("jitter_x"),
            ValueLayout.JAVA_FLOAT.withName("jitter_y"),
            ValueLayout.JAVA_FLOAT.withName("motion_scale_x"),
            ValueLayout.JAVA_FLOAT.withName("motion_scale_y"),
            ValueLayout.JAVA_BYTE.withName("reset_history"),
            MemoryLayout.sequenceLayout(7, ValueLayout.JAVA_BYTE).withName("reserved")
    ); // 64 bytes

    public static final MemoryLayout DENOISED_UPSCALE_DISPATCH_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("color_tex"),
            ValueLayout.JAVA_LONG.withName("depth_tex"),
            ValueLayout.JAVA_LONG.withName("motion_tex"),
            ValueLayout.JAVA_LONG.withName("diffuse_albedo_tex"),
            ValueLayout.JAVA_LONG.withName("specular_albedo_tex"),
            ValueLayout.JAVA_LONG.withName("normal_tex"),
            ValueLayout.JAVA_LONG.withName("roughness_tex"),
            ValueLayout.JAVA_LONG.withName("output_tex"),
            ValueLayout.JAVA_FLOAT.withName("jitter_x"),
            ValueLayout.JAVA_FLOAT.withName("jitter_y"),
            ValueLayout.JAVA_FLOAT.withName("motion_scale_x"),
            ValueLayout.JAVA_FLOAT.withName("motion_scale_y"),
            ValueLayout.JAVA_BYTE.withName("reset_history"),
            MemoryLayout.sequenceLayout(15, ValueLayout.JAVA_BYTE).withName("reserved")
    ); // 96 bytes

    public static final MemoryLayout PACK_MANIFEST_SUMMARY_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("shadow_resolution"),
            ValueLayout.JAVA_INT.withName("shadow_cascades"),
            ValueLayout.JAVA_INT.withName("declared_texture_count"),
            ValueLayout.JAVA_INT.withName("memoryless_texture_count"),
            ValueLayout.JAVA_INT.withName("total_pass_count"),
            ValueLayout.JAVA_INT.withName("fused_group_count"),
            ValueLayout.JAVA_INT.withName("compiled_pso_count"),
            ValueLayout.JAVA_INT.withName("upscaler_mode"),
            ValueLayout.JAVA_LONG.withName("saved_dram_bytes_per_frame"),
            ValueLayout.JAVA_BYTE.withName("shadow_enabled"),
            ValueLayout.JAVA_BYTE.withName("gpu_driven_shadow_cull"),
            ValueLayout.JAVA_BYTE.withName("enable_edr"),
            ValueLayout.JAVA_BYTE.withName("enable_hw_raytracing"),
            ValueLayout.JAVA_BYTE.withName("thermal_adaptive"),
            MemoryLayout.sequenceLayout(3, ValueLayout.JAVA_BYTE).withName("reserved")
    ); // 48 bytes

    public static final MemoryLayout GRAPH_PLAN_STATS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("total_passes"),
            ValueLayout.JAVA_INT.withName("fused_encoder_groups"),
            ValueLayout.JAVA_INT.withName("total_textures"),
            ValueLayout.JAVA_INT.withName("memoryless_textures"),
            ValueLayout.JAVA_LONG.withName("dram_bytes_without_tbdr"),
            ValueLayout.JAVA_LONG.withName("dram_bytes_with_tbdr"),
            ValueLayout.JAVA_LONG.withName("saved_dram_bytes_per_frame")
    ); // 40 bytes

    public static final MemoryLayout SPIRV_OPT_STATS_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("input_word_count"),
            ValueLayout.JAVA_INT.withName("output_word_count"),
            ValueLayout.JAVA_INT.withName("discard_op_count"),
            ValueLayout.JAVA_INT.withName("relaxed_fp16_decorations")
    ); // 16 bytes

    public static final MemoryLayout UMA_PRESSURE_LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("current_allocated_bytes"),
            ValueLayout.JAVA_LONG.withName("recommended_working_set_bytes"),
            ValueLayout.JAVA_FLOAT.withName("utilization_ratio"),
            ValueLayout.JAVA_INT.withName("thermal_state_level")
    ); // 24 bytes

    public static final MemoryLayout COMPOSER_UNIFORMS_LAYOUT = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("inv_proj"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("view_rot"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("proj"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("sun_world_dir"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("sky_color"),
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.JAVA_FLOAT.withName("time_seconds"),
            ValueLayout.JAVA_FLOAT.withName("rain_strength"),
            ValueLayout.JAVA_FLOAT.withName("bloom_strength"),
            ValueLayout.JAVA_FLOAT.withName("ssao_strength"),
            ValueLayout.JAVA_FLOAT.withName("godray_strength"),
            ValueLayout.JAVA_FLOAT.withName("exposure"),
            ValueLayout.JAVA_FLOAT.withName("fog_density"),
            ValueLayout.JAVA_INT.withName("tonemap_mode"),
            ValueLayout.JAVA_INT.withName("depth_reversed"),
            ValueLayout.JAVA_INT.withName("reserved_pad")
    ); // 272 bytes

    public static final MemoryLayout WORLD_FRAME_UNIFORMS_LAYOUT = MemoryLayout.structLayout(
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("inv_proj"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("proj"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("view_rot"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("inv_view_rot"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("inv_view_proj"),
            MemoryLayout.sequenceLayout(16, ValueLayout.JAVA_FLOAT).withName("prev_view_proj"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("cam_world_pos"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("sun_world_dir"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("moon_world_dir"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("sky_color"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("fog_color"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("sunrise_color"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT).withName("voxel_origin"),
            ValueLayout.JAVA_INT.withName("width"),
            ValueLayout.JAVA_INT.withName("height"),
            ValueLayout.JAVA_FLOAT.withName("time_seconds"),
            ValueLayout.JAVA_FLOAT.withName("rain_strength"),
            ValueLayout.JAVA_FLOAT.withName("thunder_strength"),
            ValueLayout.JAVA_FLOAT.withName("sea_level"),
            ValueLayout.JAVA_FLOAT.withName("edr_headroom"),
            ValueLayout.JAVA_INT.withName("dimension_id"),
            ValueLayout.JAVA_INT.withName("submerged_mode"),
            ValueLayout.JAVA_INT.withName("rt_instance_count"),
            ValueLayout.JAVA_INT.withName("rt_hardware_active"),
            ValueLayout.JAVA_INT.withName("tonemap_mode"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("user_params"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("tonemap_params"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("lighting_params"),
            MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_FLOAT).withName("surface_params")
    ); // 608 bytes

    public record WorldFrameUniforms(
            float[] invProj16,
            float[] proj16,
            float[] viewRot16,
            float[] invViewRot16,
            float[] invViewProj16,
            float[] prevViewProj16,
            float[] camWorldPos4,
            float[] sunWorldDir4,
            float[] moonWorldDir4,
            float[] skyColor4,
            float[] fogColor4,
            float[] sunriseColor4,
            int[] voxelOrigin4,
            int width,
            int height,
            float timeSeconds,
            float rainStrength,
            float thunderStrength,
            float seaLevel,
            float edrHeadroom,
            int dimensionId,
            int submergedMode,
            int rtInstanceCount,
            int rtHardwareActive,
            int tonemapMode,
            float[] userParams4,
            float[] tonemapParams4,
            float[] lightingParams4,
            float[] surfaceParams4
    ) {
        public WorldFrameUniforms(
                float[] invProj16, float[] proj16, float[] viewRot16, float[] invViewRot16,
                float[] invViewProj16, float[] prevViewProj16,
                float[] camWorldPos4, float[] sunWorldDir4, float[] moonWorldDir4,
                float[] skyColor4, float[] fogColor4, float[] sunriseColor4,
                int[] voxelOrigin4, int width, int height,
                float timeSeconds, float rainStrength, float thunderStrength,
                float seaLevel, float edrHeadroom, int dimensionId, int submergedMode,
                int rtInstanceCount, int rtHardwareActive, int tonemapMode, float[] userParams4
        ) {
            this(
                    invProj16, proj16, viewRot16, invViewRot16, invViewProj16, prevViewProj16,
                    camWorldPos4, sunWorldDir4, moonWorldDir4, skyColor4, fogColor4, sunriseColor4,
                    voxelOrigin4, width, height, timeSeconds, rainStrength, thunderStrength,
                    seaLevel, edrHeadroom, dimensionId, submergedMode, rtInstanceCount, rtHardwareActive,
                    tonemapMode, userParams4, null, null, null
            );
        }

        public WorldFrameUniforms withUserParams(float[] overrideParams4) {
            return new WorldFrameUniforms(
                    invProj16, proj16, viewRot16, invViewRot16, invViewProj16, prevViewProj16,
                    camWorldPos4, sunWorldDir4, moonWorldDir4, skyColor4, fogColor4, sunriseColor4,
                    voxelOrigin4, width, height, timeSeconds, rainStrength, thunderStrength,
                    seaLevel, edrHeadroom, dimensionId, submergedMode, rtInstanceCount, rtHardwareActive,
                    tonemapMode, overrideParams4, tonemapParams4, lightingParams4, surfaceParams4
            );
        }

        public WorldFrameUniforms withPackParams(
                float[] overrideUser4,
                float[] overrideTonemap4,
                float[] overrideLighting4,
                float[] overrideSurface4
        ) {
            return withPackParams(tonemapMode, overrideUser4, overrideTonemap4, overrideLighting4, overrideSurface4);
        }

        public WorldFrameUniforms withPackParams(
                int effectiveTonemapMode,
                float[] overrideUser4,
                float[] overrideTonemap4,
                float[] overrideLighting4,
                float[] overrideSurface4
        ) {
            return new WorldFrameUniforms(
                    invProj16, proj16, viewRot16, invViewRot16, invViewProj16, prevViewProj16,
                    camWorldPos4, sunWorldDir4, moonWorldDir4, skyColor4, fogColor4, sunriseColor4,
                    voxelOrigin4, width, height, timeSeconds, rainStrength, thunderStrength,
                    seaLevel, edrHeadroom, dimensionId, submergedMode, rtInstanceCount, rtHardwareActive,
                    effectiveTonemapMode, overrideUser4, overrideTonemap4, overrideLighting4, overrideSurface4
            );
        }
    }

    public record GraphPlanStats(
            int totalPasses,
            int fusedEncoderGroups,
            int totalTextures,
            int memorylessTextures,
            long dramBytesWithoutTbdr,
            long dramBytesWithTbdr,
            long savedDramBytesPerFrame
    ) {}

    public record SpirvOptStats(
            int inputWordCount,
            int outputWordCount,
            int discardOpCount,
            int relaxedFp16Decorations
    ) {}

    public record SpirvOptimizeResult(int[] optimizedWords, SpirvOptStats stats) {}

    public record UmaPressureInfo(
            long currentAllocatedBytes,
            long recommendedWorkingSetBytes,
            float utilizationRatio,
            int thermalStateLevel
    ) {}

    public record MslRewriteResult(String mslSource, int inlinedSamplers) {}

    public record SharedUmaAllocation(long handle, MemorySegment cpuSegment, long gpuAddress) {}

    public record DeviceCapabilities(
            String deviceName,
            long recommendedMaxWorkingSetBytes,
            int appleGpuFamily,
            int maxTileMemoryBytes,
            boolean unifiedMemory,
            boolean argumentBuffersTier2,
            boolean hardwareRayTracing,
            boolean metalFXTemporal,
            boolean metal4CommandQueue
    ) {}

    public record PackManifestSummary(
            int shadowResolution,
            int shadowCascades,
            int declaredTextureCount,
            int memorylessTextureCount,
            int totalPassCount,
            int fusedGroupCount,
            int compiledPsoCount,
            int upscalerMode,
            long savedDramBytesPerFrame,
            boolean shadowEnabled,
            boolean gpuDrivenShadowCull,
            boolean enableEdr,
            boolean enableHwRaytracing,
            boolean thermalAdaptive
    ) {}

    public record LoadedShaderPack(MemorySegment handle, PackManifestSummary summary) {}

    private final Arena libraryArena;

    // Lifecycle & Frame
    private final MethodHandle contextCreate;
    private final MethodHandle contextDestroy;
    private final MethodHandle queryCapabilities;
    private final MethodHandle attachNsWindow;
    private final MethodHandle resizeDrawable;
    private final MethodHandle setEdrEnabled;
    private final MethodHandle setDisplaySync;
    private final MethodHandle setMaxFramesInFlight;
    private final MethodHandle setShaderRenderScale;
    private final MethodHandle beginFrame;
    private final MethodHandle endFrame;
    private final MethodHandle waitFrameComplete;
    private final MethodHandle flushPipelineCache;

    // UMA Buffers
    private final MethodHandle bufferAllocShared;
    private final MethodHandle bufferRetire;

    // Phase 0: Textures, Samplers, MSL Compiler, PSOs & RenderPass Commands
    private final MethodHandle textureCreate;
    private final MethodHandle textureUploadRegion;
    private final MethodHandle textureUploadSliceRegion;
    private final MethodHandle textureDestroy;
    private final MethodHandle textureCreateView;
    private final MethodHandle samplerGetOrCreate;
    private final MethodHandle samplerGetOrCreateEx;
    private final MethodHandle compileMslLibrary;
    private final MethodHandle psoCreate;
    private final MethodHandle psoCreateEx;
    private final MethodHandle psoCreateMrt;
    private final MethodHandle psoGetBuiltinBlaze3d;
    private final MethodHandle cmdBeginRenderPass;
    private final MethodHandle cmdBeginRenderPassMrt;
    private final MethodHandle cmdSetPso;
    private final MethodHandle cmdSetDepthBias;
    private final MethodHandle cmdSetViewport;
    private final MethodHandle cmdSetScissor;
    private final MethodHandle cmdBindVertexBuffer;
    private final MethodHandle cmdBindUniformBuffer;
    private final MethodHandle cmdBindUniformBytes;
    private final MethodHandle cmdBindTexture;
    private final MethodHandle cmdBindTexelBuffer;
    private final MethodHandle cmdDraw;
    private final MethodHandle cmdDrawIndexedU32;
    private final MethodHandle cmdDrawIndexedInstanced;
    private final MethodHandle cmdDrawIndexedIndirect;
    private final MethodHandle cmdEndRenderPass;
    private final MethodHandle cmdClearColorDepth;
    private final MethodHandle cmdClearColorDepthRect;
    private final MethodHandle cmdPresentBlitTexture;
    private final MethodHandle cmdCopyBuffer;
    private final MethodHandle cmdCopyTextureToBuffer;
    private final MethodHandle cmdCopyTextureRegion;

    // Phase 1: Chunk Arena, NEON Mesher & GPU ICB Terrain Pipeline
    private final MethodHandle arenaCreate;
    private final MethodHandle arenaDestroy;
    private final MethodHandle arenaAlloc;
    private final MethodHandle arenaFree;
    private final MethodHandle meshChunkSectionNeon;
    private final MethodHandle terrainPipelineCreate;
    private final MethodHandle terrainPipelineDestroy;
    private final MethodHandle terrainQuadPoolCpuPtr;
    private final MethodHandle terrainUpdateSprites;
    private final MethodHandle terrainUpdateSection;
    private final MethodHandle terrainEncodeGpuCull;
    private final MethodHandle terrainEncodeIcbDraw;

    // Phase 2: Profiler, MetalFX Upscaler & Aperture pack.ts Loader
    private final MethodHandle lastGpuFrameMs;
    private final MethodHandle recommendedRenderScale;
    private final MethodHandle upscalerCreate;
    private final MethodHandle upscalerEncode;
    private final MethodHandle upscalerEncodeDenoised;
    private final MethodHandle upscalerHaltonJitter;
    private final MethodHandle upscalerDestroy;
    private final MethodHandle packLoadDirectory;
    private final MethodHandle packExecute;
    private final MethodHandle packDestroy;

    // Phase 3: Hardware Ray Tracing BLAS/TLAS & Voxel DDA Fallback
    private final MethodHandle rtSceneCreate;
    private final MethodHandle rtSceneDestroy;
    private final MethodHandle rtSceneUsesHardwareBvh;
    private final MethodHandle rtSceneActiveBlasCount;
    private final MethodHandle rtSceneUpdateSection;
    private final MethodHandle rtSceneUpdateSectionV2;
    private final MethodHandle rtSceneRemoveSection;
    private final MethodHandle rtSceneClearAll;
    private final MethodHandle rtSceneSetDynamicLights;
    private final MethodHandle rtSceneSetWaterRipples;
    private final MethodHandle rtSceneSetEntityShadows;
    private final MethodHandle rtSceneRebuildTlas;
    private final MethodHandle rtSceneSyncWorldData;
    private final MethodHandle rtDispatchGi;

    // RenderGraph TBDR Planner & Phase 4: Legacy Iris Compatibility Bridge + Live Composer
    private final MethodHandle graphCreate;
    private final MethodHandle graphDestroy;
    private final MethodHandle graphDeclareTexture;
    private final MethodHandle graphAddPass;
    private final MethodHandle graphCompileTbdr;
    private final MethodHandle queryUmaPressure;
    private final MethodHandle spirvOptimizeForAppleGpu;
    private final MethodHandle rewriteMslSamplersAndFp16;
    private final MethodHandle composerCreate;
    private final MethodHandle composerDestroy;
    private final MethodHandle composerExecute;

    public MetalityFFM(Path extractedDylibPath) {
        this.libraryArena = Arena.ofShared();
        SymbolLookup lookup = SymbolLookup.libraryLookup(extractedDylibPath, libraryArena);
        Linker linker = Linker.nativeLinker();

        this.contextCreate = downcall(linker, lookup, "metality_context_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.contextDestroy = downcall(linker, lookup, "metality_context_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.queryCapabilities = downcall(linker, lookup, "metality_query_capabilities",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.attachNsWindow = downcall(linker, lookup, "metality_attach_nswindow",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_BOOLEAN));
        this.resizeDrawable = downcall(linker, lookup, "metality_resize_drawable",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT));
        this.setEdrEnabled = downcall(linker, lookup, "metality_set_edr_enabled",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
        this.setDisplaySync = downcall(linker, lookup, "metality_set_display_sync",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
        this.setMaxFramesInFlight = downcall(linker, lookup, "metality_set_max_frames_in_flight",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.setShaderRenderScale = downcall(linker, lookup, "metality_set_shader_render_scale",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT));
        this.beginFrame = downcall(linker, lookup, "metality_begin_frame",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        this.endFrame = downcall(linker, lookup, "metality_end_frame",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
        this.waitFrameComplete = downcall(linker, lookup, "metality_wait_frame_complete",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        this.flushPipelineCache = downcall(linker, lookup, "metality_flush_pipeline_cache",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));

        this.bufferAllocShared = downcall(linker, lookup, "metality_buffer_alloc_shared",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.bufferRetire = downcall(linker, lookup, "metality_buffer_retire",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        // Phase 0
        this.textureCreate = downcall(linker, lookup, "metality_texture_create",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.textureUploadRegion = downcall(linker, lookup, "metality_texture_upload_region",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.textureUploadSliceRegion = downcall(linker, lookup, "metality_texture_upload_slice_region",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.textureDestroy = downcall(linker, lookup, "metality_texture_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        this.textureCreateView = downcall(linker, lookup, "metality_texture_create_view",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.samplerGetOrCreate = downcall(linker, lookup, "metality_sampler_get_or_create",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.samplerGetOrCreateEx = downcall(linker, lookup, "metality_sampler_get_or_create_ex",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT));
        this.compileMslLibrary = downcall(linker, lookup, "metality_compile_msl_library",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.psoCreate = downcall(linker, lookup, "metality_pso_create",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.psoCreateEx = downcall(linker, lookup, "metality_pso_create_ex",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.psoCreateMrt = downcall(linker, lookup, "metality_pso_create_mrt",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.psoGetBuiltinBlaze3d = downcall(linker, lookup, "metality_pso_get_builtin_blaze3d",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.cmdBeginRenderPass = downcall(linker, lookup, "metality_cmd_begin_render_pass",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.cmdBeginRenderPassMrt = downcall(linker, lookup, "metality_cmd_begin_render_pass_mrt",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.cmdSetPso = downcall(linker, lookup, "metality_cmd_set_pso",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        this.cmdSetDepthBias = downcall(linker, lookup, "metality_cmd_set_depth_bias",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
        this.cmdSetViewport = downcall(linker, lookup, "metality_cmd_set_viewport",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
        this.cmdSetScissor = downcall(linker, lookup, "metality_cmd_set_scissor",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.cmdBindVertexBuffer = downcall(linker, lookup, "metality_cmd_bind_vertex_buffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        this.cmdBindUniformBuffer = downcall(linker, lookup, "metality_cmd_bind_uniform_buffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        this.cmdBindUniformBytes = downcall(linker, lookup, "metality_cmd_bind_uniform_bytes",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.cmdBindTexture = downcall(linker, lookup, "metality_cmd_bind_texture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        this.cmdBindTexelBuffer = downcall(linker, lookup, "metality_cmd_bind_texel_buffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        this.cmdDraw = downcall(linker, lookup, "metality_cmd_draw",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.cmdDrawIndexedU32 = downcall(linker, lookup, "metality_cmd_draw_indexed_u32",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        this.cmdDrawIndexedInstanced = downcall(linker, lookup, "metality_cmd_draw_indexed_instanced",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.cmdDrawIndexedIndirect = downcall(linker, lookup, "metality_cmd_draw_indexed_indirect",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        this.cmdEndRenderPass = downcall(linker, lookup, "metality_cmd_end_render_pass",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.cmdClearColorDepth = downcall(linker, lookup, "metality_cmd_clear_color_depth",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE));
        this.cmdClearColorDepthRect = downcall(linker, lookup, "metality_cmd_clear_color_depth_rect",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.cmdPresentBlitTexture = downcall(linker, lookup, "metality_cmd_present_blit_texture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        this.cmdCopyBuffer = downcall(linker, lookup, "metality_cmd_copy_buffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        this.cmdCopyTextureToBuffer = downcall(linker, lookup, "metality_cmd_copy_texture_to_buffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.cmdCopyTextureRegion = downcall(linker, lookup, "metality_cmd_copy_texture_region",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // Phase 1
        this.arenaCreate = downcall(linker, lookup, "metality_arena_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        this.arenaDestroy = downcall(linker, lookup, "metality_arena_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.arenaAlloc = downcall(linker, lookup, "metality_arena_alloc",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.arenaFree = downcall(linker, lookup, "metality_arena_free",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        this.meshChunkSectionNeon = downcall(linker, lookup, "metality_mesh_chunk_section_neon",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.terrainPipelineCreate = downcall(linker, lookup, "metality_terrain_pipeline_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.terrainPipelineDestroy = downcall(linker, lookup, "metality_terrain_pipeline_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.terrainQuadPoolCpuPtr = downcall(linker, lookup, "metality_terrain_quad_pool_cpu_ptr",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.terrainUpdateSprites = downcall(linker, lookup, "metality_terrain_update_sprites",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.terrainUpdateSection = downcall(linker, lookup, "metality_terrain_update_section",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.terrainEncodeGpuCull = downcall(linker, lookup, "metality_terrain_encode_gpu_cull",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.terrainEncodeIcbDraw = downcall(linker, lookup, "metality_terrain_encode_icb_draw",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        // Phase 2
        this.lastGpuFrameMs = downcall(linker, lookup, "metality_last_gpu_frame_ms",
                FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS));
        this.recommendedRenderScale = downcall(linker, lookup, "metality_recommended_render_scale",
                FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN));
        this.upscalerCreate = downcall(linker, lookup, "metality_upscaler_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.upscalerEncode = downcall(linker, lookup, "metality_upscaler_encode",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.upscalerEncodeDenoised = downcall(linker, lookup, "metality_upscaler_encode_denoised",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.upscalerHaltonJitter = downcall(linker, lookup, "metality_upscaler_halton_jitter",
                FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.upscalerDestroy = downcall(linker, lookup, "metality_upscaler_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.packLoadDirectory = downcall(linker, lookup, "metality_pack_load_directory",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.packExecute = downcall(linker, lookup, "metality_pack_execute",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        this.packDestroy = downcall(linker, lookup, "metality_pack_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // Phase 3
        this.rtSceneCreate = downcall(linker, lookup, "metality_rt_scene_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN));
        this.rtSceneDestroy = downcall(linker, lookup, "metality_rt_scene_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.rtSceneUsesHardwareBvh = downcall(linker, lookup, "metality_rt_scene_uses_hardware_bvh",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
        this.rtSceneActiveBlasCount = downcall(linker, lookup, "metality_rt_scene_active_blas_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.rtSceneUpdateSection = downcall(linker, lookup, "metality_rt_scene_update_section",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.rtSceneUpdateSectionV2 = downcall(linker, lookup, "metality_rt_scene_update_section_v2",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.rtSceneRemoveSection = downcall(linker, lookup, "metality_rt_scene_remove_section",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.rtSceneClearAll = downcall(linker, lookup, "metality_rt_scene_clear_all",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.rtSceneSetDynamicLights = downcall(linker, lookup, "metality_rt_scene_set_dynamic_lights",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.rtSceneSetWaterRipples = downcall(linker, lookup, "metality_rt_scene_set_water_ripples",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.rtSceneSetEntityShadows = downcall(linker, lookup, "metality_rt_scene_set_entity_shadows",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.rtSceneRebuildTlas = downcall(linker, lookup, "metality_rt_scene_rebuild_tlas",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
        this.rtSceneSyncWorldData = downcall(linker, lookup, "metality_rt_scene_sync_world_data",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
        this.rtDispatchGi = downcall(linker, lookup, "metality_rt_dispatch_gi",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

        // RenderGraph TBDR & Phase 4
        this.graphCreate = downcall(linker, lookup, "metality_graph_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        this.graphDestroy = downcall(linker, lookup, "metality_graph_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.graphDeclareTexture = downcall(linker, lookup, "metality_graph_declare_texture",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN));
        this.graphAddPass = downcall(linker, lookup, "metality_graph_add_pass",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        this.graphCompileTbdr = downcall(linker, lookup, "metality_graph_compile_tbdr",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.queryUmaPressure = downcall(linker, lookup, "metality_query_uma_pressure",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.spirvOptimizeForAppleGpu = downcall(linker, lookup, "metality_spirv_optimize_for_apple_gpu",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.rewriteMslSamplersAndFp16 = downcall(linker, lookup, "metality_rewrite_msl_samplers_and_fp16",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.JAVA_BOOLEAN,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        this.composerCreate = downcall(linker, lookup, "metality_composer_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.composerDestroy = downcall(linker, lookup, "metality_composer_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        this.composerExecute = downcall(linker, lookup, "metality_composer_execute",
                FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String symbol, FunctionDescriptor desc) {
        return linker.downcallHandle(lookup.findOrThrow(symbol), desc);
    }

    public static MetalityFFM loadFromClasspath(Path cacheDir) throws IOException {
        Files.createDirectories(cacheDir);
        Path targetDylib = cacheDir.resolve("libmetality_native.dylib");
        try (InputStream in = MetalityFFM.class.getResourceAsStream("/natives/macos-arm64/libmetality_native.dylib")) {
            if (in == null) {
                throw new IOException("Embedded libmetality_native.dylib not found in :core resources!");
            }
            Files.copy(in, targetDylib, StandardCopyOption.REPLACE_EXISTING);
        }
        return new MetalityFFM(targetDylib);
    }

    public MemorySegment createContext(Path psoCacheDir, boolean enableEdr) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment cfg = temp.allocate(CONFIG_LAYOUT);
            MemorySegment dirUtf8 = temp.allocateFrom(psoCacheDir.toAbsolutePath().toString(), StandardCharsets.UTF_8);
            cfg.set(ValueLayout.ADDRESS, 0, dirUtf8);
            cfg.set(ValueLayout.JAVA_INT, 8, 3);
            cfg.set(ValueLayout.JAVA_BYTE, 12, (byte) 0);
            cfg.set(ValueLayout.JAVA_BYTE, 13, (byte) (enableEdr ? 1 : 0));
            return (MemorySegment) contextCreate.invokeExact(cfg);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke metality_context_create", t);
        }
    }

    public DeviceCapabilities getCapabilities(MemorySegment ctx) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment caps = temp.allocate(DEVICE_CAPS_LAYOUT);
            queryCapabilities.invokeExact(ctx, caps);

            String name = caps.getString(0, StandardCharsets.UTF_8);
            long workingSet = caps.get(ValueLayout.JAVA_LONG, 128);
            int gpuFamily = caps.get(ValueLayout.JAVA_INT, 144);
            int tileMem = caps.get(ValueLayout.JAVA_INT, 152);
            boolean uma = caps.get(ValueLayout.JAVA_BYTE, 156) != 0;
            boolean abTier2 = caps.get(ValueLayout.JAVA_BYTE, 157) != 0;
            boolean hwRt = caps.get(ValueLayout.JAVA_BYTE, 158) != 0;
            boolean mfxTemporal = caps.get(ValueLayout.JAVA_BYTE, 160) != 0;
            boolean metal4 = caps.get(ValueLayout.JAVA_BYTE, 163) != 0;

            return new DeviceCapabilities(
                    name, workingSet, gpuFamily, tileMem, uma, abTier2, hwRt, mfxTemporal, metal4
            );
        } catch (Throwable t) {
            throw new RuntimeException("Failed to query Apple Silicon Metal capabilities", t);
        }
    }

    public SharedUmaAllocation allocateZeroCopyBuffer(MemorySegment ctx, long sizeBytes, boolean writeCombined) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment outCpuPtr = temp.allocate(ValueLayout.ADDRESS);
            MemorySegment outGpuAddr = temp.allocate(ValueLayout.JAVA_LONG);

            long handle = (long) bufferAllocShared.invokeExact(ctx, sizeBytes, writeCombined, outCpuPtr, outGpuAddr);
            if (handle == 0L) {
                throw new OutOfMemoryError("Metal UMA shared buffer allocation failed (" + sizeBytes + " bytes)");
            }
            long alignedBytes = (sizeBytes + 255L) & ~255L;
            MemorySegment rawPtr = outCpuPtr.get(ValueLayout.ADDRESS, 0).reinterpret(alignedBytes);
            long gpuAddress = outGpuAddr.get(ValueLayout.JAVA_LONG, 0);
            return new SharedUmaAllocation(handle, rawPtr, gpuAddress);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to allocate UMA shared buffer", t);
        }
    }

    public void retireBuffer(MemorySegment ctx, long handle) {
        try {
            bufferRetire.invokeExact(ctx, handle);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to retire UMA buffer", t);
        }
    }

    public boolean attachCocoaWindow(MemorySegment ctx, long nsWindowPtr, int fbWidth, int fbHeight, float scaleFactor, boolean vsync) {
        try {
            return (boolean) attachNsWindow.invokeExact(ctx, MemorySegment.ofAddress(nsWindowPtr), fbWidth, fbHeight, scaleFactor, vsync);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to attach CAMetalLayer to NSWindow", t);
        }
    }

    public void resizeDrawable(MemorySegment ctx, int fbWidth, int fbHeight, float scaleFactor) {
        try {
            resizeDrawable.invokeExact(ctx, fbWidth, fbHeight, scaleFactor);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to resize CAMetalLayer drawable", t);
        }
    }

    public void setEdrEnabled(MemorySegment ctx, boolean enabled) {
        try {
            setEdrEnabled.invokeExact(ctx, enabled);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to update EDR state", t);
        }
    }

    public void setDisplaySync(MemorySegment ctx, boolean enabled) {
        try {
            setDisplaySync.invokeExact(ctx, enabled);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to update CAMetalLayer displaySyncEnabled", t);
        }
    }

    public void setMaxFramesInFlight(MemorySegment ctx, int frames) {
        try {
            setMaxFramesInFlight.invokeExact(ctx, frames);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to update max frames in flight", t);
        }
    }

    public void setShaderRenderScale(MemorySegment ctx, float scale) {
        try {
            setShaderRenderScale.invokeExact(ctx, scale);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to update shader render scale", t);
        }
    }

    public long beginFrame(MemorySegment ctx) {
        try {
            return (long) beginFrame.invokeExact(ctx);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_begin_frame", t);
        }
    }

    public void endFrame(MemorySegment ctx, boolean presentDrawable) {
        try {
            endFrame.invokeExact(ctx, presentDrawable);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_end_frame", t);
        }
    }

    public boolean waitFrameComplete(MemorySegment ctx, long frameId, long timeoutMs) {
        try {
            return (boolean) waitFrameComplete.invokeExact(ctx, frameId, timeoutMs);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_wait_frame_complete", t);
        }
    }

    public boolean flushPipelineCache(MemorySegment ctx) {
        try {
            return (boolean) flushPipelineCache.invokeExact(ctx);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to flush Metal binary archive", t);
        }
    }

    // --- Phase 0 Wrappers ---

    public long createTexture(MemorySegment ctx, int width, int height, int mipLevels, int format, int usageFlags) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment desc = temp.allocate(TEXTURE_DESC_LAYOUT);
            desc.set(ValueLayout.JAVA_INT, 0, width);
            desc.set(ValueLayout.JAVA_INT, 4, height);
            desc.set(ValueLayout.JAVA_INT, 8, mipLevels);
            desc.set(ValueLayout.JAVA_INT, 12, format);
            desc.set(ValueLayout.JAVA_INT, 16, usageFlags);
            return (long) textureCreate.invokeExact(ctx, desc);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create Metal texture", t);
        }
    }

    public void uploadTextureRegion(MemorySegment ctx, long texHandle, int mipLevel, int x, int y, int width, int height, MemorySegment pixels, int bytesPerRow) {
        uploadTextureSliceRegion(ctx, texHandle, mipLevel, 0, x, y, width, height, pixels, bytesPerRow);
    }

    public void uploadTextureSliceRegion(MemorySegment ctx, long texHandle, int mipLevel, int arraySlice, int x, int y, int width, int height, MemorySegment pixels, int bytesPerRow) {
        try {
            textureUploadSliceRegion.invokeExact(ctx, texHandle, mipLevel, arraySlice, x, y, width, height, pixels, bytesPerRow);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to upload texture slice region", t);
        }
    }

    public void destroyTexture(MemorySegment ctx, long texHandle) {
        try {
            textureDestroy.invokeExact(ctx, texHandle);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to destroy Metal texture", t);
        }
    }

    public long createTextureView(MemorySegment ctx, long parentTexHandle, int baseMipLevel, int mipLevelCount) {
        try {
            return (long) textureCreateView.invokeExact(ctx, parentTexHandle, baseMipLevel, mipLevelCount);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create Metal texture view", t);
        }
    }

    public long getOrCreateSampler(MemorySegment ctx, boolean linearMinMag, boolean linearMipmap, boolean repeatWrap, int maxAnisotropy) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment desc = temp.allocate(SAMPLER_DESC_LAYOUT);
            desc.set(ValueLayout.JAVA_BYTE, 0, (byte) (linearMinMag ? 1 : 0));
            desc.set(ValueLayout.JAVA_BYTE, 1, (byte) (linearMipmap ? 1 : 0));
            desc.set(ValueLayout.JAVA_BYTE, 2, (byte) (repeatWrap ? 1 : 0));
            desc.set(ValueLayout.JAVA_BYTE, 3, (byte) maxAnisotropy);
            return (long) samplerGetOrCreate.invokeExact(ctx, desc);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get or create Metal sampler", t);
        }
    }

    public long getOrCreateSamplerEx(
            MemorySegment ctx,
            boolean linearMin,
            boolean linearMag,
            boolean repeatU,
            boolean repeatV,
            int maxAnisotropy,
            float maxLod
    ) {
        try {
            return (long) samplerGetOrCreateEx.invokeExact(ctx, linearMin, linearMag, repeatU, repeatV, maxAnisotropy, maxLod);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get or create Metal sampler ex", t);
        }
    }

    public long compileMslLibrary(MemorySegment ctx, String label, String mslSource, boolean fastMath) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment lbl = temp.allocateFrom(label != null ? label : "RenderPearlShader", StandardCharsets.UTF_8);
            MemorySegment src = temp.allocateFrom(mslSource, StandardCharsets.UTF_8);
            MemorySegment errBuf = temp.allocate(1024);
            long lib = (long) compileMslLibrary.invokeExact(ctx, lbl, src, fastMath, errBuf, 1024);
            if (lib == 0L) {
                throw new IllegalStateException("MSL compilation failed [" + label + "]: " + errBuf.getString(0, StandardCharsets.UTF_8));
            }
            return lib;
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_compile_msl_library", t);
        }
    }

    public long createRenderPso(
            MemorySegment ctx,
            long shaderLib,
            String vertexEntry,
            String fragmentEntry,
            int colorFormat,
            int depthFormat,
            int blendMode,
            int depthCompare,
            int cullMode,
            boolean depthWriteEnabled,
            int colorWriteMask
    ) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pd = temp.allocate(PIPELINE_DESC_LAYOUT);
            MemorySegment vs = temp.allocateFrom(vertexEntry, StandardCharsets.UTF_8);
            MemorySegment fs = temp.allocateFrom(fragmentEntry, StandardCharsets.UTF_8);
            MemorySegment errBuf = temp.allocate(1024);

            pd.set(ValueLayout.JAVA_LONG, 0, shaderLib);
            pd.set(ValueLayout.ADDRESS, 8, vs);
            pd.set(ValueLayout.ADDRESS, 16, fs);
            pd.set(ValueLayout.JAVA_INT, 24, colorFormat);
            pd.set(ValueLayout.JAVA_INT, 28, depthFormat);
            pd.set(ValueLayout.JAVA_INT, 32, blendMode);
            pd.set(ValueLayout.JAVA_INT, 36, depthCompare);
            pd.set(ValueLayout.JAVA_INT, 40, cullMode);
            pd.set(ValueLayout.JAVA_BYTE, 44, (byte) (depthWriteEnabled ? 1 : 0));
            pd.set(ValueLayout.JAVA_BYTE, 45, (byte) (colorWriteMask & 0xF));

            long pso = (long) psoCreate.invokeExact(ctx, pd, errBuf, 1024);
            if (pso == 0L) {
                throw new IllegalStateException("Metal PSO creation failed (" + vertexEntry + " + " + fragmentEntry + "): "
                        + errBuf.getString(0, StandardCharsets.UTF_8));
            }
            return pso;
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_pso_create", t);
        }
    }

    private void writePipelineDescEx(
            MemorySegment pd,
            Arena temp,
            long vertexLibrary,
            long fragmentLibrary,
            String vertexEntry,
            String fragmentEntry,
            int colorFormat,
            int depthFormat,
            int blendMode,
            int depthCompare,
            int cullMode,
            boolean depthWriteEnabled,
            int colorWriteMask,
            boolean wireframe,
            int topology,
            VertexBufferLayoutDesc[] vbLayouts,
            VertexAttributeDesc[] attributes
    ) {
        MemorySegment vs = temp.allocateFrom(vertexEntry, StandardCharsets.UTF_8);
        MemorySegment fs = temp.allocateFrom(fragmentEntry, StandardCharsets.UTF_8);
        int vbCount = vbLayouts != null ? vbLayouts.length : 0;
        int attrCount = attributes != null ? attributes.length : 0;
        MemorySegment vbSeg = vbCount > 0 ? temp.allocate(VERTEX_BUFFER_LAYOUT_DESC, vbCount) : MemorySegment.NULL;
        for (int i = 0; i < vbCount; ++i) {
            long base = (long) i * VERTEX_BUFFER_LAYOUT_DESC.byteSize();
            vbSeg.set(ValueLayout.JAVA_INT, base, vbLayouts[i].slot());
            vbSeg.set(ValueLayout.JAVA_INT, base + 4, vbLayouts[i].stride());
            vbSeg.set(ValueLayout.JAVA_INT, base + 8, vbLayouts[i].stepRate());
        }
        MemorySegment attrSeg = attrCount > 0 ? temp.allocate(VERTEX_ATTRIBUTE_DESC, attrCount) : MemorySegment.NULL;
        for (int i = 0; i < attrCount; ++i) {
            long base = (long) i * VERTEX_ATTRIBUTE_DESC.byteSize();
            attrSeg.set(ValueLayout.JAVA_INT, base, attributes[i].location());
            attrSeg.set(ValueLayout.JAVA_INT, base + 4, attributes[i].bufferSlot());
            attrSeg.set(ValueLayout.JAVA_INT, base + 8, attributes[i].offset());
            attrSeg.set(ValueLayout.JAVA_INT, base + 12, attributes[i].format());
        }
        pd.set(ValueLayout.JAVA_LONG, 0, vertexLibrary);
        pd.set(ValueLayout.JAVA_LONG, 8, fragmentLibrary);
        pd.set(ValueLayout.ADDRESS, 16, vs);
        pd.set(ValueLayout.ADDRESS, 24, fs);
        pd.set(ValueLayout.JAVA_INT, 32, colorFormat);
        pd.set(ValueLayout.JAVA_INT, 36, depthFormat);
        pd.set(ValueLayout.JAVA_INT, 40, blendMode);
        pd.set(ValueLayout.JAVA_INT, 44, depthCompare);
        pd.set(ValueLayout.JAVA_INT, 48, cullMode);
        pd.set(ValueLayout.JAVA_BYTE, 52, (byte) (depthWriteEnabled ? 1 : 0));
        pd.set(ValueLayout.JAVA_BYTE, 53, (byte) ((colorWriteMask & 0xF) | EXPLICIT_WRITE_MASK_FLAG));
        pd.set(ValueLayout.JAVA_BYTE, 54, (byte) (wireframe ? 1 : 0));
        pd.set(ValueLayout.JAVA_BYTE, 55, (byte) (topology & 0xFF));
        pd.set(ValueLayout.JAVA_INT, 56, vbCount);
        pd.set(ValueLayout.JAVA_INT, 60, attrCount);
        pd.set(ValueLayout.ADDRESS, 64, vbSeg);
        pd.set(ValueLayout.ADDRESS, 72, attrSeg);
    }

    public long createRenderPsoEx(
            MemorySegment ctx,
            long vertexLibrary,
            long fragmentLibrary,
            String vertexEntry,
            String fragmentEntry,
            int colorFormat,
            int depthFormat,
            int blendMode,
            int depthCompare,
            int cullMode,
            boolean depthWriteEnabled,
            int colorWriteMask,
            boolean wireframe,
            int topology,
            VertexBufferLayoutDesc[] vbLayouts,
            VertexAttributeDesc[] attributes
    ) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pd = temp.allocate(PIPELINE_DESC_EX_LAYOUT);
            MemorySegment errBuf = temp.allocate(1024);
            writePipelineDescEx(
                    pd, temp, vertexLibrary, fragmentLibrary, vertexEntry, fragmentEntry,
                    colorFormat, depthFormat, blendMode, depthCompare, cullMode,
                    depthWriteEnabled, colorWriteMask, wireframe, topology, vbLayouts, attributes
            );
            long pso = (long) psoCreateEx.invokeExact(ctx, pd, errBuf, 1024);
            if (pso == 0L) {
                throw new IllegalStateException("Metal PSO EX creation failed (" + vertexEntry + " + " + fragmentEntry + "): "
                        + errBuf.getString(0, StandardCharsets.UTF_8));
            }
            return pso;
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_pso_create_ex", t);
        }
    }

    public long createRenderPsoMrt(
            MemorySegment ctx,
            long vertexLibrary,
            long fragmentLibrary,
            String vertexEntry,
            String fragmentEntry,
            int colorAttachmentCount,
            int[] colorFormats,
            int[] blendModes,
            int[] writeMasks,
            int depthFormat,
            int depthCompare,
            int cullMode,
            boolean depthWriteEnabled,
            boolean wireframe,
            int topology,
            VertexBufferLayoutDesc[] vbLayouts,
            VertexAttributeDesc[] attributes
    ) {
        if (colorAttachmentCount <= 1) {
            int cf = (colorFormats != null && colorFormats.length > 0 && colorAttachmentCount == 1) ? colorFormats[0] : 0;
            int bm = (blendModes != null && blendModes.length > 0) ? blendModes[0] : BLEND_OPAQUE;
            int wm = (writeMasks != null && writeMasks.length > 0) ? writeMasks[0] : 0xF;
            return createRenderPsoEx(
                    ctx, vertexLibrary, fragmentLibrary, vertexEntry, fragmentEntry,
                    cf, depthFormat, bm, depthCompare, cullMode,
                    depthWriteEnabled, wm, wireframe, topology, vbLayouts, attributes
            );
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment mrt = temp.allocate(PIPELINE_MRT_DESC_LAYOUT);
            MemorySegment errBuf = temp.allocate(1024);
            writePipelineDescEx(
                    mrt, temp, vertexLibrary, fragmentLibrary, vertexEntry, fragmentEntry,
                    colorFormats[0], depthFormat, blendModes[0], depthCompare, cullMode,
                    depthWriteEnabled, writeMasks[0], wireframe, topology, vbLayouts, attributes
            );
            int clampedCount = Math.min(colorAttachmentCount, 4);
            mrt.set(ValueLayout.JAVA_INT, 80, clampedCount);
            for (int i = 1; i < clampedCount; ++i) {
                mrt.set(ValueLayout.JAVA_INT, 84 + (long) (i - 1) * 4L, colorFormats[i]);
                mrt.set(ValueLayout.JAVA_INT, 96 + (long) (i - 1) * 4L, blendModes[i]);
                mrt.set(ValueLayout.JAVA_BYTE, 108 + (long) (i - 1), (byte) ((writeMasks[i] & 0xF) | EXPLICIT_WRITE_MASK_FLAG));
            }
            long pso = (long) psoCreateMrt.invokeExact(ctx, mrt, errBuf, 1024);
            if (pso == 0L) {
                throw new IllegalStateException("Metal PSO MRT creation failed (" + vertexEntry + " + " + fragmentEntry + "): "
                        + errBuf.getString(0, StandardCharsets.UTF_8));
            }
            return pso;
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_pso_create_mrt", t);
        }
    }

    public long getBuiltinBlaze3dPso(MemorySegment ctx, int builtinKind, int colorFormat, int depthFormat, int blendMode) {
        try {
            return (long) psoGetBuiltinBlaze3d.invokeExact(ctx, builtinKind, colorFormat, depthFormat, blendMode);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to acquire builtin Blaze3D PSO", t);
        }
    }

    public boolean cmdBeginRenderPass(MemorySegment ctx, MemorySegment beginInfoStruct) {
        try {
            return (boolean) cmdBeginRenderPass.invokeExact(ctx, beginInfoStruct);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_begin_render_pass", t);
        }
    }

    public boolean cmdBeginRenderPassMrt(MemorySegment ctx, MemorySegment mrtBeginInfoStruct) {
        try {
            return (boolean) cmdBeginRenderPassMrt.invokeExact(ctx, mrtBeginInfoStruct);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_begin_render_pass_mrt", t);
        }
    }

    public void cmdSetPso(MemorySegment ctx, long psoHandle) {
        try {
            cmdSetPso.invokeExact(ctx, psoHandle);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_set_pso", t);
        }
    }

    public void cmdSetDepthBias(MemorySegment ctx, float depthBias, float slopeScale) {
        try {
            cmdSetDepthBias.invokeExact(ctx, depthBias, slopeScale);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_set_depth_bias", t);
        }
    }

    public void cmdSetViewport(MemorySegment ctx, float x, float y, float w, float h, float znear, float zfar) {
        try {
            cmdSetViewport.invokeExact(ctx, x, y, w, h, znear, zfar);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_set_viewport", t);
        }
    }

    public void cmdSetScissor(MemorySegment ctx, int x, int y, int w, int h) {
        try {
            cmdSetScissor.invokeExact(ctx, x, y, w, h);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_set_scissor", t);
        }
    }

    public void cmdBindVertexBuffer(MemorySegment ctx, int slot, long bufHandle, long byteOffset) {
        try {
            cmdBindVertexBuffer.invokeExact(ctx, slot, bufHandle, byteOffset);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_bind_vertex_buffer", t);
        }
    }

    public void cmdBindUniformBuffer(MemorySegment ctx, int slot, long bufHandle, long byteOffset) {
        try {
            cmdBindUniformBuffer.invokeExact(ctx, slot, bufHandle, byteOffset);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_bind_uniform_buffer", t);
        }
    }

    public void cmdBindUniformBytes(MemorySegment ctx, int slot, MemorySegment data, int byteLength) {
        try {
            cmdBindUniformBytes.invokeExact(ctx, slot, data, byteLength);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_bind_uniform_bytes", t);
        }
    }

    public void cmdBindTexture(MemorySegment ctx, int slot, long texHandle, long samplerHandle) {
        try {
            cmdBindTexture.invokeExact(ctx, slot, texHandle, samplerHandle);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_bind_texture", t);
        }
    }

    public void cmdBindTexelBuffer(MemorySegment ctx, int slot, long bufHandle, long offset, long sizeBytes, int pixelFormat) {
        try {
            cmdBindTexelBuffer.invokeExact(ctx, slot, bufHandle, offset, sizeBytes, pixelFormat);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_bind_texel_buffer", t);
        }
    }

    public void cmdDraw(MemorySegment ctx, int vertexStart, int vertexCount) {
        try {
            cmdDraw.invokeExact(ctx, vertexStart, vertexCount);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_draw", t);
        }
    }

    public void cmdDrawIndexedU32(MemorySegment ctx, long indexBufHandle, long indexByteOffset, int indexCount) {
        try {
            cmdDrawIndexedU32.invokeExact(ctx, indexBufHandle, indexByteOffset, indexCount);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_draw_indexed_u32", t);
        }
    }

    public void cmdDrawIndexedInstanced(
            MemorySegment ctx,
            long indexBufHandle,
            long indexByteOffset,
            int indexCount,
            boolean indexIsU16,
            int instanceCount,
            int baseVertex,
            int baseInstance
    ) {
        try {
            cmdDrawIndexedInstanced.invokeExact(ctx, indexBufHandle, indexByteOffset, indexCount, indexIsU16, instanceCount, baseVertex, baseInstance);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_draw_indexed_instanced", t);
        }
    }

    public void cmdDrawIndexedIndirect(
            MemorySegment ctx,
            long indexBufHandle,
            boolean indexIsU16,
            long indirectBufHandle,
            long indirectByteOffset,
            int drawCount
    ) {
        try {
            cmdDrawIndexedIndirect.invokeExact(ctx, indexBufHandle, indexIsU16, indirectBufHandle, indirectByteOffset, drawCount);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_draw_indexed_indirect", t);
        }
    }

    public void cmdEndRenderPass(MemorySegment ctx) {
        try {
            cmdEndRenderPass.invokeExact(ctx);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_end_render_pass", t);
        }
    }

    public void cmdClearColorDepth(
            MemorySegment ctx,
            long colorTex,
            float r, float g, float b, float a,
            long depthTex,
            double clearDepth
    ) {
        try {
            cmdClearColorDepth.invokeExact(ctx, colorTex, r, g, b, a, depthTex, clearDepth);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_clear_color_depth", t);
        }
    }

    public void cmdClearColorDepthRect(
            MemorySegment ctx,
            long colorTex,
            float r, float g, float b, float a,
            long depthTex,
            double clearDepth,
            int mipLevel,
            int x,
            int y,
            int width,
            int height
    ) {
        try {
            cmdClearColorDepthRect.invokeExact(ctx, colorTex, r, g, b, a, depthTex, clearDepth, mipLevel, x, y, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_clear_color_depth_rect", t);
        }
    }

    public void cmdPresentBlitTexture(MemorySegment ctx, long srcColorTex) {
        try {
            cmdPresentBlitTexture.invokeExact(ctx, srcColorTex);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_present_blit_texture", t);
        }
    }

    public void cmdCopyBuffer(MemorySegment ctx, long srcBuf, long srcOff, long dstBuf, long dstOff, long size) {
        try {
            cmdCopyBuffer.invokeExact(ctx, srcBuf, srcOff, dstBuf, dstOff, size);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_copy_buffer", t);
        }
    }

    public void cmdCopyTextureToBuffer(MemorySegment ctx, long srcTex, long dstBuf, long dstOffset, int bytesPerRow, int width, int height) {
        try {
            cmdCopyTextureToBuffer.invokeExact(ctx, srcTex, dstBuf, dstOffset, bytesPerRow, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_copy_texture_to_buffer", t);
        }
    }

    public void cmdCopyTextureRegion(MemorySegment ctx, long srcTex, long dstTex, int mipLevel, int dstX, int dstY, int srcX, int srcY, int width, int height) {
        try {
            cmdCopyTextureRegion.invokeExact(ctx, srcTex, dstTex, mipLevel, dstX, dstY, srcX, srcY, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_cmd_copy_texture_region", t);
        }
    }

    // --- Phase 1 Wrappers ---

    public MemorySegment createArena(long capacityBytes, int alignmentBytes) {
        try {
            return (MemorySegment) arenaCreate.invokeExact(capacityBytes, alignmentBytes);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create UMA Chunk Arena", t);
        }
    }

    public void destroyArena(MemorySegment arena) {
        try {
            arenaDestroy.invokeExact(arena);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to destroy UMA Chunk Arena", t);
        }
    }

    public long[] allocFromArena(MemorySegment arena, long sizeBytes) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment outOff = temp.allocate(ValueLayout.JAVA_LONG);
            MemorySegment outSize = temp.allocate(ValueLayout.JAVA_LONG);
            boolean ok = (boolean) arenaAlloc.invokeExact(arena, sizeBytes, outOff, outSize);
            if (!ok) {
                return null;
            }
            return new long[]{outOff.get(ValueLayout.JAVA_LONG, 0), outSize.get(ValueLayout.JAVA_LONG, 0)};
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_arena_alloc", t);
        }
    }

    public void freeToArena(MemorySegment arena, long offset, long alignedSize) {
        try {
            arenaFree.invokeExact(arena, offset, alignedSize);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_arena_free", t);
        }
    }

    public boolean meshChunkSectionNeon(MemorySegment inputStruct, MemorySegment outputStruct) {
        try {
            return (boolean) meshChunkSectionNeon.invokeExact(inputStruct, outputStruct);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_mesh_chunk_section_neon", t);
        }
    }

    public MemorySegment createTerrainPipeline(MemorySegment ctx, int maxSections, long quadPoolBytes) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment errBuf = temp.allocate(512);
            MemorySegment handle = (MemorySegment) terrainPipelineCreate.invokeExact(ctx, maxSections, quadPoolBytes, errBuf, 512);
            if (MemorySegment.NULL.equals(handle)) {
                String msg = errBuf.getString(0, StandardCharsets.UTF_8);
                throw new IllegalStateException("Failed to create GPU ICB Terrain Pipeline: " + msg);
            }
            return handle;
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_terrain_pipeline_create", t);
        }
    }

    public void destroyTerrainPipeline(MemorySegment ctx, MemorySegment terrainHandle) {
        try {
            terrainPipelineDestroy.invokeExact(ctx, terrainHandle);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to destroy terrain pipeline", t);
        }
    }

    public MemorySegment getTerrainQuadPoolSegment(MemorySegment terrainHandle, long quadPoolBytes) {
        try {
            long aligned = (quadPoolBytes + 255L) & ~255L;
            MemorySegment ptr = (MemorySegment) terrainQuadPoolCpuPtr.invokeExact(terrainHandle);
            return ptr.reinterpret(aligned);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to map UMA Terrain Quad Pool", t);
        }
    }

    public void updateTerrainSprites(MemorySegment terrainHandle, MemorySegment spriteEntries, int count) {
        try {
            terrainUpdateSprites.invokeExact(terrainHandle, spriteEntries, count);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to update terrain sprite UV table", t);
        }
    }

    public void updateTerrainSection(MemorySegment terrainHandle, int sectionSlot, MemorySegment sectionGpuRecord) {
        try {
            terrainUpdateSection.invokeExact(terrainHandle, sectionSlot, sectionGpuRecord);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to update GPU section record", t);
        }
    }

    public void encodeTerrainGpuCull(MemorySegment ctx, MemorySegment terrainHandle, MemorySegment frustumPlanes24f, MemorySegment camPos3f, int activeSectionCount) {
        try {
            terrainEncodeGpuCull.invokeExact(ctx, terrainHandle, frustumPlanes24f, camPos3f, activeSectionCount);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to encode GPU Compute Terrain Culling", t);
        }
    }

    public void encodeTerrainIcbDraw(MemorySegment ctx, MemorySegment terrainHandle, MemorySegment viewProj16f, MemorySegment camPos3f, long blockAtlasTex, long atlasSampler, int passLayer, int activeSectionCount) {
        try {
            terrainEncodeIcbDraw.invokeExact(ctx, terrainHandle, viewProj16f, camPos3f, blockAtlasTex, atlasSampler, passLayer, activeSectionCount);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to encode GPU ICB Terrain Draw", t);
        }
    }

    // --- Phase 2 Wrappers ---

    public double lastGpuFrameMs(MemorySegment ctx) {
        try {
            return (double) lastGpuFrameMs.invokeExact(ctx);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_last_gpu_frame_ms", t);
        }
    }

    public float recommendedRenderScale(MemorySegment ctx, boolean thermalAdaptive) {
        try {
            return (float) recommendedRenderScale.invokeExact(ctx, thermalAdaptive);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_recommended_render_scale", t);
        }
    }

    public MemorySegment createUpscaler(MemorySegment ctx, MemorySegment upscalerDescStruct) {
        try {
            return (MemorySegment) upscalerCreate.invokeExact(ctx, upscalerDescStruct);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_upscaler_create", t);
        }
    }

    public boolean encodeUpscaler(MemorySegment ctx, MemorySegment upscalerHandle, MemorySegment dispatchInfoStruct) {
        try {
            return (boolean) upscalerEncode.invokeExact(ctx, upscalerHandle, dispatchInfoStruct);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_upscaler_encode", t);
        }
    }

    public boolean encodeUpscalerDenoised(MemorySegment ctx, MemorySegment upscalerHandle, MemorySegment denoisedDispatchInfoStruct) {
        try {
            return (boolean) upscalerEncodeDenoised.invokeExact(ctx, upscalerHandle, denoisedDispatchInfoStruct);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_upscaler_encode_denoised", t);
        }
    }

    public float[] haltonJitter(long frameIndex, int inputWidth, int outputWidth) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment outX = temp.allocate(ValueLayout.JAVA_FLOAT);
            MemorySegment outY = temp.allocate(ValueLayout.JAVA_FLOAT);
            upscalerHaltonJitter.invokeExact(frameIndex, inputWidth, outputWidth, outX, outY);
            return new float[]{outX.get(ValueLayout.JAVA_FLOAT, 0), outY.get(ValueLayout.JAVA_FLOAT, 0)};
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_upscaler_halton_jitter", t);
        }
    }

    public void destroyUpscaler(MemorySegment upscalerHandle) {
        try {
            upscalerDestroy.invokeExact(upscalerHandle);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_upscaler_destroy", t);
        }
    }

    public LoadedShaderPack loadShaderPack(MemorySegment ctx, Path packRootDir, int screenW, int screenH, float internalScale) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment dirUtf8 = temp.allocateFrom(packRootDir.toAbsolutePath().toString(), StandardCharsets.UTF_8);
            MemorySegment sumSeg = temp.allocate(PACK_MANIFEST_SUMMARY_LAYOUT);
            MemorySegment errBuf = temp.allocate(1024);

            MemorySegment packHandle = (MemorySegment) packLoadDirectory.invokeExact(
                    ctx, dirUtf8, screenW, screenH, internalScale, sumSeg, errBuf, 1024);
            if (MemorySegment.NULL.equals(packHandle)) {
                String msg = errBuf.getString(0, StandardCharsets.UTF_8);
                throw new IllegalStateException("Failed to load Aperture shader pack (" + packRootDir + "): " + msg);
            }

            PackManifestSummary summary = new PackManifestSummary(
                    sumSeg.get(ValueLayout.JAVA_INT, 0),
                    sumSeg.get(ValueLayout.JAVA_INT, 4),
                    sumSeg.get(ValueLayout.JAVA_INT, 8),
                    sumSeg.get(ValueLayout.JAVA_INT, 12),
                    sumSeg.get(ValueLayout.JAVA_INT, 16),
                    sumSeg.get(ValueLayout.JAVA_INT, 20),
                    sumSeg.get(ValueLayout.JAVA_INT, 24),
                    sumSeg.get(ValueLayout.JAVA_INT, 28),
                    sumSeg.get(ValueLayout.JAVA_LONG, 32),
                    sumSeg.get(ValueLayout.JAVA_BYTE, 40) != 0,
                    sumSeg.get(ValueLayout.JAVA_BYTE, 41) != 0,
                    sumSeg.get(ValueLayout.JAVA_BYTE, 42) != 0,
                    sumSeg.get(ValueLayout.JAVA_BYTE, 43) != 0,
                    sumSeg.get(ValueLayout.JAVA_BYTE, 44) != 0
            );
            return new LoadedShaderPack(packHandle, summary);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_pack_load_directory", t);
        }
    }

    private static final float[] DEFAULT_USER_PARAMS = new float[]{1.0f, 1.0f, 1.0f, 1.0f};
    private static final float[] DEFAULT_TONEMAP_PARAMS = new float[]{0.95f, 1.14f, 1.16f, 1.25f};
    private static final float[] DEFAULT_LIGHTING_PARAMS = new float[]{1.18f, 1.12f, 1.12f, 0.65f};
    private static final float[] DEFAULT_SURFACE_PARAMS = new float[]{1.15f, 1.15f, 1.15f, 1.10f};
    private static final ThreadLocal<MemorySegment> PACK_EXECUTE_UNIFORM_TLS =
            ThreadLocal.withInitial(() -> Arena.ofAuto().allocate(WORLD_FRAME_UNIFORMS_LAYOUT));

    public boolean packExecute(
            MemorySegment ctx,
            MemorySegment packHandle,
            MemorySegment optRtSceneHandle,
            long sceneColorHandle,
            long sceneDepthHandle,
            WorldFrameUniforms frame
    ) {
        try {
            MemorySegment u = PACK_EXECUTE_UNIFORM_TLS.get();
            MemorySegment.copy(frame.invProj16(), 0, u, ValueLayout.JAVA_FLOAT, 0L, 16);
            MemorySegment.copy(frame.proj16(), 0, u, ValueLayout.JAVA_FLOAT, 64L, 16);
            MemorySegment.copy(frame.viewRot16(), 0, u, ValueLayout.JAVA_FLOAT, 128L, 16);
            MemorySegment.copy(frame.invViewRot16(), 0, u, ValueLayout.JAVA_FLOAT, 192L, 16);
            MemorySegment.copy(frame.invViewProj16(), 0, u, ValueLayout.JAVA_FLOAT, 256L, 16);
            MemorySegment.copy(frame.prevViewProj16(), 0, u, ValueLayout.JAVA_FLOAT, 320L, 16);
            MemorySegment.copy(frame.camWorldPos4(), 0, u, ValueLayout.JAVA_FLOAT, 384L, 4);
            MemorySegment.copy(frame.sunWorldDir4(), 0, u, ValueLayout.JAVA_FLOAT, 400L, 4);
            MemorySegment.copy(frame.moonWorldDir4(), 0, u, ValueLayout.JAVA_FLOAT, 416L, 4);
            MemorySegment.copy(frame.skyColor4(), 0, u, ValueLayout.JAVA_FLOAT, 432L, 4);
            MemorySegment.copy(frame.fogColor4(), 0, u, ValueLayout.JAVA_FLOAT, 448L, 4);
            MemorySegment.copy(frame.sunriseColor4(), 0, u, ValueLayout.JAVA_FLOAT, 464L, 4);
            if (frame.voxelOrigin4() != null) {
                MemorySegment.copy(frame.voxelOrigin4(), 0, u, ValueLayout.JAVA_INT, 480L, 4);
            } else {
                u.set(ValueLayout.JAVA_INT, 480L, 0);
                u.set(ValueLayout.JAVA_INT, 484L, 0);
                u.set(ValueLayout.JAVA_INT, 488L, 0);
                u.set(ValueLayout.JAVA_INT, 492L, 0);
            }
            u.set(ValueLayout.JAVA_INT, 496L, frame.width());
            u.set(ValueLayout.JAVA_INT, 500L, frame.height());
            u.set(ValueLayout.JAVA_FLOAT, 504L, frame.timeSeconds());
            u.set(ValueLayout.JAVA_FLOAT, 508L, frame.rainStrength());
            u.set(ValueLayout.JAVA_FLOAT, 512L, frame.thunderStrength());
            u.set(ValueLayout.JAVA_FLOAT, 516L, frame.seaLevel());
            u.set(ValueLayout.JAVA_FLOAT, 520L, frame.edrHeadroom());
            u.set(ValueLayout.JAVA_INT, 524L, frame.dimensionId());
            u.set(ValueLayout.JAVA_INT, 528L, frame.submergedMode());
            u.set(ValueLayout.JAVA_INT, 532L, frame.rtInstanceCount());
            u.set(ValueLayout.JAVA_INT, 536L, frame.rtHardwareActive());
            u.set(ValueLayout.JAVA_INT, 540L, frame.tonemapMode());
            float[] up = frame.userParams4() != null ? frame.userParams4() : DEFAULT_USER_PARAMS;
            float[] tp = frame.tonemapParams4() != null ? frame.tonemapParams4() : DEFAULT_TONEMAP_PARAMS;
            float[] lp = frame.lightingParams4() != null ? frame.lightingParams4() : DEFAULT_LIGHTING_PARAMS;
            float[] sp = frame.surfaceParams4() != null ? frame.surfaceParams4() : DEFAULT_SURFACE_PARAMS;
            MemorySegment.copy(up, 0, u, ValueLayout.JAVA_FLOAT, 544L, 4);
            MemorySegment.copy(tp, 0, u, ValueLayout.JAVA_FLOAT, 560L, 4);
            MemorySegment.copy(lp, 0, u, ValueLayout.JAVA_FLOAT, 576L, 4);
            MemorySegment.copy(sp, 0, u, ValueLayout.JAVA_FLOAT, 592L, 4);
            MemorySegment rtSeg = optRtSceneHandle != null ? optRtSceneHandle : MemorySegment.NULL;
            return (boolean) packExecute.invokeExact(ctx, packHandle, rtSeg, sceneColorHandle, sceneDepthHandle, u);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_pack_execute", t);
        }
    }

    public void destroyShaderPack(MemorySegment packHandle) {
        try {
            packDestroy.invokeExact(packHandle);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_pack_destroy", t);
        }
    }

    // --- Phase 3 Wrappers ---

    public MemorySegment rtSceneCreate(MemorySegment ctx, int maxSections, boolean forceVoxelFallback) {
        try {
            return (MemorySegment) rtSceneCreate.invokeExact(ctx, maxSections, forceVoxelFallback);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_create", t);
        }
    }

    public void rtSceneDestroy(MemorySegment scene) {
        try {
            rtSceneDestroy.invokeExact(scene);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_destroy", t);
        }
    }

    public boolean rtSceneUsesHardwareBvh(MemorySegment scene) {
        try {
            return (boolean) rtSceneUsesHardwareBvh.invokeExact(scene);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_uses_hardware_bvh", t);
        }
    }

    public int rtSceneActiveBlasCount(MemorySegment scene) {
        try {
            return (int) rtSceneActiveBlasCount.invokeExact(scene);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_active_blas_count", t);
        }
    }

    public boolean rtSceneUpdateSection(
            MemorySegment ctx, MemorySegment scene,
            int secX, int secY, int secZ,
            MemorySegment solidQuads, int solidCount,
            MemorySegment cutoutQuads, int cutoutCount
    ) {
        try {
            return (boolean) rtSceneUpdateSection.invokeExact(
                    ctx, scene, secX, secY, secZ, solidQuads, solidCount, cutoutQuads, cutoutCount);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_update_section", t);
        }
    }

    public boolean rtSceneUpdateSectionV2(
            MemorySegment ctx, MemorySegment scene,
            int secX, int secY, int secZ,
            MemorySegment solidQuads, int solidCount,
            MemorySegment cutoutQuads, int cutoutCount,
            MemorySegment translucentQuads, int translucentCount
    ) {
        try {
            return (boolean) rtSceneUpdateSectionV2.invokeExact(
                    ctx, scene, secX, secY, secZ,
                    solidQuads, solidCount,
                    cutoutQuads, cutoutCount,
                    translucentQuads, translucentCount);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_update_section_v2", t);
        }
    }

    public void rtSceneRemoveSection(MemorySegment scene, int secX, int secY, int secZ) {
        try {
            rtSceneRemoveSection.invokeExact(scene, secX, secY, secZ);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_remove_section", t);
        }
    }

    public void rtSceneClearAll(MemorySegment scene) {
        try {
            rtSceneClearAll.invokeExact(scene);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_clear_all", t);
        }
    }

    public void rtSceneSetDynamicLights(MemorySegment scene, MemorySegment lightsData8f, int count) {
        try {
            rtSceneSetDynamicLights.invokeExact(scene, lightsData8f, count);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_set_dynamic_lights", t);
        }
    }

    public void rtSceneSetWaterRipples(MemorySegment scene, MemorySegment ripplesData8f, int count) {
        try {
            rtSceneSetWaterRipples.invokeExact(scene, ripplesData8f, count);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_set_water_ripples", t);
        }
    }

    public void rtSceneSetEntityShadows(MemorySegment scene, MemorySegment castersData8f, int count) {
        try {
            rtSceneSetEntityShadows.invokeExact(scene, castersData8f, count);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_set_entity_shadows", t);
        }
    }

    public boolean rtSceneRebuildTlas(MemorySegment ctx, MemorySegment scene, float camX, float camY, float camZ) {
        try {
            return (boolean) rtSceneRebuildTlas.invokeExact(ctx, scene, camX, camY, camZ);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_rebuild_tlas", t);
        }
    }

    public boolean syncRtSceneWorldData(MemorySegment ctx, MemorySegment scene, float camX, float camY, float camZ) {
        try {
            return (boolean) rtSceneSyncWorldData.invokeExact(ctx, scene, camX, camY, camZ);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_scene_sync_world_data", t);
        }
    }

    public boolean rtDispatchGi(
            MemorySegment ctx, MemorySegment scene,
            MemorySegment invViewProj, MemorySegment camPos, MemorySegment sunDir,
            int width, int height,
            long outNoisyRadiance, long outDepth, long outMotion,
            long outDiffuseAlbedo, long outSpecularAlbedo, long outNormal, long outRoughness
    ) {
        try {
            return (boolean) rtDispatchGi.invokeExact(
                    ctx, scene, invViewProj, camPos, sunDir, width, height,
                    outNoisyRadiance, outDepth, outMotion,
                    outDiffuseAlbedo, outSpecularAlbedo, outNormal, outRoughness);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_rt_dispatch_gi", t);
        }
    }

    // --- RenderGraph TBDR & Phase 4 Wrappers ---

    public MemorySegment graphCreate() {
        try {
            return (MemorySegment) graphCreate.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_graph_create", t);
        }
    }

    public void graphDestroy(MemorySegment graph) {
        try {
            graphDestroy.invokeExact(graph);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_graph_destroy", t);
        }
    }

    public int graphDeclareTexture(
            MemorySegment graph,
            String name,
            int format,
            int width,
            int height,
            boolean forcePersistent
    ) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment nameUtf8 = temp.allocateFrom(name, StandardCharsets.UTF_8);
            return (int) graphDeclareTexture.invokeExact(graph, nameUtf8, format, width, height, forcePersistent);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_graph_declare_texture", t);
        }
    }

    public int graphAddPass(
            MemorySegment graph,
            String name,
            int[] readTexIds,
            boolean samplesNeighbors,
            int[] writeTexIds
    ) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment nameUtf8 = temp.allocateFrom(name, StandardCharsets.UTF_8);
            MemorySegment readSeg = (readTexIds != null && readTexIds.length > 0)
                    ? temp.allocateFrom(ValueLayout.JAVA_INT, readTexIds)
                    : MemorySegment.NULL;
            MemorySegment writeSeg = (writeTexIds != null && writeTexIds.length > 0)
                    ? temp.allocateFrom(ValueLayout.JAVA_INT, writeTexIds)
                    : MemorySegment.NULL;
            return (int) graphAddPass.invokeExact(
                    graph,
                    nameUtf8,
                    readSeg,
                    readTexIds != null ? readTexIds.length : 0,
                    samplesNeighbors,
                    writeSeg,
                    writeTexIds != null ? writeTexIds.length : 0
            );
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_graph_add_pass", t);
        }
    }

    public GraphPlanStats graphCompileTbdr(MemorySegment graph) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment stats = temp.allocate(GRAPH_PLAN_STATS_LAYOUT);
            graphCompileTbdr.invokeExact(graph, stats);
            return new GraphPlanStats(
                    stats.get(ValueLayout.JAVA_INT, 0),
                    stats.get(ValueLayout.JAVA_INT, 4),
                    stats.get(ValueLayout.JAVA_INT, 8),
                    stats.get(ValueLayout.JAVA_INT, 12),
                    stats.get(ValueLayout.JAVA_LONG, 16),
                    stats.get(ValueLayout.JAVA_LONG, 24),
                    stats.get(ValueLayout.JAVA_LONG, 32)
            );
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_graph_compile_tbdr", t);
        }
    }

    public UmaPressureInfo queryUmaPressure(MemorySegment ctx) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment info = temp.allocate(UMA_PRESSURE_LAYOUT);
            queryUmaPressure.invokeExact(ctx, info);
            return new UmaPressureInfo(
                    info.get(ValueLayout.JAVA_LONG, 0),
                    info.get(ValueLayout.JAVA_LONG, 8),
                    info.get(ValueLayout.JAVA_FLOAT, 16),
                    info.get(ValueLayout.JAVA_INT, 20)
            );
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_query_uma_pressure", t);
        }
    }

    public SpirvOptimizeResult spirvOptimizeForAppleGpu(int[] inWords, boolean injectRelaxedFp16) {
        if (inWords == null || inWords.length < 5) {
            throw new IllegalArgumentException("Invalid SPIR-V word stream");
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment inSeg = temp.allocateFrom(ValueLayout.JAVA_INT, inWords);
            int outCap = inWords.length * 2 + 1024;
            MemorySegment outSeg = temp.allocate(ValueLayout.JAVA_INT, outCap);
            MemorySegment statsSeg = temp.allocate(SPIRV_OPT_STATS_LAYOUT);

            boolean ok = (boolean) spirvOptimizeForAppleGpu.invokeExact(
                    inSeg, inWords.length, injectRelaxedFp16, outSeg, outCap, statsSeg);
            if (!ok) {
                throw new IllegalStateException("metality_spirv_optimize_for_apple_gpu failed");
            }
            SpirvOptStats stats = new SpirvOptStats(
                    statsSeg.get(ValueLayout.JAVA_INT, 0),
                    statsSeg.get(ValueLayout.JAVA_INT, 4),
                    statsSeg.get(ValueLayout.JAVA_INT, 8),
                    statsSeg.get(ValueLayout.JAVA_INT, 12)
            );
            int[] outWords = new int[stats.outputWordCount()];
            MemorySegment.copy(outSeg, ValueLayout.JAVA_INT, 0, outWords, 0, stats.outputWordCount());
            return new SpirvOptimizeResult(outWords, stats);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException("Failed in metality_spirv_optimize_for_apple_gpu", t);
        }
    }

    public MslRewriteResult rewriteMslSamplersAndFp16(String inMsl, boolean stripDiscardSafety) {
        try (Arena temp = Arena.ofConfined()) {
            byte[] utf8 = inMsl.getBytes(StandardCharsets.UTF_8);
            MemorySegment inSeg = temp.allocate(utf8.length + 1L);
            MemorySegment.copy(utf8, 0, inSeg, ValueLayout.JAVA_BYTE, 0, utf8.length);
            inSeg.set(ValueLayout.JAVA_BYTE, utf8.length, (byte) 0);

            int cap = Math.max(8192, utf8.length * 2 + 4096);
            MemorySegment outSeg = temp.allocate(cap);
            MemorySegment outCount = temp.allocate(ValueLayout.JAVA_INT);

            boolean ok = (boolean) rewriteMslSamplersAndFp16.invokeExact(inSeg, stripDiscardSafety, outSeg, cap, outCount);
            if (!ok) {
                throw new IllegalStateException("metality_rewrite_msl_samplers_and_fp16 failed");
            }
            return new MslRewriteResult(
                    outSeg.getString(0, StandardCharsets.UTF_8),
                    outCount.get(ValueLayout.JAVA_INT, 0)
            );
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException("Failed in metality_rewrite_msl_samplers_and_fp16", t);
        }
    }

    public MemorySegment composerCreate(MemorySegment ctx) {
        try {
            return (MemorySegment) composerCreate.invokeExact(ctx);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_composer_create", t);
        }
    }

    public void composerDestroy(MemorySegment composer) {
        try {
            composerDestroy.invokeExact(composer);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_composer_destroy", t);
        }
    }

    public boolean composerExecute(
            MemorySegment ctx,
            MemorySegment composer,
            long colorTexHandle,
            long depthTexHandle,
            float[] invProj16,
            float[] viewRot16,
            float[] proj16,
            float sunX, float sunY, float sunZ,
            float skyR, float skyG, float skyB,
            int width, int height,
            float timeSeconds,
            float rainStrength,
            float bloomStrength,
            float ssaoStrength,
            float godrayStrength,
            float exposure,
            float fogDensity,
            int tonemapMode,
            boolean depthReversed
    ) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment u = temp.allocate(COMPOSER_UNIFORMS_LAYOUT);
            MemorySegment.copy(invProj16, 0, u, ValueLayout.JAVA_FLOAT, 0, 16);
            MemorySegment.copy(viewRot16, 0, u, ValueLayout.JAVA_FLOAT, 64, 16);
            MemorySegment.copy(proj16, 0, u, ValueLayout.JAVA_FLOAT, 128, 16);
            u.set(ValueLayout.JAVA_FLOAT, 192, sunX);
            u.set(ValueLayout.JAVA_FLOAT, 196, sunY);
            u.set(ValueLayout.JAVA_FLOAT, 200, sunZ);
            u.set(ValueLayout.JAVA_FLOAT, 204, 1.0f);
            u.set(ValueLayout.JAVA_FLOAT, 208, skyR);
            u.set(ValueLayout.JAVA_FLOAT, 212, skyG);
            u.set(ValueLayout.JAVA_FLOAT, 216, skyB);
            u.set(ValueLayout.JAVA_FLOAT, 220, 1.0f);
            u.set(ValueLayout.JAVA_INT, 224, width);
            u.set(ValueLayout.JAVA_INT, 228, height);
            u.set(ValueLayout.JAVA_FLOAT, 232, timeSeconds);
            u.set(ValueLayout.JAVA_FLOAT, 236, rainStrength);
            u.set(ValueLayout.JAVA_FLOAT, 240, bloomStrength);
            u.set(ValueLayout.JAVA_FLOAT, 244, ssaoStrength);
            u.set(ValueLayout.JAVA_FLOAT, 248, godrayStrength);
            u.set(ValueLayout.JAVA_FLOAT, 252, exposure);
            u.set(ValueLayout.JAVA_FLOAT, 256, fogDensity);
            u.set(ValueLayout.JAVA_INT, 260, tonemapMode);
            u.set(ValueLayout.JAVA_INT, 264, depthReversed ? 1 : 0);
            u.set(ValueLayout.JAVA_INT, 268, 0);
            return (boolean) composerExecute.invokeExact(ctx, composer, colorTexHandle, depthTexHandle, u);
        } catch (Throwable t) {
            throw new RuntimeException("Failed in metality_composer_execute", t);
        }
    }

    public void destroyContext(MemorySegment ctx) {
        try {
            contextDestroy.invokeExact(ctx);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to destroy Metal context", t);
        } finally {
            libraryArena.close();
        }
    }
}
