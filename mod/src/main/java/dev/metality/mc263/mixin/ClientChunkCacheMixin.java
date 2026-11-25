package dev.metality.mc263.mixin;

import dev.metality.mc263.world.Mc263ChunkAdapter;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 攔截多人伺服器區塊封包覆寫與空區段變更事件：
 * 當伺服器以新封包原地覆寫既有 (cx, cz) 區塊或區段變為純空氣時，原版不會針對空區段呼叫 SectionCompiler.compile()，
 * 此處主動清除舊大廳殘留的體素與光追區段。
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {

    @Shadow
    public abstract int getLoadedChunksCount();

    @Inject(method = "onSectionEmptinessChanged", at = @At("HEAD"))
    private void metality$onSectionEmptinessChanged(int secX, int secY, int secZ, boolean empty, CallbackInfo ci) {
        if (empty) {
            Mc263ChunkAdapter.onSectionRemoved(secX, secY, secZ);
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void metality$onReplaceWithPacketData(
            int x,
            int z,
            ClientboundLevelChunkPacketData data,
            CallbackInfoReturnable<LevelChunk> cir
    ) {
        LevelChunk chunk = cir.getReturnValue();
        if (chunk == null) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null) {
            return;
        }
        for (int i = 0; i < sections.length; ++i) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir()) {
                int secY = chunk.getSectionYFromSectionIndex(i);
                Mc263ChunkAdapter.onSectionRemoved(x, secY, z);
            }
        }
    }

    @Inject(method = "updateViewRadius", at = @At("RETURN"))
    private void metality$onUpdateViewRadius(int viewDistance, CallbackInfo ci) {
        if (this.getLoadedChunksCount() == 0) {
            Mc263ChunkAdapter.invalidateAllSections();
        }
    }
}
