package dev.metality.api.pipeline;

import java.util.List;

/**
 * 允許延伸模組註冊自訂渲染階段鉤子與全域 Uniform 提供者，跨 Minecraft 版本維持穩定。
 */
public interface RenderGraphExtensionRegistry {

    void registerHook(String extensionId, CustomRenderPassHook.Stage stage, CustomRenderPassHook hook);

    boolean unregisterHook(String extensionId);

    List<CustomRenderPassHook> hooksForStage(CustomRenderPassHook.Stage stage);

    int totalRegisteredHooks();
}
