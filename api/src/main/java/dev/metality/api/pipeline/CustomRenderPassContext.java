package dev.metality.api.pipeline;

/**
 * 傳遞給外部渲染擴充鉤子的執行期上下文（不暴露任何 Minecraft 或 Metal 內部指標）。
 */
public interface CustomRenderPassContext {

    long frameIndex();

    int viewportWidth();

    int viewportHeight();

    float[] viewProjectionMatrix();

    double cameraWorldX();

    double cameraWorldY();

    double cameraWorldZ();

    /**
     * 設定供光影或後續合成 Pass 讀取的自訂命名浮點數 Uniform。
     */
    void setCustomUniformFloat(String uniformName, float value);

    void setCustomUniformVec4(String uniformName, float x, float y, float z, float w);
}
