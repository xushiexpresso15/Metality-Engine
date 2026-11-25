package dev.metality.mc263.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 存取 OptionsSubScreen 私有保留的上一畫面引用，供 Metality 設定頁正確返回上一層選單。
 */
@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccessor {

    @Accessor("lastScreen")
    Screen metality$getLastScreen();
}
