package dev.metality.mc263.mixin;

import dev.metality.mc263.shader.Mc263ShaderRuntime;
import dev.metality.mc263.world.Mc263ChunkAdapter;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 停用原版方向性面暗角（Cardinal Shading），將法線光照完全交由光追與 PBR 管線運算；
 * 並於多人伺服器卸載區塊時同步移除原生光追與體素區段。
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Unique
    private static final CardinalLighting METALITY_NEUTRAL_CARDINAL =
            new CardinalLighting(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

    @Inject(method = "cardinalLighting", at = @At("HEAD"), cancellable = true)
    private void metality$neutralCardinal(CallbackInfoReturnable<CardinalLighting> cir) {
        if (Mc263ShaderRuntime.isApertureWorldShaderActive()) {
            cir.setReturnValue(METALITY_NEUTRAL_CARDINAL);
        }
    }

    @Inject(method = "unload", at = @At("HEAD"))
    private void metality$onUnloadChunk(LevelChunk chunk, CallbackInfo ci) {
        if (chunk == null) {
            return;
        }
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        LevelChunkSection[] sections = chunk.getSections();
        if (sections != null) {
            for (int i = 0; i < sections.length; ++i) {
                int secY = chunk.getSectionYFromSectionIndex(i);
                Mc263ChunkAdapter.onSectionRemoved(cx, secY, cz);
            }
        }
    }
}
