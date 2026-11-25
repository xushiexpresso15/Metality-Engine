# Changelog

本專案依循 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.1.0/) 格式與 [SemVer 2.0.0](docs/VERSIONING.md) 版本命名規則。

## [0.1.0-beta.1] - 2026-11-25

第一個完整功能與畫質打磨里程碑（由 `0.1.0-alpha` 正式晉升為 Beta）。

### Metality Engine (`metality-mc26.3-0.1.0-beta.1.jar`)
- **Apple Silicon 原生 Metal 3/4 渲染後端**：完整實作 Minecraft 26.3 RenderPearl Metal 後端、UMA 零拷貝區塊管線、ARM NEON 16-Byte Quad-Pulling Mesher、GPU ICB 剔除與 MetalFX 超解析度／去噪。
- **引擎級連體玻璃（Connected Glass）**：純引擎端實作程序化 47-tile 原生圖集 CTM 與玻璃片內部邊條剔除（支援全 35 種玻璃方塊與玻璃片、Fabric Indigo 與原版雙路徑、材質包邊框自適應、`F3+T` 熱重載，並於 Video Settings Engine 分頁提供即時開關）。
- **低畫質水體一致性**：移除原生水體隱藏與光影水體開關的耦合，確保 LOW 模式亦呈現物理衰減水體且切換設定不觸發區塊重載。
- **洞窟 CSM 漏光修復**：原生 CSM 光柵化改為雙面繪製（`MTLCullModeNone`）並修正近太陽面裁切，徹底阻斷深層洞窟 `-Y` 天花板太陽光漏光；修正 6 幀快取 Cascade 1 轉頭閃爍。
- **模組隔離**：`:api` 與 `:core` 維持零 `net/minecraft/` 與 `com/mojang/` 依賴。

### Meteor Dusk (`meteor-dusk-0.1.0-beta.1.zip`)
- **高效能非光追管線**：2-Cascade PCSS 軟陰影、1.0x 全解析度銳利生物陰影、多方向 GTAO ＋ SSGI 與 `1-2-1` 二項式濾波，於 Apple M4 Max Retina `3024×1842` ULTRA 達 **~200 FPS (`4.99 ms`)**。
- **柔和點光源系統**：移除 `ndL <= 0.12` 圓形斷層光環，導入世界空間解析軟半影體素 DDA 與更溫潤的火把／熔岩／發光地衣衰減曲線，並修復洞穴熔岩瀑布閃爍與邊緣亮框異常。
- **密閉室內與洞窟防漏光**：實作屋頂下方 4 方向體素出屋頂開闊度偵測與屋頂內陽光漏光防護，密閉石英空間不再過亮。
- **天空、體積光與夜景**：`RGBA16F` 天空／光束貼圖、FP32 AgX 色調映射、1.0x 無極點奇異銀河與極光、月光夜雲，以及修復薄玻璃片體積光束異常。

### Meteor Zenith (`meteor-zenith-0.1.0-beta.1.zip`)
- **硬體光追管線**：Apple M3–M5 Metal Hardware BVH（M1–M2 自動降級至 512×128×512 體素 DDA）、1-Bounce 彩色全域光照、時域累積與 À-Trous 降噪，於 Apple M4 Max Retina `3024×1842` ULTRA 達 **~144 FPS (`7.01 ms`)**。
- **與 Meteor Dusk 100% 共享核心美學**：天空、體積雲、體積光、FP32 AgX 色調映射、平衡日照／月光亮度、深水 Beer-Lambert 衰減與 1.0x 銳利生物陰影完全同步。
