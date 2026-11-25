package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.metality.backend.MetalGpuDevice;
import dev.metality.nativebridge.MetalityFFM;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RenderPearl Pipeline 之原生 Metal PSO 實作（SPIRV-Cross -> MTLLibrary + MTLBinaryArchive 快取）。
 * 依實際綁定之 RenderPass Color/Depth PixelFormat（含 MRT 多渲染目標）按需快取 PSO 變體。
 */
public final class MetalRenderPipeline implements BackendRenderPipeline {

    private final MetalGpuDevice metalDevice;
    private final String name;
    private final long vertexLibrary;
    private final long fragmentLibrary;
    private final String vertexEntry;
    private final String fragmentEntry;
    private final int[] blendModes;
    private final int[] writeMasks;
    private final int depthCompare;
    private final int cullMode;
    private final boolean depthWriteEnabled;
    private final float depthBiasConstant;
    private final float depthBiasSlope;
    private final boolean wireframe;
    private final int topology;
    private final boolean quads;
    private final boolean triangleFan;
    private final List<BindGroupLayout.UniformDescription> uniforms;
    private final MetalityFFM.VertexBufferLayoutDesc[] vbLayouts;
    private final MetalityFFM.VertexAttributeDesc[] attributes;
    private final ConcurrentHashMap<Long, Long> singlePsoCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MrtPsoKey, Long> mrtPsoCache = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    private record MrtPsoKey(int c0, int c1, int c2, int c3, int count, int depthFormat) {}

    private MetalRenderPipeline(
            MetalGpuDevice metalDevice,
            String name,
            long vertexLibrary,
            long fragmentLibrary,
            String vertexEntry,
            String fragmentEntry,
            int[] blendModes,
            int[] writeMasks,
            int depthCompare,
            int cullMode,
            boolean depthWriteEnabled,
            float depthBiasConstant,
            float depthBiasSlope,
            boolean wireframe,
            int topology,
            boolean quads,
            boolean triangleFan,
            List<BindGroupLayout.UniformDescription> uniforms,
            MetalityFFM.VertexBufferLayoutDesc[] vbLayouts,
            MetalityFFM.VertexAttributeDesc[] attributes,
            int[] defaultColorFormats,
            int defaultDepthFormat
    ) {
        this.metalDevice = metalDevice;
        this.name = name;
        this.vertexLibrary = vertexLibrary;
        this.fragmentLibrary = fragmentLibrary;
        this.vertexEntry = vertexEntry;
        this.fragmentEntry = fragmentEntry;
        this.blendModes = blendModes;
        this.writeMasks = writeMasks;
        this.depthCompare = depthCompare;
        this.cullMode = cullMode;
        this.depthWriteEnabled = depthWriteEnabled;
        this.depthBiasConstant = depthBiasConstant;
        this.depthBiasSlope = depthBiasSlope;
        this.wireframe = wireframe;
        this.topology = topology;
        this.quads = quads;
        this.triangleFan = triangleFan;
        this.uniforms = uniforms != null ? List.copyOf(uniforms) : List.of();
        this.vbLayouts = vbLayouts;
        this.attributes = attributes;
        // 預編譯預設附著格式之 PSO 至 MTLBinaryArchive，消除畫面繪製時首次卡頓
        getOrCreatePso(defaultColorFormats, defaultDepthFormat);
    }

    public static BackendRenderPipeline.Pending beginCompile(MetalGpuDevice metalDevice, BackendRenderPipeline.CreateInfo info) {
        return () -> compileNow(metalDevice, info);
    }

    private static MetalRenderPipeline compileNow(MetalGpuDevice metalDevice, BackendRenderPipeline.CreateInfo info) {
        long vsLib = 0L;
        long fsLib = 0L;
        String vsEntry = "main0";
        String fsEntry = "main0";

        for (BackendRenderPipeline.CreateInfo.Shader sh : info.shaders()) {
            String msl = SpvcMslShaderBridge.transpileSpvToMsl(sh.module());
            String entry = SpvcMslShaderBridge.mapMslEntryPoint(sh.entryPoint());
            long lib = metalDevice.compileMslLibrary(info.name() + ":" + sh.name(), msl, true);
            if (sh.module().type() == ShaderType.VERTEX) {
                vsLib = lib;
                vsEntry = entry;
            } else if (sh.module().type() == ShaderType.FRAGMENT) {
                fsLib = lib;
                fsEntry = entry;
            }
        }
        if (vsLib == 0L || fsLib == 0L) {
            throw new IllegalStateException("Pipeline " + info.name() + " is missing vertex or fragment shader");
        }

        List<ColorTargetState> colorTargets = info.colorTargetStates();
        int targetCount = (colorTargets != null) ? Math.min(4, colorTargets.size()) : 0;
        int[] blendModes = new int[4];
        int[] writeMasks = new int[4];
        Arrays.fill(blendModes, MetalityFFM.BLEND_OPAQUE);
        Arrays.fill(writeMasks, 0xF);
        int[] defaultColorFormats = new int[targetCount];
        for (int i = 0; i < targetCount; ++i) {
            ColorTargetState ct = colorTargets.get(i);
            blendModes[i] = MetalFormatMapper.mapBlendMode(ct.blendFunction());
            writeMasks[i] = ct.writeMask();
            defaultColorFormats[i] = ct.format() != null
                    ? MetalFormatMapper.mapPixelFormat(ct.format())
                    : MetalityFFM.FORMAT_RGBA8_UNORM;
        }

        DepthStencilState dss = info.depthStencilState();
        int depthCompare = dss != null ? MetalFormatMapper.mapDepthCompare(dss.depthTest()) : MetalityFFM.DEPTH_ALWAYS;
        boolean depthWrite = dss != null && dss.writeDepth();
        float depthBiasConstant = dss != null ? dss.depthBiasConstant() : 0.0f;
        float depthBiasSlope = dss != null ? dss.depthBiasScaleFactor() : 0.0f;
        int defaultDepthFormat = (depthWrite || depthCompare != MetalityFFM.DEPTH_ALWAYS)
                ? MetalityFFM.FORMAT_DEPTH32_FLOAT
                : 0;

        int cullMode = info.cull() ? MetalityFFM.CULL_BACK : MetalityFFM.CULL_NONE;
        boolean wireframe = info.polygonMode() == PolygonMode.WIREFRAME;
        PrimitiveTopology primTopo = info.primitiveTopology();
        int mappedTopo = MetalFormatMapper.mapTopology(primTopo);
        boolean isQuads = primTopo == PrimitiveTopology.QUADS;
        boolean isTriangleFan = primTopo == PrimitiveTopology.TRIANGLE_FAN;

        List<BackendRenderPipeline.CreateInfo.VertexBuffer> rawVbs = info.vertexBuffers();
        MetalityFFM.VertexBufferLayoutDesc[] vbDescs = new MetalityFFM.VertexBufferLayoutDesc[rawVbs.size()];
        for (int i = 0; i < rawVbs.size(); ++i) {
            var vb = rawVbs.get(i);
            vbDescs[i] = new MetalityFFM.VertexBufferLayoutDesc(vb.bufferSlot(), vb.stride(), vb.stepRate());
        }

        List<BackendRenderPipeline.CreateInfo.AttribBinding> rawAttrs = info.attribBindings();
        MetalityFFM.VertexAttributeDesc[] attrDescs = new MetalityFFM.VertexAttributeDesc[rawAttrs.size()];
        for (int i = 0; i < rawAttrs.size(); ++i) {
            var attr = rawAttrs.get(i);
            attrDescs[i] = new MetalityFFM.VertexAttributeDesc(
                    attr.location(),
                    attr.bufferSlot(),
                    attr.offset(),
                    MetalFormatMapper.mapVertexFormat(attr.format())
            );
        }

        return new MetalRenderPipeline(
                metalDevice, info.name(), vsLib, fsLib, vsEntry, fsEntry,
                blendModes, writeMasks, depthCompare, cullMode, depthWrite,
                depthBiasConstant, depthBiasSlope,
                wireframe, mappedTopo, isQuads, isTriangleFan, info.uniforms(), vbDescs, attrDescs,
                defaultColorFormats, defaultDepthFormat
        );
    }

    public long getOrCreatePso(int colorFormat, int depthFormat) {
        int[] formats = colorFormat != 0 ? new int[]{colorFormat} : new int[0];
        return getOrCreatePso(formats, depthFormat);
    }

    public long getOrCreatePso(int[] colorFormats, int depthFormat) {
        if (closed) {
            throw new IllegalStateException("RenderPipeline " + name + " has been closed");
        }
        int count = colorFormats != null ? Math.min(4, colorFormats.length) : 0;
        if (count <= 1) {
            int cf = count == 1 ? colorFormats[0] : 0;
            long key = (((long) cf) << 32) | (depthFormat & 0xFFFFFFFFL);
            Long existing = singlePsoCache.get(key);
            if (existing != null) {
                return existing;
            }
            return singlePsoCache.computeIfAbsent(key, ignored -> metalDevice.createRenderPsoEx(
                    vertexLibrary, fragmentLibrary, vertexEntry, fragmentEntry,
                    cf, depthFormat, blendModes[0], depthCompare, cullMode,
                    depthWriteEnabled, writeMasks[0], wireframe, topology,
                    vbLayouts, attributes
            ));
        }

        int c0 = colorFormats[0];
        int c1 = count > 1 ? colorFormats[1] : 0;
        int c2 = count > 2 ? colorFormats[2] : 0;
        int c3 = count > 3 ? colorFormats[3] : 0;
        MrtPsoKey key = new MrtPsoKey(c0, c1, c2, c3, count, depthFormat);
        Long existing = mrtPsoCache.get(key);
        if (existing != null) {
            return existing;
        }
        return mrtPsoCache.computeIfAbsent(key, ignored -> {
            int[] resolvedFormats = new int[count];
            int[] resolvedBlends = new int[count];
            int[] resolvedMasks = new int[count];
            for (int i = 0; i < count; ++i) {
                resolvedFormats[i] = colorFormats[i];
                resolvedBlends[i] = blendModes[i];
                resolvedMasks[i] = writeMasks[i];
            }
            return metalDevice.createRenderPsoMrt(
                    vertexLibrary, fragmentLibrary, vertexEntry, fragmentEntry,
                    count, resolvedFormats, resolvedBlends, resolvedMasks,
                    depthFormat, depthCompare, cullMode, depthWriteEnabled,
                    wireframe, topology, vbLayouts, attributes
            );
        });
    }

    public BindGroupLayout.UniformDescription uniformAt(int index) {
        return (index >= 0 && index < uniforms.size()) ? uniforms.get(index) : null;
    }

    public boolean isQuads() {
        return quads;
    }

    public boolean isTriangleFan() {
        return triangleFan;
    }

    public float depthBiasConstant() {
        return depthBiasConstant;
    }

    public float depthBiasSlope() {
        return depthBiasSlope;
    }

    public String name() {
        return name;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
