package dev.metality.mc263.mixin;

import dev.metality.mc263.shader.Mc263ShaderRuntime;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SuspendedParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 抑制水上視角時原版不透明深度寫入的水下懸浮粒子（ParticleTypes.UNDERWATER），
 * 避免在 suppressVanillaWater=true 下於水中寫入孤立不透明深度點並被誤判為水下水草產生白色螢火點。
 */
@Mixin(SuspendedParticle.UnderwaterProvider.class)
public abstract class SuspendedParticleMixin {

    @Inject(
            method = "createParticle(Lnet/minecraft/core/particles/SimpleParticleType;Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDDLnet/minecraft/util/RandomSource;)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void metality$suppressOpaqueUnderwaterFireflies(
            SimpleParticleType type,
            ClientLevel level,
            double x,
            double y,
            double z,
            double xd,
            double yd,
            double zd,
            RandomSource random,
            CallbackInfoReturnable<Particle> cir
    ) {
        if (Mc263ShaderRuntime.shouldSuppressUnderwaterParticles()) {
            cir.setReturnValue(null);
        }
    }
}
