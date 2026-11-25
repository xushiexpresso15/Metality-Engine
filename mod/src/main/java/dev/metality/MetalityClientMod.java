package dev.metality;

import dev.metality.api.MetalityApi;
import dev.metality.mc263.glass.ConnectedGlassBlockStateModel;
import dev.metality.mc263.gui.MetalityKeybinds;
import dev.metality.mc263.world.Mc263ChunkAdapter;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MetalityClientMod implements ClientModInitializer {

    public static final String MOD_ID = "metality";
    public static final Logger LOGGER = LoggerFactory.getLogger("Metality");

    @Override
    public void onInitializeClient() {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");
        if (!osName.toLowerCase().contains("mac") || !osArch.equals("aarch64")) {
            throw new UnsupportedOperationException(
                    "Metality strictly targets macOS on Apple Silicon (arm64). Current environment: "
                            + osName + " / " + osArch);
        }
        MetalityKeybinds.register();
        ConnectedGlassBlockStateModel.registerModelLoadingPlugin();
        if (MetalityApi.isAvailable()) {
            Mc263ChunkAdapter.ensureVanillaPresetsRegistered(MetalityApi.get().materials());
        }
        LOGGER.info("Metality Client Engine bootstrapped (Apple Silicon Native Metal 3/4 + RenderPearl Metal Backend).");
    }
}
