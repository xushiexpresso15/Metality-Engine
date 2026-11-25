package dev.metality.mc263.mixin;

import dev.metality.mc263.gui.MetalityVideoSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 攔截原版顯示設定視窗切換，改為開啟 Metality 整合引擎與光影設定介面（可隨時切換回原版）。
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    public abstract void setScreen(Screen screen);

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void metality$redirectVideoSettings(Screen screen, CallbackInfo ci) {
        if (screen instanceof VideoSettingsScreen vss && !MetalityVideoSettingsScreen.consumeVanillaBypass()) {
            ci.cancel();
            Screen parent = ((OptionsSubScreenAccessor) vss).metality$getLastScreen();
            this.setScreen(new MetalityVideoSettingsScreen(
                    parent,
                    this.minecraft,
                    this.minecraft.options,
                    MetalityVideoSettingsScreen.Tab.GENERAL
            ));
        }
    }
}
