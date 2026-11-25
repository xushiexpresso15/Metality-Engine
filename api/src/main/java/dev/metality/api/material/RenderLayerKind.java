package dev.metality.api.material;

/**
 * 方塊或流體在 TBDR 管線中的幾何批次層別（直接決定是否啟用 Apple GPU 硬體隱藏面消除 HSR）。
 */
public enum RenderLayerKind {
    EMPTY(0),
    SOLID(1),
    CUTOUT(2),
    TRANSLUCENT(3);

    private final int nativeCode;

    RenderLayerKind(int nativeCode) {
        this.nativeCode = nativeCode;
    }

    public int nativeCode() {
        return nativeCode;
    }
}
