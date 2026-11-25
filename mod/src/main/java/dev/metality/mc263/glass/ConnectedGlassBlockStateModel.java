package dev.metality.mc263.glass;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.client.renderer.v1.model.MeshQuadCollection;
import net.fabricmc.fabric.api.client.renderer.v1.model.ModelHelper;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric Renderer API ({@code fabric-renderer-api-v1}) wrapper for glass and glass-pane
 * {@link BlockStateModel}s.
 *
 * <p>Fabric API redirects {@code SectionCompiler.compile()} away from vanilla
 * {@code ModelBlockRenderer.tesselateBlock} into {@code AltModelBlockRenderer.tesselateBlock}
 * (Indigo / Sodium / custom renderers), which invokes {@link #emitQuads}. Wrapping only candidate
 * glass block states via {@link ModelLoadingPlugin} preserves zero overhead for all non-glass blocks,
 * matches {@code VanillaBlockModelPartEncoder.emitQuads} semantics verbatim, and shares the exact
 * zero-allocation {@link BakedQuad} CTM cache with the vanilla {@code ModelBlockRendererMixin}
 * fallback (mutually exclusive paths, zero double-processing).
 */
public final class ConnectedGlassBlockStateModel extends WrapperBlockStateModel {

    private static final ThreadLocal<List<BlockStateModelPart>> SCRATCH_PARTS =
            ThreadLocal.withInitial(() -> new ArrayList<>(8));

    public ConnectedGlassBlockStateModel(BlockStateModel wrapped) {
        super(wrapped);
    }

    /**
     * Registers Metality's {@link ModelLoadingPlugin} so every full glass block (18 types) and
     * glass pane (17 types) is wrapped automatically on initial boot and every {@code F3+T} reload.
     */
    public static void registerModelLoadingPlugin() {
        ModelLoadingPlugin.register(pluginContext ->
                pluginContext.modifyBlockModelAfterBake().register((model, context) -> {
                    if (model != null
                            && !(model instanceof ConnectedGlassBlockStateModel)
                            && ConnectedGlassEngine.isCandidateState(context.state())) {
                        return new ConnectedGlassBlockStateModel(model);
                    }
                    return model;
                })
        );
    }

    /**
     * Returns {@code null} because connected-glass quad UVs and rim culling depend on neighboring
     * block states and must never be cached solely by static model identity.
     */
    @Override
    public Object createGeometryKey(
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            RandomSource random
    ) {
        return null;
    }

    @Override
    public void emitQuads(
            QuadEmitter emitter,
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            RandomSource random,
            Predicate<Direction> cullTest
    ) {
        if (!ConnectedGlassEngine.isEnabled()
                || !ConnectedGlassEngine.isEligibleWorldRegion(level)
                || !ConnectedGlassEngine.isCandidateState(state)) {
            super.emitQuads(emitter, level, pos, state, random, cullTest);
            return;
        }

        List<BlockStateModelPart> parts = SCRATCH_PARTS.get();
        parts.clear();
        try {
            this.wrapped.collectParts(random, parts);
            if (parts.isEmpty()) {
                // Custom Fabric BlockStateModel that emits quads directly without BlockStateModelParts.
                super.emitQuads(emitter, level, pos, state, random, cullTest);
                return;
            }
            for (int p = 0, partCount = parts.size(); p < partCount; ++p) {
                BlockStateModelPart part = parts.get(p);
                if (part instanceof SimpleModelWrapper smw && smw.quads() instanceof MeshQuadCollection) {
                    // Preserve custom Fabric MeshQuadCollection models verbatim.
                    part.emitQuads(emitter, cullTest);
                    continue;
                }
                TriState ao = part.useAmbientOcclusion() ? TriState.DEFAULT : TriState.FALSE;
                for (int i = 0; i <= ModelHelper.NULL_FACE_ID; ++i) {
                    Direction cullFace = ModelHelper.faceFromIndex(i);
                    // Matches VanillaBlockModelPartEncoder.emitQuads polarity: true means cull/skip face.
                    if (cullTest.test(cullFace)) {
                        continue;
                    }
                    List<BakedQuad> quads = part.getQuads(cullFace);
                    for (int j = 0, quadCount = quads.size(); j < quadCount; ++j) {
                        BakedQuad quad = quads.get(j);
                        if (ConnectedGlassEngine.shouldCullGlassPaneRimQuad(level, state, pos, quad)) {
                            continue;
                        }
                        BakedQuad remapped = ConnectedGlassEngine.remapGlassQuad(level, state, pos, quad);
                        emitter.cullFace(cullFace);
                        emitter.fromBakedQuad(remapped);
                        emitter.ambientOcclusion(ao);
                        emitter.shadeMode(ShadeMode.VANILLA);
                        emitter.emit();
                    }
                }
            }
        } finally {
            parts.clear();
        }
    }
}
