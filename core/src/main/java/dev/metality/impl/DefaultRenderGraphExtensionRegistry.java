package dev.metality.impl;

import dev.metality.api.pipeline.CustomRenderPassContext;
import dev.metality.api.pipeline.CustomRenderPassHook;
import dev.metality.api.pipeline.RenderGraphExtensionRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 執行緒安全且讀取零鎖之管線擴充鉤子註冊表。
 */
public final class DefaultRenderGraphExtensionRegistry implements RenderGraphExtensionRegistry {

    private record Entry(String extensionId, CustomRenderPassHook.Stage stage, CustomRenderPassHook hook) {}

    private final Map<CustomRenderPassHook.Stage, CopyOnWriteArrayList<Entry>> hooksByStage =
            new EnumMap<>(CustomRenderPassHook.Stage.class);

    public DefaultRenderGraphExtensionRegistry() {
        for (CustomRenderPassHook.Stage stage : CustomRenderPassHook.Stage.values()) {
            hooksByStage.put(stage, new CopyOnWriteArrayList<>());
        }
    }

    @Override
    public void registerHook(String extensionId, CustomRenderPassHook.Stage stage, CustomRenderPassHook hook) {
        Objects.requireNonNull(extensionId, "extensionId");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(hook, "hook");
        unregisterHook(extensionId);
        hooksByStage.get(stage).add(new Entry(extensionId, stage, hook));
    }

    @Override
    public boolean unregisterHook(String extensionId) {
        boolean removed = false;
        for (CopyOnWriteArrayList<Entry> list : hooksByStage.values()) {
            removed |= list.removeIf(e -> e.extensionId().equals(extensionId));
        }
        return removed;
    }

    @Override
    public List<CustomRenderPassHook> hooksForStage(CustomRenderPassHook.Stage stage) {
        return hooksByStage.get(stage).stream().map(Entry::hook).toList();
    }

    public void dispatchStage(CustomRenderPassHook.Stage stage, CustomRenderPassContext context) {
        CopyOnWriteArrayList<Entry> list = hooksByStage.get(stage);
        for (int i = 0, sz = list.size(); i < sz; ++i) {
            list.get(i).hook().execute(context);
        }
    }

    @Override
    public int totalRegisteredHooks() {
        int total = 0;
        for (CopyOnWriteArrayList<Entry> list : hooksByStage.values()) {
            total += list.size();
        }
        return total;
    }
}
