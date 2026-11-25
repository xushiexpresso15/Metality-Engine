package dev.metality.api.pipeline;

/**
 * 外部延伸模組可實作的自訂渲染階段鉤子（例如遠景 LOD 幾何注入或實體光影參數更新）。
 */
@FunctionalInterface
public interface CustomRenderPassHook {

    enum Stage {
        PRE_TERRAIN,
        POST_SOLID_TERRAIN,
        POST_TRANSLUCENT,
        POST_COMPOSITE,
        CUSTOM_COMPUTE
    }

    void execute(CustomRenderPassContext context);
}
