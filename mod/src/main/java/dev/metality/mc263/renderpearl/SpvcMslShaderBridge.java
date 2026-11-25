package dev.metality.mc263.renderpearl;

import com.mojang.renderpearl.backend.api.SpvModule;
import dev.metality.nativebridge.MetalityFFM;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

/**
 * 透過 Minecraft 26.3 原生附帶之 LWJGL SPIRV-Cross (Spvc) 將 RenderPearl SpvModule 轉譯為 Metal 3.1 MSL。
 */
public final class SpvcMslShaderBridge {

    private static final int SPVC_SUCCESS = 0;
    private static final int MSL_VERSION_3_1 = (3 << 16) | (1 << 8);

    private SpvcMslShaderBridge() {}

    public static String mapMslEntryPoint(String spvEntry) {
        if (spvEntry == null || spvEntry.isEmpty() || "main".equals(spvEntry)) {
            return "main0";
        }
        return spvEntry;
    }

    public static String transpileSpvToMsl(SpvModule module) {
        return transpileSpvBytesToMsl(module.spv());
    }

    public static String transpileSpvBytesToMsl(ByteBuffer spirvBytes) {
        if (spirvBytes == null || spirvBytes.remaining() < 4 || (spirvBytes.remaining() % 4) != 0) {
            throw new IllegalArgumentException("Invalid SPIR-V bytecode buffer size: "
                    + (spirvBytes == null ? 0 : spirvBytes.remaining()));
        }

        int wordCount = spirvBytes.remaining() / 4;
        IntBuffer wordBuf = MemoryUtil.memAllocInt(wordCount);
        ByteBuffer dup = spirvBytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < wordCount; ++i) {
            wordBuf.put(i, dup.getInt());
        }

        long ctx = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pCtx = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pCtx), "spvc_context_create");
            ctx = pCtx.get(0);

            PointerBuffer pIr = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_parse_spirv(ctx, wordBuf, wordCount, pIr), "spvc_context_parse_spirv", ctx);
            long ir = pIr.get(0);

            PointerBuffer pCompiler = stack.mallocPointer(1);
            checkSpvc(
                    Spvc.spvc_context_create_compiler(
                            ctx, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pCompiler
                    ),
                    "spvc_context_create_compiler",
                    ctx
            );
            long compiler = pCompiler.get(0);

            PointerBuffer pOpts = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_compiler_create_compiler_options(compiler, pOpts), "spvc_compiler_create_compiler_options", ctx);
            long opts = pOpts.get(0);

            checkSpvc(Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS),
                    "set MSL_PLATFORM", ctx);
            checkSpvc(Spvc.spvc_compiler_options_set_uint(opts, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_3_1),
                    "set MSL_VERSION", ctx);
            checkSpvc(Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true),
                    "set MSL_ENABLE_DECORATION_BINDING", ctx);
            checkSpvc(Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true),
                    "set MSL_TEXTURE_BUFFER_NATIVE", ctx);
            checkSpvc(Spvc.spvc_compiler_options_set_bool(opts, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true),
                    "set FLIP_VERTEX_Y", ctx);
            checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, opts), "install_compiler_options", ctx);

            // 將 RenderPearl Push Constants 明確綁定至 Metal [[buffer(19)]]（保留 0..18 給 Uniform、20..30 給 Vertex Buffers）
            registerPushConstantBinding(compiler, stack, ctx, Spv.SpvExecutionModelVertex);
            registerPushConstantBinding(compiler, stack, ctx, Spv.SpvExecutionModelFragment);

            PointerBuffer pOut = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_compiler_compile(compiler, pOut), "spvc_compiler_compile", ctx);
            return MemoryUtil.memUTF8(pOut.get(0));
        } finally {
            if (ctx != 0L) {
                Spvc.spvc_context_destroy(ctx);
            }
            MemoryUtil.memFree(wordBuf);
        }
    }

    private static void registerPushConstantBinding(long compiler, MemoryStack stack, long ctx, int stage) {
        SpvcMslResourceBinding rb = SpvcMslResourceBinding.calloc(stack);
        Spvc.spvc_msl_resource_binding_init(rb);
        rb.stage(stage);
        rb.desc_set(Spvc.SPVC_MSL_PUSH_CONSTANT_DESC_SET);
        rb.binding(Spvc.SPVC_MSL_PUSH_CONSTANT_BINDING);
        rb.msl_buffer(MetalityFFM.PUSH_CONSTANT_SLOT);
        checkSpvc(Spvc.spvc_compiler_msl_add_resource_binding(compiler, rb), "add push_constant binding", ctx);
    }

    private static void checkSpvc(int rc, String step) {
        if (rc != SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross failed at " + step + " (code=" + rc + ")");
        }
    }

    private static void checkSpvc(int rc, String step, long ctx) {
        if (rc != SPVC_SUCCESS) {
            String msg = Spvc.spvc_context_get_last_error_string(ctx);
            throw new IllegalStateException("SPIRV-Cross failed at " + step + ": " + msg);
        }
    }
}
