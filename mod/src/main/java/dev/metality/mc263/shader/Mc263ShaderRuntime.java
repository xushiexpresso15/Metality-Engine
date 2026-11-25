package dev.metality.mc263.shader;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.metality.api.pipeline.CustomRenderPassContext;
import dev.metality.api.pipeline.CustomRenderPassHook;
import dev.metality.api.shader.ShaderPackFormat;
import dev.metality.api.shader.ShaderPackOption;
import dev.metality.api.shader.ShaderPackService.ActivePackInfo;
import dev.metality.config.MetalitySettingsConfig;
import dev.metality.impl.DefaultMetalityEngine;
import dev.metality.mc263.mixin.GameRendererAccessor;
import dev.metality.mc263.renderpearl.MetalDeviceBackend;
import dev.metality.mc263.renderpearl.MetalPearlTexture;
import dev.metality.mc263.world.Mc263ChunkAdapter;
import dev.metality.nativebridge.MetalityFFM;
import dev.metality.shader.ShaderPackScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft 26.3 世界光影即時排程與執行橋接器：
 * 於 {@code LevelRenderer.render} 返回前（HUD 與截圖 Readback 之前），
 * 自動載入 {@code -Dmetality.shaderpack}，重建硬體 RT TLAS（若啟用），
 * 並以跨維度統一影格參數執行 Aperture pack.ts 或 Legacy Iris 管線。
 */
public final class Mc263ShaderRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("Metality/ShaderRuntime");
    private static volatile boolean autoLoadAttempted = false;
    private static volatile Boolean cachedStartupApertureActive = null;
    private static long frameCounter = 0L;
    private static final Matrix4f lastViewProjMat = new Matrix4f();
    private static double lastCamX = 0.0;
    private static double lastCamY = 0.0;
    private static double lastCamZ = 0.0;
    private static int lastDimId = Integer.MIN_VALUE;
    private static ClientLevel lastClientLevel = null;
    private static boolean hasHistory = false;
    private static volatile boolean fastStateInitialized = false;
    private static volatile boolean fastApertureActive = false;
    private static volatile boolean fastSuppressVanillaWater = false;
    private static volatile Boolean fastConnectedGlass = null;
    private static volatile boolean fastDisableShade = false;
    private static volatile boolean fastSuppressUnderwaterParticles = false;
    private static volatile Boolean cachedStartupDisableShade = null;
    private static boolean lastApertureActive = false;
    private static boolean lastWaterSuppressed = false;
    private static boolean lastDisableShade = false;
    private static volatile boolean pendingLightmapRefresh = false;

    // Reusable render-thread scratch objects (zero per-frame heap allocations!)
    private static final Matrix4f SCRATCH_PROJ = new Matrix4f();
    private static final Matrix4f SCRATCH_RAW_VIEW_ROT = new Matrix4f();
    private static final Matrix4f SCRATCH_VIEW_ROT = new Matrix4f();
    private static final Matrix4f SCRATCH_BOB = new Matrix4f();
    private static final Matrix4f SCRATCH_BOB_INV = new Matrix4f();
    private static final Matrix4f SCRATCH_RAW_VIEW_INV = new Matrix4f();
    private static final Matrix4f SCRATCH_BOB_ROT = new Matrix4f();
    private static final Matrix4f SCRATCH_INV_PROJ = new Matrix4f();
    private static final Matrix4f SCRATCH_INV_VIEW_ROT = new Matrix4f();
    private static final Matrix4f SCRATCH_VIEW_PROJ = new Matrix4f();
    private static final Matrix4f SCRATCH_INV_VIEW_PROJ = new Matrix4f();
    private static final Matrix4f SCRATCH_REPROJ = new Matrix4f();
    private static final Vector3f SCRATCH_VEC1 = new Vector3f();
    private static final Vector3f SCRATCH_VEC2 = new Vector3f();
    private static final Vector3f SCRATCH_RIGHT = new Vector3f();
    private static final Vector3f SCRATCH_UP = new Vector3f();
    private static final Vector3f SCRATCH_FWD = new Vector3f();
    private static final PoseStack SCRATCH_POSE_STACK = new PoseStack();
    private static final BlockPos.MutableBlockPos SCRATCH_BLOCK_POS = new BlockPos.MutableBlockPos();

    private static final float[] BUF_INV_PROJ = new float[16];
    private static final float[] BUF_PROJ = new float[16];
    private static final float[] BUF_VIEW_ROT = new float[16];
    private static final float[] BUF_INV_VIEW_ROT = new float[16];
    private static final float[] BUF_INV_VIEW_PROJ = new float[16];
    private static final float[] BUF_CUR_VIEW_PROJ = new float[16];
    private static final float[] BUF_PREV_VIEW_PROJ = new float[16];
    private static final float[] BUF_CAM_POS = new float[4];
    private static final float[] BUF_SUN_DIR = new float[4];
    private static final float[] BUF_MOON_DIR = new float[4];
    private static final float[] BUF_SKY_COL = new float[4];
    private static final float[] BUF_FOG_COL = new float[4];
    private static final float[] BUF_SUNRISE_COL = new float[4];
    private static final int[] BUF_VOXEL_ZERO = new int[4];

    private static final int MAX_DYNAMIC_LIGHTS = 96;
    private static final float[] BUF_DYN_LIGHTS = new float[MAX_DYNAMIC_LIGHTS * 8];
    private static final float[] BUF_TMP_ITEM = new float[5];
    private static final float[] BUF_RIPPLES = new float[64 * 8];
    private static final float[] BUF_ENTITY_SHADOWS = new float[256 * 8];
    private static final double[] BUF_BOX_DIST_SQ = new double[256];
    private static final float[] BUF_TMP8 = new float[8];
    private static final List<DynamicLightCandidate> SCRATCH_ENTITY_LIGHTS = new ArrayList<>(64);
    private static final List<EntityShadowCandidate> SCRATCH_SHADOW_CANDS = new ArrayList<>(64);
    private static final List<Entity> SCRATCH_SHADOW_ORDERED = new ArrayList<>(65);

    private static final class MutableHookContext implements CustomRenderPassContext {
        long frameIndex;
        int viewportWidth;
        int viewportHeight;
        double camX, camY, camZ;
        @Override public long frameIndex() { return frameIndex; }
        @Override public int viewportWidth() { return viewportWidth; }
        @Override public int viewportHeight() { return viewportHeight; }
        @Override public float[] viewProjectionMatrix() { return BUF_CUR_VIEW_PROJ; }
        @Override public double cameraWorldX() { return camX; }
        @Override public double cameraWorldY() { return camY; }
        @Override public double cameraWorldZ() { return camZ; }
        @Override public void setCustomUniformFloat(String uniformName, float value) {}
        @Override public void setCustomUniformVec4(String uniformName, float x, float y, float z, float w) {}
    }
    private static final MutableHookContext HOOK_CTX = new MutableHookContext();

    private static final int MAX_WATER_RIPPLES = 64;
    private static final float RIPPLE_LIFETIME_SEC = 2.8f;
    private static final int MAX_ENTITY_SHADOW_CASTERS = 256;
    private static final List<RippleEvent> ACTIVE_RIPPLES = new ArrayList<>();
    private static final Map<UUID, EntityWaterState> ENTITY_WATER_STATES = new HashMap<>();
    private static ClientLevel lastRippleLevel = null;
    private static float lastRippleSampleSec = -1.0f;

    private record RippleEvent(
            double wx, double wy, double wz,
            float birthSec, float dirX, float dirZ, float amplitude, float kind
    ) {}

    private static final class EntityWaterState {
        double ex, ey, ez;
        double lastWakeX, lastWakeZ;
        boolean wasInWater;
        float lastSampleSec;
        float lastWakeSec;
        float lastOarSec;
        float lastIdleSec;
        int oarSide = 1;
        long seenFrame;
    }

    public record PackActionResult(boolean success, String message) {}

    private Mc263ShaderRuntime() {}

    /** Lock-free hot-path query for FluidRendererMixin (per-block chunk meshing). */
    public static boolean shouldSuppressVanillaWaterMesh() {
        if (fastStateInitialized) {
            return fastSuppressVanillaWater;
        }
        return computeSuppressVanillaWaterSlow();
    }

    /** Lock-free hot-path query for ConnectedGlassEngine (works with shaders ON or OFF). */
    public static boolean isConnectedGlassEnabled() {
        Boolean cached = fastConnectedGlass;
        if (cached != null) {
            return cached;
        }
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isPresent()) {
            boolean enabled = backendOpt.get().settingsConfig().isConnectedGlass();
            fastConnectedGlass = enabled;
            return enabled;
        }
        return true;
    }

    /** Lock-free hot-path query for BlockModelLighterMixin (per-block-face chunk meshing). */
    public static boolean isDisableShadeActive() {
        if (fastStateInitialized) {
            return fastDisableShade;
        }
        return computeDisableShadeSlow();
    }

    /** Lock-free hot-path query for SuspendedParticleMixin (kills opaque underwater dust fireflies above water). */
    public static boolean shouldSuppressUnderwaterParticles() {
        return fastSuppressUnderwaterParticles;
    }

    /** Lock-free hot-path query for RenderSectionRegionMixin / LightmapMixin / SkyRendererMixin. */
    public static boolean isApertureWorldShaderActive() {
        if (fastStateInitialized) {
            return fastApertureActive;
        }
        return computeApertureActiveSlow();
    }

    private static boolean computeSuppressVanillaWaterSlow() {
        return isWaterSuppressionConfigured() && computeApertureActiveSlow();
    }

    private static boolean computeDisableShadeSlow() {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isPresent()) {
            DefaultMetalityEngine eng = backendOpt.get().publicEngine();
            if (eng.shaders().activePackInfo().isPresent()) {
                return eng.nativeShaders().isDisableShadeEnabled();
            }
            if (autoLoadAttempted) {
                return false;
            }
            Boolean cached = cachedStartupDisableShade;
            if (cached != null) {
                return cached;
            }
            Path configured = resolveStartupPackPath(backendOpt.get());
            boolean detected = detectStartupDisableShade(configured);
            cachedStartupDisableShade = detected;
            return detected;
        }
        Boolean cached = cachedStartupDisableShade;
        if (cached != null) {
            return cached;
        }
        String prop = System.getProperty("metality.shaderpack");
        boolean detected = (prop != null && !prop.isBlank()) && detectStartupDisableShade(Path.of(prop.trim()));
        cachedStartupDisableShade = detected;
        return detected;
    }

    private static boolean detectStartupDisableShade(Path packDir) {
        if (packDir == null) {
            return false;
        }
        Path packTs = packDir.resolve("pack.ts");
        if (!Files.isRegularFile(packTs)) {
            return false;
        }
        try {
            String src = Files.readString(packTs);
            return src.contains("disableShade = true") || src.contains("disableShade: true");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isWaterSuppressionConfigured() {
        return MetalDeviceBackend.activeInstance()
                .map(b -> b.settingsConfig().isSuppressVanillaWater())
                .orElse(true);
    }

    private static boolean computeApertureActiveSlow() {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isPresent()) {
            Optional<ActivePackInfo> active = backendOpt.get().publicEngine().shaders().activePackInfo();
            if (active.isPresent()) {
                return active.get().format() == ShaderPackFormat.APERTURE_TS;
            }
            if (autoLoadAttempted) {
                return false;
            }
            Boolean cached = cachedStartupApertureActive;
            if (cached != null) {
                return cached;
            }
            Path configured = resolveStartupPackPath(backendOpt.get());
            boolean detected = configured != null && Files.exists(configured.resolve("pack.ts"));
            cachedStartupApertureActive = detected;
            return detected;
        }
        Boolean cached = cachedStartupApertureActive;
        if (cached != null) {
            return cached;
        }
        String prop = System.getProperty("metality.shaderpack");
        boolean detected = false;
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop.trim());
            detected = Files.exists(p.resolve("pack.ts"));
        }
        cachedStartupApertureActive = detected;
        return detected;
    }

    public static Path shaderPacksDirectory() {
        return MetalDeviceBackend.activeInstance()
                .map(b -> b.gameDir().resolve("shaderpacks"))
                .orElseGet(() -> Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks"));
    }

    public static String packKeyFor(Path gameDir, Path packPath) {
        if (packPath == null) {
            return "";
        }
        Path fileName = packPath.getFileName();
        return fileName != null ? fileName.toString() : packPath.toString();
    }

    public static Path resolveConfiguredPackPath(MetalDeviceBackend backend) {
        if (backend == null) {
            return null;
        }
        String configured = backend.settingsConfig().getActiveShaderPack();
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured.trim());
            if (!candidate.isAbsolute()) {
                candidate = backend.gameDir().resolve("shaderpacks").resolve(candidate);
            }
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        String prop = System.getProperty("metality.shaderpack");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop.trim());
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    private static Path resolveStartupPackPath(MetalDeviceBackend backend) {
        String prop = System.getProperty("metality.shaderpack");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop.trim());
        }
        MetalitySettingsConfig cfg = backend.settingsConfig();
        if (!cfg.isShadersEnabled()) {
            return null;
        }
        return resolveConfiguredPackPath(backend);
    }

    public static void ensureConfiguredPackLoadedForUi() {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty()) {
            return;
        }
        MetalDeviceBackend backend = backendOpt.get();
        DefaultMetalityEngine engine = backend.publicEngine();
        if (engine.shaders().activePackInfo().isPresent() || !backend.settingsConfig().isShadersEnabled()) {
            return;
        }
        Path packPath = resolveStartupPackPath(backend);
        if (packPath == null || !Files.exists(packPath)) {
            return;
        }
        int[] viewport = resolveViewportSize();
        ensureStartupShaderPackLoaded(backend, engine, viewport[0], viewport[1]);
    }

    public static PackActionResult applyShaderPackSelection(boolean enabled, Path packPath) {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty()) {
            return new PackActionResult(false, "Metal 裝置後端尚未初始化");
        }
        MetalDeviceBackend backend = backendOpt.get();
        DefaultMetalityEngine engine = backend.publicEngine();
        MetalitySettingsConfig cfg = backend.settingsConfig();

        cfg.setShadersEnabled(enabled);
        if (packPath != null) {
            String stored = toConfigPackReference(backend.gameDir(), packPath);
            cfg.setActiveShaderPack(stored);
        }
        cfg.save();

        if (!enabled || packPath == null) {
            engine.shaders().unloadPack();
            autoLoadAttempted = true;
            cachedStartupApertureActive = false;
            hasHistory = false;
            engine.nativeRayTracingScene().setEntityShadows(null, 0);
            requestLightmapRefresh();
            syncWorldRenderState(false, false, false);
            return new PackActionResult(true, "光影已關閉 (原生渲染)");
        }

        if (!Files.exists(packPath)) {
            return new PackActionResult(false, "找不到光影包路徑: " + packPath.getFileName());
        }

        int[] viewport = resolveViewportSize();
        try {
            Map<String, String> overrides = MetalitySettingsConfig.loadPackOverrides(
                    backend.gameDir(), packKeyFor(backend.gameDir(), packPath)
            );
            ActivePackInfo info = engine.shaders().loadPack(packPath, viewport[0], viewport[1], 1.0f, overrides);
            autoLoadAttempted = true;
            boolean apertureActive = (info.format() == ShaderPackFormat.APERTURE_TS);
            cachedStartupApertureActive = apertureActive;
            hasHistory = false;
            engine.rayTracing().setEnabled(
                    cfg.isHardwareRayTracing() && backend.metalDevice().capabilities().hardwareRayTracing()
            );
            requestLightmapRefresh();
            boolean suppressWater = apertureActive && cfg.isSuppressVanillaWater();
            boolean disableShade = apertureActive && engine.nativeShaders().isDisableShadeEnabled();
            syncWorldRenderState(apertureActive, suppressWater, disableShade);
            LOGGER.info("[Metality] Switched shader pack to '{}' (format={}, overrides={})",
                    info.packName(), info.format(), overrides.size());
            return new PackActionResult(true, "已載入光影包: " + info.packName());
        } catch (Throwable t) {
            LOGGER.error("[Metality] Failed to switch shader pack to {}", packPath, t);
            return new PackActionResult(false, "載入失敗: " + t.getMessage());
        }
    }

    public static PackActionResult toggleShaders() {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty()) {
            return new PackActionResult(false, "Metal 裝置後端尚未初始化");
        }
        MetalDeviceBackend backend = backendOpt.get();
        DefaultMetalityEngine engine = backend.publicEngine();
        MetalitySettingsConfig cfg = backend.settingsConfig();

        boolean currentlyLoaded = engine.shaders().activePackInfo().isPresent();
        if (currentlyLoaded) {
            Path current = engine.shaders().activePackPath().orElseGet(() -> resolveConfiguredPackPath(backend));
            return applyShaderPackSelection(false, current);
        }

        Path target = resolveConfiguredPackPath(backend);
        if (target == null) {
            List<ShaderPackScanner.DiscoveredPack> found = ShaderPackScanner.scan(shaderPacksDirectory(), null);
            if (!found.isEmpty()) {
                target = found.get(0).path();
            }
        }
        if (target == null) {
            return new PackActionResult(false, "shaderpacks 資料夾內尚無可用的光影包");
        }
        return applyShaderPackSelection(true, target);
    }

    public static PackActionResult reloadActiveShaderPack() {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty()) {
            return new PackActionResult(false, "Metal 裝置後端尚未初始化");
        }
        MetalDeviceBackend backend = backendOpt.get();
        DefaultMetalityEngine engine = backend.publicEngine();
        if (engine.shaders().activePackInfo().isEmpty()) {
            Path configured = resolveConfiguredPackPath(backend);
            if (configured != null && backend.settingsConfig().isShadersEnabled()) {
                return applyShaderPackSelection(true, configured);
            }
            return new PackActionResult(false, "目前沒有已啟用的光影包可重載");
        }
        try {
            ActivePackInfo info = engine.shaders().reloadActivePack();
            hasHistory = false;
            requestLightmapRefresh();
            boolean apertureActive = (info.format() == ShaderPackFormat.APERTURE_TS);
            boolean suppressWater = apertureActive && backend.settingsConfig().isSuppressVanillaWater();
            boolean disableShade = apertureActive && engine.nativeShaders().isDisableShadeEnabled();
            syncWorldRenderState(apertureActive, suppressWater, disableShade);
            return new PackActionResult(true, "已重新載入光影包: " + info.packName());
        } catch (Throwable t) {
            LOGGER.error("[Metality] Failed to reload active shader pack", t);
            return new PackActionResult(false, "重載失敗: " + t.getMessage());
        }
    }

    public static void applyEngineSettingsLive(MetalitySettingsConfig updated) {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty() || updated == null) {
            return;
        }
        MetalDeviceBackend backend = backendOpt.get();
        DefaultMetalityEngine engine = backend.publicEngine();
        MetalitySettingsConfig cfg = backend.settingsConfig();
        float prevScale = backend.metalDevice().shaderRenderScale();
        boolean prevConnectedGlass = cfg.isConnectedGlass();

        cfg.applyFrom(updated);
        cfg.save();

        boolean newConnectedGlass = cfg.isConnectedGlass();
        fastConnectedGlass = newConnectedGlass;
        if (prevConnectedGlass != newConnectedGlass) {
            Minecraft mcInstance = Minecraft.getInstance();
            if (mcInstance.levelExtractor != null && mcInstance.level != null) {
                mcInstance.levelExtractor.allChanged();
            }
        }

        backend.metalDevice().setEdrEnabled(cfg.resolveEffectiveEdr());
        backend.metalDevice().setMaxFramesInFlight(cfg.getMaxFramesInFlight());

        float effScale = cfg.getShaderRenderScale();
        if (cfg.isThermalAdaptiveScaling()) {
            effScale = Math.min(effScale, engine.telemetry().recommendedThermalRenderScale());
        }
        if (Math.abs(prevScale - effScale) > 1e-4f) {
            hasHistory = false;
        }
        backend.metalDevice().setShaderRenderScale(effScale);
        engine.rayTracing().setEnabled(
                cfg.isHardwareRayTracing() && backend.metalDevice().capabilities().hardwareRayTracing()
        );
        boolean apertureActive = computeApertureActiveSlow();
        boolean suppressWater = apertureActive && cfg.isSuppressVanillaWater();
        boolean disableShade = apertureActive && engine.nativeShaders().isDisableShadeEnabled();
        syncWorldRenderState(apertureActive, suppressWater, disableShade);
    }

    public static void saveActivePackOverrides(DefaultMetalityEngine engine) {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty() || engine == null) {
            return;
        }
        Optional<Path> packPathOpt = engine.shaders().activePackPath();
        if (packPathOpt.isEmpty()) {
            return;
        }
        MetalDeviceBackend backend = backendOpt.get();
        Map<String, String> nonDefaults = new java.util.LinkedHashMap<>();
        for (ShaderPackOption opt : engine.shaders().activePackOptions()) {
            if (!opt.currentValue().equals(opt.defaultValue())) {
                nonDefaults.put(opt.name(), opt.currentValue());
            }
        }
        MetalitySettingsConfig.savePackOverrides(
                backend.gameDir(),
                packKeyFor(backend.gameDir(), packPathOpt.get()),
                nonDefaults
        );
    }

    public static void resetTemporalHistory() {
        hasHistory = false;
    }

    private static String toConfigPackReference(Path gameDir, Path packPath) {
        try {
            Path packsDir = gameDir.resolve("shaderpacks").toRealPath();
            Path realPack = packPath.toRealPath();
            if (realPack.getParent() != null && realPack.getParent().equals(packsDir)) {
                return realPack.getFileName().toString();
            }
            return realPack.toString();
        } catch (IOException ignored) {
            Path packsDir = gameDir.resolve("shaderpacks").toAbsolutePath().normalize();
            Path absPack = packPath.toAbsolutePath().normalize();
            if (absPack.getParent() != null && absPack.getParent().equals(packsDir)) {
                return absPack.getFileName().toString();
            }
            return absPack.toString();
        }
    }

    private static int[] resolveViewportSize() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer != null && mc.gameRenderer.mainRenderTarget() != null) {
            RenderTarget rt = mc.gameRenderer.mainRenderTarget();
            return new int[]{Math.max(1, rt.width), Math.max(1, rt.height)};
        }
        if (mc.getWindow() != null) {
            return new int[]{Math.max(1, mc.getWindow().getWidth()), Math.max(1, mc.getWindow().getHeight())};
        }
        return new int[]{1280, 720};
    }

    public static void requestLightmapRefresh() {
        pendingLightmapRefresh = true;
    }

    public static boolean consumePendingLightmapRefresh() {
        if (!pendingLightmapRefresh) {
            return false;
        }
        pendingLightmapRefresh = false;
        return true;
    }

    private static void syncWorldRenderState(boolean apertureActive, boolean suppressWater, boolean disableShade) {
        boolean firstInit = !fastStateInitialized;
        boolean changed = apertureActive != lastApertureActive
                || suppressWater != lastWaterSuppressed
                || disableShade != lastDisableShade;
        fastApertureActive = apertureActive;
        fastSuppressVanillaWater = suppressWater;
        fastDisableShade = disableShade;
        if (!suppressWater) {
            fastSuppressUnderwaterParticles = false;
        }
        fastStateInitialized = true;
        if (changed) {
            lastApertureActive = apertureActive;
            lastWaterSuppressed = suppressWater;
            lastDisableShade = disableShade;
            pendingLightmapRefresh = true;
            hasHistory = false;
            if (!firstInit) {
                Minecraft mcInstance = Minecraft.getInstance();
                if (mcInstance.levelExtractor != null && mcInstance.level != null) {
                    mcInstance.levelExtractor.allChanged();
                }
            }
        }
    }

    public static void executeWorldPostComposition(
            GameRenderer gameRenderer,
            LevelRenderState levelRenderState,
            CameraRenderState cameraRenderState,
            Vector4f fogColor
    ) {
        Optional<MetalDeviceBackend> backendOpt = MetalDeviceBackend.activeInstance();
        if (backendOpt.isEmpty() || gameRenderer == null || levelRenderState == null || cameraRenderState == null) {
            return;
        }
        MetalDeviceBackend backend = backendOpt.get();
        DefaultMetalityEngine engine = backend.publicEngine();

        RenderTarget mainTarget = gameRenderer.mainRenderTarget();
        if (mainTarget == null) {
            return;
        }

        int targetW = Math.max(1, mainTarget.width);
        int targetH = Math.max(1, mainTarget.height);
        ensureStartupShaderPackLoaded(backend, engine, targetW, targetH);

        MetalitySettingsConfig cfg = backend.settingsConfig();
        float effScale = cfg.getShaderRenderScale();
        if (cfg.isThermalAdaptiveScaling()) {
            effScale = Math.min(effScale, engine.telemetry().recommendedThermalRenderScale());
        }
        backend.metalDevice().setShaderRenderScale(effScale);

        Optional<ActivePackInfo> activeOpt = engine.shaders().activePackInfo();
        if (activeOpt.isEmpty()) {
            return;
        }

        if (!(mainTarget.getColorTexture() instanceof MetalPearlTexture colorTex)
                || !(mainTarget.getDepthTexture() instanceof MetalPearlTexture depthTex)) {
            return;
        }

        backend.flushActiveRenderPass();

        ClientLevel level = Minecraft.getInstance().level;
        Mc263ChunkAdapter.syncClientLevel(level);

        Matrix4f projMat = cameraRenderState.projectionMatrix != null
                ? SCRATCH_PROJ.set(cameraRenderState.projectionMatrix)
                : SCRATCH_PROJ.identity();
        Matrix4f rawViewRotMat = cameraRenderState.viewRotationMatrix != null
                ? SCRATCH_RAW_VIEW_ROT.set(cameraRenderState.viewRotationMatrix)
                : SCRATCH_RAW_VIEW_ROT.identity();
        Matrix4f viewRotMat = SCRATCH_VIEW_ROT.set(rawViewRotMat);
        double rawCx = cameraRenderState.pos != null ? cameraRenderState.pos.x : 0.0;
        double rawCy = cameraRenderState.pos != null ? cameraRenderState.pos.y : 64.0;
        double rawCz = cameraRenderState.pos != null ? cameraRenderState.pos.z : 0.0;
        double eyeOffX = 0.0;
        double eyeOffY = 0.0;
        double eyeOffZ = 0.0;
        if (gameRenderer instanceof GameRendererAccessor gra) {
            SCRATCH_POSE_STACK.setIdentity();
            gra.metality$bobHurt(cameraRenderState, SCRATCH_POSE_STACK);
            if (Minecraft.getInstance().options.bobView().get()) {
                gra.metality$bobView(cameraRenderState, SCRATCH_POSE_STACK);
            }
            Matrix4f bobMat = SCRATCH_BOB.set(SCRATCH_POSE_STACK.last().pose());
            Vector3f viewEyeOffset = SCRATCH_BOB_INV.set(bobMat).invert().transformPosition(SCRATCH_VEC1.set(0.0f, 0.0f, 0.0f));
            Vector3f worldEyeOffset = SCRATCH_RAW_VIEW_INV.set(rawViewRotMat).invert().transformDirection(viewEyeOffset, SCRATCH_VEC2);
            eyeOffX = worldEyeOffset.x;
            eyeOffY = worldEyeOffset.y;
            eyeOffZ = worldEyeOffset.z;
            Matrix4f bobRotOnly = SCRATCH_BOB_ROT.set(bobMat).setTranslation(0.0f, 0.0f, 0.0f);
            viewRotMat = SCRATCH_VIEW_ROT.set(bobRotOnly).mul(rawViewRotMat);
        }
        final double cx = rawCx + eyeOffX;
        final double cy = rawCy + eyeOffY;
        final double cz = rawCz + eyeOffZ;
        Matrix4f invProjMat = SCRATCH_INV_PROJ.set(projMat).invert();
        Matrix4f invViewRotMat = SCRATCH_INV_VIEW_ROT.set(viewRotMat).invert();
        Matrix4f viewProjMat = SCRATCH_VIEW_PROJ.set(projMat).mul(viewRotMat);
        Matrix4f invViewProjMat = SCRATCH_INV_VIEW_PROJ.set(viewProjMat).invert();

        float[] invProj = invProjMat.get(BUF_INV_PROJ);
        float[] proj = projMat.get(BUF_PROJ);
        float[] viewRot = viewRotMat.get(BUF_VIEW_ROT);
        float[] invViewRot = invViewRotMat.get(BUF_INV_VIEW_ROT);
        float[] invViewProj = invViewProjMat.get(BUF_INV_VIEW_PROJ);
        float[] curViewProj = viewProjMat.get(BUF_CUR_VIEW_PROJ);

        SkyRenderState skyState = levelRenderState.skyRenderState;
        float tiltRad = (float) Math.toRadians(-32.0);
        float cosTilt = Mth.cos(tiltRad);
        float sinTilt = Mth.sin(tiltRad);

        float sunAngle = skyState != null ? skyState.sunAngle : 0.0f;
        float rawSunX = -Mth.sin(sunAngle);
        float rawSunY = Mth.cos(sunAngle);
        float sunX = rawSunX;
        float sunY = rawSunY * cosTilt;
        float sunZ = rawSunY * sinTilt;

        float moonAngle = skyState != null ? skyState.moonAngle : (sunAngle + (float) Math.PI);
        float rawMoonX = -Mth.sin(moonAngle);
        float rawMoonY = Mth.cos(moonAngle);
        float moonX = rawMoonX;
        float moonY = rawMoonY * cosTilt;
        float moonZ = rawMoonY * sinTilt;
        float moonPhaseFactor = (skyState != null && skyState.moonPhase != null)
                ? (skyState.moonPhase.index() / 7.0f)
                : 0.5f;

        float skyR = 0.55f, skyG = 0.72f, skyB = 0.98f;
        if (skyState != null && skyState.skyColor != null) {
            Vector3fc sc = skyState.skyColor;
            skyR = sc.x();
            skyG = sc.y();
            skyB = sc.z();
        } else if (fogColor != null) {
            skyR = fogColor.x();
            skyG = fogColor.y();
            skyB = fogColor.z();
        }
        float starBrightness = skyState != null ? skyState.starBrightness : 0.0f;

        float fogR = fogColor != null ? fogColor.x() : skyR;
        float fogG = fogColor != null ? fogColor.y() : skyG;
        float fogB = fogColor != null ? fogColor.z() : skyB;
        float fogW = fogColor != null ? fogColor.w() : 1.0f;

        float srR = 0.0f, srG = 0.0f, srB = 0.0f, srA = 0.0f;
        if (skyState != null && skyState.sunriseAndSunsetColor != null) {
            Vector4fc sr = skyState.sunriseAndSunsetColor;
            srR = sr.x();
            srG = sr.y();
            srB = sr.z();
            srA = sr.w();
        }

        int dimensionId = 0;
        if (level != null) {
            if (level.dimension().equals(Level.NETHER)) {
                dimensionId = -1;
            } else if (level.dimension().equals(Level.END)) {
                dimensionId = 1;
            }
        } else if (skyState != null && skyState.skybox != null) {
            if (skyState.skybox == DimensionType.Skybox.END) {
                dimensionId = 1;
            } else if (skyState.skybox == DimensionType.Skybox.NONE) {
                dimensionId = -1;
            }
        }

        float rainStrength = (dimensionId == 0 && levelRenderState.weatherRenderState != null)
                ? Mth.clamp(levelRenderState.weatherRenderState.intensity, 0.0f, 1.0f)
                : 0.0f;
        float thunderStrength = (dimensionId == 0 && level != null)
                ? Mth.clamp(level.getThunderLevel(1.0f), 0.0f, 1.0f)
                : 0.0f;
        float seaLevel = level != null ? (float) level.getSeaLevel() : 63.0f;
        float timeSeconds = (levelRenderState.gameTime + levelRenderState.worldPartialTicks) / 20.0f;

        int submergedMode = 0;
        if (cameraRenderState.fogType == FogType.WATER) {
            submergedMode = 1;
        } else if (cameraRenderState.fogType == FogType.LAVA) {
            submergedMode = 2;
        }

        ActivePackInfo packInfo = activeOpt.get();
        boolean isAperture = (packInfo.format() == ShaderPackFormat.APERTURE_TS);
        boolean suppressWater = isAperture && cfg.isSuppressVanillaWater();
        boolean disableShade = isAperture && engine.nativeShaders().isDisableShadeEnabled();
        syncWorldRenderState(isAperture, suppressWater, disableShade);
        // V9a: Suppress opaque depth-writing ParticleTypes.UNDERWATER dust when above water to eliminate 1px water fireflies
        fastSuppressUnderwaterParticles = suppressWater && (submergedMode == 0);
        final long currentFrame = ++frameCounter;
        if (isAperture) {
            backend.metalDevice().ensureFrameStarted();
            syncWorldEntitiesSinglePass(
                    engine, level, invViewRotMat, dimensionId,
                    cx, cy, cz, timeSeconds, currentFrame,
                    levelRenderState.worldPartialTicks,
                    cfg.isDynamicLightsEnabled(),
                    cfg.isInteractiveRipplesEnabled()
            );
            if (packInfo.hardwareRayTracingEnabled() && engine.nativeRayTracingScene().isEnabled()) {
                engine.nativeRayTracingScene().rebuildTlas((float) cx, (float) cy, (float) cz);
            } else {
                engine.nativeRayTracingScene().syncWorldDataOnly((float) cx, (float) cy, (float) cz);
            }
        }
        double dcx = cx - lastCamX;
        double dcy = cy - lastCamY;
        double dcz = cz - lastCamZ;
        boolean temporalReset = !hasHistory
                || level != lastClientLevel
                || lastDimId != dimensionId
                || (dcx * dcx + dcy * dcy + dcz * dcz) > 256.0;
        Matrix4f reprojMat = temporalReset
                ? SCRATCH_REPROJ.set(viewProjMat)
                : SCRATCH_REPROJ.set(lastViewProjMat).translate((float) dcx, (float) dcy, (float) dcz);
        float[] prevViewProj = reprojMat.get(BUF_PREV_VIEW_PROJ);

        BUF_CAM_POS[0] = (float) cx; BUF_CAM_POS[1] = (float) cy; BUF_CAM_POS[2] = (float) cz; BUF_CAM_POS[3] = (float) (currentFrame % 1024L);
        BUF_SUN_DIR[0] = sunX; BUF_SUN_DIR[1] = sunY; BUF_SUN_DIR[2] = sunZ; BUF_SUN_DIR[3] = sunY > -0.05f ? 1.0f : 0.0f;
        BUF_MOON_DIR[0] = moonX; BUF_MOON_DIR[1] = moonY; BUF_MOON_DIR[2] = moonZ; BUF_MOON_DIR[3] = moonPhaseFactor;
        BUF_SKY_COL[0] = skyR; BUF_SKY_COL[1] = skyG; BUF_SKY_COL[2] = skyB; BUF_SKY_COL[3] = starBrightness;
        BUF_FOG_COL[0] = fogR; BUF_FOG_COL[1] = fogG; BUF_FOG_COL[2] = fogB; BUF_FOG_COL[3] = fogW;
        BUF_SUNRISE_COL[0] = srR; BUF_SUNRISE_COL[1] = srG; BUF_SUNRISE_COL[2] = srB; BUF_SUNRISE_COL[3] = srA;

        MetalityFFM.WorldFrameUniforms worldFrame = new MetalityFFM.WorldFrameUniforms(
                invProj,
                proj,
                viewRot,
                invViewRot,
                invViewProj,
                prevViewProj,
                BUF_CAM_POS,
                BUF_SUN_DIR,
                BUF_MOON_DIR,
                BUF_SKY_COL,
                BUF_FOG_COL,
                BUF_SUNRISE_COL,
                BUF_VOXEL_ZERO,
                targetW,
                targetH,
                timeSeconds,
                rainStrength,
                thunderStrength,
                seaLevel,
                1.0f,
                dimensionId,
                submergedMode,
                engine.nativeRayTracingScene().activeSectionBlasCount(),
                (packInfo.hardwareRayTracingEnabled() && engine.nativeRayTracingScene().isHardwareAccelerated()) ? 1 : 0,
                temporalReset ? 1 : 0,
                null
        );

        engine.executeWorldShader(colorTex.nativeTexture(), depthTex.nativeTexture(), worldFrame);
        lastViewProjMat.set(viewProjMat);
        lastCamX = cx;
        lastCamY = cy;
        lastCamZ = cz;
        lastDimId = dimensionId;
        lastClientLevel = level;
        hasHistory = true;

        HOOK_CTX.frameIndex = currentFrame;
        HOOK_CTX.viewportWidth = targetW;
        HOOK_CTX.viewportHeight = targetH;
        HOOK_CTX.camX = cx;
        HOOK_CTX.camY = cy;
        HOOK_CTX.camZ = cz;
        engine.dispatchHookStage(CustomRenderPassHook.Stage.POST_COMPOSITE, HOOK_CTX);
    }

    private static void ensureStartupShaderPackLoaded(
            MetalDeviceBackend backend,
            DefaultMetalityEngine engine,
            int width,
            int height
    ) {
        if (autoLoadAttempted) {
            return;
        }
        autoLoadAttempted = true;
        Path packPath = resolveStartupPackPath(backend);
        if (packPath == null) {
            return;
        }
        if (!Files.exists(packPath)) {
            LOGGER.warn("[Metality] Configured shader pack path does not exist: {}", packPath);
            return;
        }
        try {
            Map<String, String> overrides = MetalitySettingsConfig.loadPackOverrides(
                    backend.gameDir(), packKeyFor(backend.gameDir(), packPath)
            );
            ActivePackInfo info = engine.shaders().loadPack(packPath, width, height, 1.0f, overrides);
            boolean apertureActive = (info.format() == ShaderPackFormat.APERTURE_TS);
            cachedStartupApertureActive = apertureActive;
            engine.rayTracing().setEnabled(
                    backend.settingsConfig().isHardwareRayTracing() && backend.metalDevice().capabilities().hardwareRayTracing()
            );
            boolean suppressWater = apertureActive && backend.settingsConfig().isSuppressVanillaWater();
            boolean disableShade = apertureActive && engine.nativeShaders().isDisableShadeEnabled();
            syncWorldRenderState(apertureActive, suppressWater, disableShade);
            LOGGER.info(
                    "[Metality] Loaded shader pack '{}' (format={}, passes={}, fusedGroups={}, memoryless={}/{}, savedDram={} MB/frame, compiledLibraries={}, hardwareRayTracing={}, options={}, profiles={}, overrides={})",
                    info.packName(),
                    info.format(),
                    info.totalPasses(),
                    info.fusedGroups(),
                    info.memorylessTextures(),
                    info.declaredTextures(),
                    String.format("%.2f", info.savedDramBytesPerFrame() / (1024.0 * 1024.0)),
                    info.compiledPsos(),
                    info.hardwareRayTracingEnabled(),
                    engine.shaders().activePackOptions().size(),
                    engine.shaders().activePackProfiles().size(),
                    overrides.size()
            );
        } catch (Throwable t) {
            LOGGER.error("[Metality] Failed to load startup shader pack from {}", packPath, t);
        }
    }

    private record DynamicLightCandidate(
            double distSq,
            double x,
            double y,
            double z,
            float power,
            float r,
            float g,
            float b,
            float radius
    ) {}

    /**
     * Single-pass entity collection for dynamic lights, interactive water ripples, and articulated entity shadow OBBs.
     * Eliminates 3x redundant level.entitiesForRendering() iterations and per-frame array/list heap allocations.
     */
    private static void syncWorldEntitiesSinglePass(
            DefaultMetalityEngine engine,
            ClientLevel level,
            Matrix4f invViewRotMat,
            int dimensionId,
            double cx,
            double cy,
            double cz,
            float timeSeconds,
            long currentFrame,
            float partialTick,
            boolean dynamicLightsEnabled,
            boolean interactiveRipplesEnabled
    ) {
        Minecraft mc = Minecraft.getInstance();
        Entity player = mc.player;

        // 1. Held dynamic lights (local player)
        int lightCount = 0;
        boolean localPlayerHoldingLight = false;
        if (dynamicLightsEnabled && player instanceof LivingEntity livingPlayer) {
            Vector3f rightVec = invViewRotMat.transformDirection(SCRATCH_RIGHT.set(1.0f, 0.0f, 0.0f));
            Vector3f upVec = invViewRotMat.transformDirection(SCRATCH_UP.set(0.0f, 1.0f, 0.0f));
            Vector3f fwdVec = invViewRotMat.transformDirection(SCRATCH_FWD.set(0.0f, 0.0f, -1.0f));

            ItemStack mainHand = livingPlayer.getMainHandItem();
            if (classifyItemLight(mainHand, BUF_TMP_ITEM) && lightCount < MAX_DYNAMIC_LIGHTS) {
                localPlayerHoldingLight = true;
                lightCount = appendDynamicLight(
                        BUF_DYN_LIGHTS, lightCount,
                        cx + fwdVec.x * 0.34 + rightVec.x * 0.22 - upVec.x * 0.12,
                        cy + fwdVec.y * 0.34 + rightVec.y * 0.22 - upVec.y * 0.12,
                        cz + fwdVec.z * 0.34 + rightVec.z * 0.22 - upVec.z * 0.12,
                        BUF_TMP_ITEM[3], BUF_TMP_ITEM[0], BUF_TMP_ITEM[1], BUF_TMP_ITEM[2], BUF_TMP_ITEM[4]
                );
            }

            ItemStack offHand = livingPlayer.getOffhandItem();
            if (classifyItemLight(offHand, BUF_TMP_ITEM) && lightCount < MAX_DYNAMIC_LIGHTS) {
                localPlayerHoldingLight = true;
                lightCount = appendDynamicLight(
                        BUF_DYN_LIGHTS, lightCount,
                        cx + fwdVec.x * 0.34 - rightVec.x * 0.22 - upVec.x * 0.12,
                        cy + fwdVec.y * 0.34 - rightVec.y * 0.22 - upVec.y * 0.12,
                        cz + fwdVec.z * 0.34 - rightVec.z * 0.22 - upVec.z * 0.12,
                        BUF_TMP_ITEM[3], BUF_TMP_ITEM[0], BUF_TMP_ITEM[1], BUF_TMP_ITEM[2], BUF_TMP_ITEM[4]
                );
            }
        }

        boolean doRipples = interactiveRipplesEnabled && level != null && dimensionId == 0;
        if (!doRipples) {
            ACTIVE_RIPPLES.clear();
            ENTITY_WATER_STATES.clear();
            lastRippleLevel = level;
            lastRippleSampleSec = timeSeconds;
            engine.nativeRayTracingScene().setWaterRipples(null, 0);
        } else {
            if (level != lastRippleLevel || timeSeconds < lastRippleSampleSec - 1.0f || Math.abs(timeSeconds - lastRippleSampleSec) > 5.0f) {
                ACTIVE_RIPPLES.clear();
                ENTITY_WATER_STATES.clear();
                lastRippleLevel = level;
            }
            lastRippleSampleSec = timeSeconds;
            if (player != null) {
                processEntityWaterRipple(level, player, cx, cy, cz, timeSeconds, currentFrame);
            }
        }

        boolean doShadows = level != null && mc.options.entityShadows().get();
        SCRATCH_ENTITY_LIGHTS.clear();
        SCRATCH_SHADOW_CANDS.clear();

        if (level != null && (dynamicLightsEnabled || doRipples || doShadows)) {
            for (Entity entity : level.entitiesForRendering()) {
                if (entity == null || entity == player) {
                    continue;
                }
                double ex = entity.getX();
                double ey = entity.getY();
                double ez = entity.getZ();
                double dx = ex - cx;
                double dy = ey - cy;
                double dz = ez - cz;
                double distSq = dx * dx + dy * dy + dz * dz;

                // A. Dynamic entity lights (<= 192m)
                if (dynamicLightsEnabled && lightCount < MAX_DYNAMIC_LIGHTS && distSq <= 192.0 * 192.0
                        && !(entity instanceof ArmorStand stand && (stand.isMarker() || stand.isInvisible()))) {
                    float midY = (float) (ey + Math.max(0.35f, entity.getBbHeight() * 0.55f));
                    if (entity instanceof EndCrystal) {
                        SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(distSq, ex, ey + 0.95, ez, 28.0f, 0.90f, 0.40f, 1.00f, 1.35f));
                    } else if (entity instanceof Blaze || entity instanceof Fireball || entity.isOnFire()) {
                        SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(distSq, ex, midY, ez, 22.0f, 1.00f, 0.38f, 0.06f, 0.85f));
                    } else if (entity instanceof DragonFireball) {
                        SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(distSq, ex, midY, ez, 24.0f, 0.86f, 0.24f, 1.00f, 0.95f));
                    } else if (entity instanceof MagmaCube) {
                        SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(distSq, ex, midY, ez, 14.0f, 1.00f, 0.30f, 0.04f, 0.75f));
                    } else if (entity instanceof GlowSquid || entity instanceof Allay) {
                        SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(distSq, ex, midY, ez, 15.0f, 0.20f, 0.92f, 0.96f, 0.65f));
                    } else if (entity instanceof ExperienceOrb) {
                        SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(distSq, ex, ey + 0.25, ez, 8.0f, 0.48f, 1.00f, 0.28f, 0.32f));
                    } else if (entity instanceof SpectralArrow) {
                        SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(distSq, ex, ey + 0.15, ez, 10.0f, 1.00f, 0.85f, 0.30f, 0.28f));
                    } else if (entity instanceof GlowItemFrame gif) {
                        if (classifyItemLight(gif.getItem(), BUF_TMP_ITEM)) {
                            SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(
                                    distSq, ex, ey + 0.25, ez,
                                    BUF_TMP_ITEM[3] * 0.75f, BUF_TMP_ITEM[0], BUF_TMP_ITEM[1], BUF_TMP_ITEM[2], BUF_TMP_ITEM[4]
                            ));
                        } else {
                            SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(distSq, ex, ey + 0.25, ez, 9.0f, 0.35f, 0.95f, 0.88f, 0.40f));
                        }
                    } else if (entity instanceof LivingEntity livingEntity) {
                        if (classifyItemLight(livingEntity.getMainHandItem(), BUF_TMP_ITEM)
                                || classifyItemLight(livingEntity.getOffhandItem(), BUF_TMP_ITEM)) {
                            SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(
                                    distSq, ex, ey + livingEntity.getBbHeight() * 0.68, ez,
                                    BUF_TMP_ITEM[3], BUF_TMP_ITEM[0], BUF_TMP_ITEM[1], BUF_TMP_ITEM[2], BUF_TMP_ITEM[4]
                            ));
                        }
                    } else if (entity instanceof ItemEntity itemEntity) {
                        if (classifyItemLight(itemEntity.getItem(), BUF_TMP_ITEM)) {
                            SCRATCH_ENTITY_LIGHTS.add(new DynamicLightCandidate(
                                    distSq, ex, ey + 0.35, ez,
                                    BUF_TMP_ITEM[3] * 0.65f, BUF_TMP_ITEM[0], BUF_TMP_ITEM[1], BUF_TMP_ITEM[2], BUF_TMP_ITEM[4]
                            ));
                        }
                    }
                }

                // B. Interactive water ripples (<= 72m)
                if (doRipples && distSq <= 72.0 * 72.0) {
                    processEntityWaterRipple(level, entity, cx, cy, cz, timeSeconds, currentFrame);
                }

                // C. Entity shadow casters (<= 44m)
                if (doShadows && distSq <= 46.0 * 46.0
                        && !entity.isInvisible()
                        && !(entity instanceof HangingEntity)
                        && !(entity instanceof Display)
                        && !(entity instanceof Interaction)
                        && !(entity instanceof AreaEffectCloud)
                        && !(entity instanceof Marker)
                        && !(entity instanceof ExperienceOrb)
                        && !(entity instanceof ArmorStand stand && stand.isMarker())) {
                    float w = entity.getBbWidth();
                    float h = entity.getBbHeight();
                    if (w >= 0.22f || h >= 0.22f) {
                        Vec3 pos = entity.getPosition(partialTick);
                        double sdx = pos.x - cx;
                        double sdy = pos.y - cy;
                        double sdz = pos.z - cz;
                        double sDistSq = sdx * sdx + sdy * sdy + sdz * sdz;
                        if (sDistSq <= 44.0 * 44.0) {
                            SCRATCH_SHADOW_CANDS.add(new EntityShadowCandidate(entity, sDistSq));
                        }
                    }
                }
            }
        }

        // Flush dynamic lights
        if (!dynamicLightsEnabled) {
            engine.nativeRayTracingScene().setDynamicLights(null, 0);
        } else {
            if (!SCRATCH_ENTITY_LIGHTS.isEmpty()) {
                SCRATCH_ENTITY_LIGHTS.sort(java.util.Comparator.comparingDouble(DynamicLightCandidate::distSq));
                for (DynamicLightCandidate cand : SCRATCH_ENTITY_LIGHTS) {
                    if (lightCount >= MAX_DYNAMIC_LIGHTS) {
                        break;
                    }
                    lightCount = appendDynamicLight(
                            BUF_DYN_LIGHTS, lightCount,
                            cand.x(), cand.y(), cand.z(),
                            cand.power(), cand.r(), cand.g(), cand.b(), cand.radius()
                    );
                }
            }
            engine.nativeRayTracingScene().setDynamicLights(lightCount > 0 ? BUF_DYN_LIGHTS : null, lightCount);
        }

        // Flush interactive water ripples
        if (doRipples) {
            ENTITY_WATER_STATES.entrySet().removeIf(entry -> (currentFrame - entry.getValue().seenFrame) > 8L);
            Iterator<RippleEvent> it = ACTIVE_RIPPLES.iterator();
            while (it.hasNext()) {
                RippleEvent r = it.next();
                float age = timeSeconds - r.birthSec();
                if (age < 0.0f || age > RIPPLE_LIFETIME_SEC) {
                    it.remove();
                }
            }
            int rn = Math.min(MAX_WATER_RIPPLES, ACTIVE_RIPPLES.size());
            if (rn == 0) {
                engine.nativeRayTracingScene().setWaterRipples(null, 0);
            } else {
                int startIdx = ACTIVE_RIPPLES.size() - rn;
                for (int i = 0; i < rn; ++i) {
                    RippleEvent r = ACTIVE_RIPPLES.get(startIdx + i);
                    int off = i * 8;
                    BUF_RIPPLES[off] = (float) r.wx();
                    BUF_RIPPLES[off + 1] = (float) r.wy();
                    BUF_RIPPLES[off + 2] = (float) r.wz();
                    BUF_RIPPLES[off + 3] = Math.max(0.0f, timeSeconds - r.birthSec());
                    BUF_RIPPLES[off + 4] = r.dirX();
                    BUF_RIPPLES[off + 5] = r.dirZ();
                    BUF_RIPPLES[off + 6] = r.amplitude();
                    BUF_RIPPLES[off + 7] = r.kind();
                }
                engine.nativeRayTracingScene().setWaterRipples(BUF_RIPPLES, rn);
            }
        }

        // Flush entity shadow casters (V3c: exclude local player's own OBBs in first-person when holding a light so legs never cast self-shadow!)
        if (!doShadows) {
            engine.nativeRayTracingScene().setEntityShadows(null, 0);
        } else {
            boolean excludeLocalPlayerSelfShadow = localPlayerHoldingLight && mc.options.getCameraType().isFirstPerson();
            emitEntityShadowCasters(engine, mc, cx, cy, cz, partialTick, excludeLocalPlayerSelfShadow);
        }
    }

    private static int appendDynamicLight(
            float[] buf,
            int index,
            double x,
            double y,
            double z,
            float power,
            float r,
            float g,
            float b,
            float radius
    ) {
        int off = index * 8;
        buf[off] = (float) x;
        buf[off + 1] = (float) y;
        buf[off + 2] = (float) z;
        buf[off + 3] = power;
        buf[off + 4] = r;
        buf[off + 5] = g;
        buf[off + 6] = b;
        buf[off + 7] = radius;
        return index + 1;
    }

    private static boolean classifyItemLight(ItemStack stack, float[] outRgbPowerRadius) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        int emit = (stack.getItem() instanceof BlockItem bi)
                ? bi.getBlock().defaultBlockState().getLightEmission()
                : 0;

        if ("lava_bucket".equals(path)) {
            outRgbPowerRadius[0] = 1.00f;
            outRgbPowerRadius[1] = 0.30f;
            outRgbPowerRadius[2] = 0.04f;
            outRgbPowerRadius[3] = 34.0f;
            outRgbPowerRadius[4] = 1.20f;
            return true;
        }
        if (path.contains("redstone_torch") || "redstone_ore".equals(path) || "deepslate_redstone_ore".equals(path)) {
            // V3b: Natural warm-crimson held redstone torch (prevents blood-red R/G=21 clipping on dark spruce bark)
            outRgbPowerRadius[0] = 1.00f;
            outRgbPowerRadius[1] = 0.30f;
            outRgbPowerRadius[2] = 0.16f;
            outRgbPowerRadius[3] = 6.8f;
            outRgbPowerRadius[4] = 0.35f;
            return true;
        }
        if ("verdant_froglight".equals(path) || "glow_lichen".equals(path)) {
            outRgbPowerRadius[0] = 0.32f;
            outRgbPowerRadius[1] = 1.00f;
            outRgbPowerRadius[2] = 0.46f;
            outRgbPowerRadius[3] = 20.0f;
            outRgbPowerRadius[4] = 0.45f;
            return true;
        }
        if ("sea_lantern".equals(path) || "beacon".equals(path) || "conduit".equals(path)) {
            outRgbPowerRadius[0] = 0.52f;
            outRgbPowerRadius[1] = 0.94f;
            outRgbPowerRadius[2] = 1.00f;
            outRgbPowerRadius[3] = 21.0f;
            outRgbPowerRadius[4] = 0.48f;
            return true;
        }
        if (path.contains("soul") || path.contains("sculk") || "glow_ink_sac".equals(path)) {
            outRgbPowerRadius[0] = 0.14f;
            outRgbPowerRadius[1] = 0.88f;
            outRgbPowerRadius[2] = 1.00f;
            outRgbPowerRadius[3] = 20.0f;
            outRgbPowerRadius[4] = 0.45f;
            return true;
        }
        if ("end_rod".equals(path)) {
            outRgbPowerRadius[0] = 0.96f;
            outRgbPowerRadius[1] = 0.86f;
            outRgbPowerRadius[2] = 1.00f;
            outRgbPowerRadius[3] = 20.0f;
            outRgbPowerRadius[4] = 0.40f;
            return true;
        }
        if (path.contains("crying_obsidian") || path.contains("amethyst") || "respawn_anchor".equals(path)
                || "end_crystal".equals(path) || "nether_star".equals(path) || "pearlescent_froglight".equals(path)) {
            outRgbPowerRadius[0] = 0.84f;
            outRgbPowerRadius[1] = 0.38f;
            outRgbPowerRadius[2] = 1.00f;
            outRgbPowerRadius[3] = 18.0f;
            outRgbPowerRadius[4] = 0.45f;
            return true;
        }
        if ("ochre_froglight".equals(path) || "glowstone".equals(path) || "glowstone_dust".equals(path)) {
            outRgbPowerRadius[0] = 1.00f;
            outRgbPowerRadius[1] = 0.72f;
            outRgbPowerRadius[2] = 0.26f;
            outRgbPowerRadius[3] = 20.0f;
            outRgbPowerRadius[4] = 0.45f;
            return true;
        }
        if (emit > 0 || path.contains("torch") || path.contains("lantern") || "blaze_rod".equals(path)
                || "blaze_powder".equals(path) || "glow_berries".equals(path)
                || "fire_charge".equals(path) || "shroomlight".equals(path)) {
            float power = 18.0f * (Math.max(emit, 12) / 15.0f);
            outRgbPowerRadius[0] = 1.00f;
            outRgbPowerRadius[1] = 0.50f;
            outRgbPowerRadius[2] = 0.14f;
            outRgbPowerRadius[3] = power;
            outRgbPowerRadius[4] = 0.40f;
            return true;
        }
        return false;
    }

    private static void processEntityWaterRipple(
            ClientLevel level,
            Entity entity,
            double cx,
            double cy,
            double cz,
            float timeSeconds,
            long currentFrame
    ) {
        double ex = entity.getX();
        double ey = entity.getY();
        double ez = entity.getZ();
        double dx = ex - cx;
        double dy = ey - cy;
        double dz = ez - cz;
        if (dx * dx + dy * dy + dz * dz > 72.0 * 72.0) {
            return;
        }

        boolean isBoat = entity instanceof AbstractBoat;
        double waterSurfY = probeWaterSurfaceY(level, entity, ex, ey, ez, isBoat, SCRATCH_BLOCK_POS);
        boolean inWater = !Double.isNaN(waterSurfY);

        UUID id = entity.getUUID();
        EntityWaterState st = ENTITY_WATER_STATES.get(id);
        boolean firstSeen = (st == null);
        if (firstSeen) {
            st = new EntityWaterState();
            st.ex = ex;
            st.ey = ey;
            st.ez = ez;
            st.lastWakeX = ex;
            st.lastWakeZ = ez;
            st.wasInWater = inWater && entity.getDeltaMovement().y > -0.15;
            st.lastSampleSec = timeSeconds;
            st.lastWakeSec = timeSeconds;
            st.lastOarSec = timeSeconds;
            st.lastIdleSec = timeSeconds;
            ENTITY_WATER_STATES.put(id, st);
        }
        st.seenFrame = currentFrame;

        float dt = Math.max(0.016f, Math.min(0.25f, timeSeconds - st.lastSampleSec));
        st.lastSampleSec = timeSeconds;
        double vy = firstSeen ? entity.getDeltaMovement().y : (ey - st.ey);
        double mvx = firstSeen ? 0.0 : (ex - st.ex);
        double mvz = firstSeen ? 0.0 : (ez - st.ez);
        double frameDist = Math.hypot(mvx, mvz);
        if (frameDist > 2.5) {
            frameDist = 0.0;
            st.lastWakeX = ex;
            st.lastWakeZ = ez;
        }
        double speedBps = frameDist / dt;

        if (inWater && !st.wasInWater && !firstSeen) {
            float splashAmp = Mth.clamp(
                    0.32f + (float) Math.abs(vy / dt) * 0.055f + entity.getBbWidth() * 0.14f,
                    0.32f,
                    1.05f
            );
            pushRipple(ex, waterSurfY, ez, timeSeconds, 0.0f, 0.0f, splashAmp, 0.0f);
            st.lastWakeX = ex;
            st.lastWakeZ = ez;
            st.lastWakeSec = timeSeconds;
        }

        if (inWater && isBoat) {
            AbstractBoat boat = (AbstractBoat) entity;
            double dirX;
            double dirZ;
            if (frameDist > 0.008) {
                dirX = mvx / frameDist;
                dirZ = mvz / frameDist;
            } else {
                float yawRad = (float) Math.toRadians(boat.getYRot());
                dirX = -Mth.sin(yawRad);
                dirZ = Mth.cos(yawRad);
            }
            boolean paddling = boat.getRowingTime(0, 0.0f) > 0.0f || boat.getRowingTime(1, 0.0f) > 0.0f;
            if (speedBps > 0.25 || paddling) {
                if (timeSeconds - st.lastWakeSec >= 0.15f) {
                    float wakeAmp = Mth.clamp(0.30f + (float) speedBps * 0.05f, 0.30f, 0.78f);
                    pushRipple(
                            ex - dirX * 0.42,
                            waterSurfY,
                            ez - dirZ * 0.42,
                            timeSeconds,
                            (float) dirX,
                            (float) dirZ,
                            wakeAmp,
                            1.0f
                    );
                    st.lastWakeSec = timeSeconds;
                }
                if (timeSeconds - st.lastOarSec >= 0.30f) {
                    double perpX = -dirZ;
                    double perpZ = dirX;
                    double side = st.oarSide;
                    st.oarSide = -st.oarSide;
                    pushRipple(
                            ex + dirX * 0.08 + perpX * (side * 0.90),
                            waterSurfY,
                            ez + dirZ * 0.08 + perpZ * (side * 0.90),
                            timeSeconds,
                            0.0f,
                            0.0f,
                            0.36f,
                            0.0f
                    );
                    st.lastOarSec = timeSeconds;
                }
            } else if (timeSeconds - st.lastIdleSec >= 1.45f) {
                pushRipple(ex, waterSurfY, ez, timeSeconds, 0.0f, 0.0f, 0.14f, 0.0f);
                st.lastIdleSec = timeSeconds;
            }
        } else if (inWater) {
            double stepDist = Math.hypot(ex - st.lastWakeX, ez - st.lastWakeZ);
            if (stepDist >= 0.30 && (timeSeconds - st.lastWakeSec) >= 0.14f) {
                float wakeAmp = Mth.clamp(0.16f + (float) speedBps * 0.055f, 0.16f, 0.52f);
                pushRipple(ex, waterSurfY, ez, timeSeconds, 0.0f, 0.0f, wakeAmp, 0.0f);
                st.lastWakeX = ex;
                st.lastWakeZ = ez;
                st.lastWakeSec = timeSeconds;
            } else if (timeSeconds - st.lastIdleSec >= 1.45f) {
                pushRipple(ex, waterSurfY, ez, timeSeconds, 0.0f, 0.0f, 0.12f, 0.0f);
                st.lastIdleSec = timeSeconds;
            }
        }

        st.ex = ex;
        st.ey = ey;
        st.ez = ez;
        st.wasInWater = inWater;
    }

    private static double probeWaterSurfaceY(
            ClientLevel level,
            Entity entity,
            double ex,
            double ey,
            double ez,
            boolean isBoat,
            BlockPos.MutableBlockPos mPos
    ) {
        int bx = Mth.floor(ex);
        int bz = Mth.floor(ez);
        int yMid = Mth.floor(ey + 0.15);
        mPos.set(bx, yMid, bz);
        FluidState fMid = level.getFluidState(mPos);
        if (fMid.is(FluidTags.WATER)) {
            int yTop = yMid;
            while (level.getFluidState(mPos.set(bx, yTop + 1, bz)).is(FluidTags.WATER) && (yTop - yMid) < 4) {
                yTop++;
            }
            return yTop + 0.888;
        }
        int yLow = Mth.floor(ey - (isBoat ? 0.55 : 0.18));
        mPos.set(bx, yLow, bz);
        FluidState fLow = level.getFluidState(mPos);
        if (fLow.is(FluidTags.WATER)) {
            return yLow + 0.888;
        }
        if (entity.isInWater()) {
            return Math.floor(ey) + 0.888;
        }
        return Double.NaN;
    }

    private static void pushRipple(
            double wx, double wy, double wz,
            float birthSec, float dirX, float dirZ, float amplitude, float kind
    ) {
        if (ACTIVE_RIPPLES.size() >= MAX_WATER_RIPPLES) {
            ACTIVE_RIPPLES.remove(0);
        }
        ACTIVE_RIPPLES.add(new RippleEvent(wx, wy, wz, birthSec, dirX, dirZ, amplitude, kind));
    }

    private record EntityShadowCandidate(Entity entity, double distSq) {}

    private static void emitEntityShadowCasters(
            DefaultMetalityEngine engine,
            Minecraft mc,
            double cx,
            double cy,
            double cz,
            float partialTick,
            boolean excludeLocalPlayerSelfShadow
    ) {
        SCRATCH_SHADOW_CANDS.sort(java.util.Comparator.comparingDouble(EntityShadowCandidate::distSq));

        SCRATCH_SHADOW_ORDERED.clear();
        if (!excludeLocalPlayerSelfShadow && mc.player != null && !mc.player.isInvisible()) {
            SCRATCH_SHADOW_ORDERED.add(mc.player);
        }
        for (EntityShadowCandidate c : SCRATCH_SHADOW_CANDS) {
            SCRATCH_SHADOW_ORDERED.add(c.entity());
        }

        float[] buf = BUF_ENTITY_SHADOWS;
        int count = 0;
        int playerBoxEnd = 0;

        for (Entity entity : SCRATCH_SHADOW_ORDERED) {
            if (count >= MAX_ENTITY_SHADOW_CASTERS) {
                break;
            }
            boolean isLocalPlayer = (entity == mc.player);
            float w = entity.getBbWidth();
            float h = entity.getBbHeight();
            Vec3 pos = entity.getPosition(partialTick);
            double ex = pos.x;
            double ey = pos.y;
            double ez = pos.z;

            if (!(entity instanceof LivingEntity living)) {
                float yawRad = (float) Math.toRadians(entity.getYRot(partialTick));
                float hx = Math.max(0.14f, w * 0.44f);
                float hy = Math.max(0.12f, h * 0.46f);
                float hz = Math.max(0.14f, w * 0.44f);
                count = appendArticulatedBox(
                        buf, count, ex, ey + hy + 0.04, ez,
                        0.0f, 0.0f, 0.0f, hx, hy, hz, yawRad, 0.0f
                );
                continue;
            }

            float bodyYawRad = (float) Math.toRadians(Mth.rotLerp(partialTick, living.yBodyRotO, living.yBodyRot));
            float headYawRad = (float) Math.toRadians(Mth.rotLerp(partialTick, living.yHeadRotO, living.yHeadRot));
            float headPitchRad = (float) Math.toRadians(Mth.clamp(living.getXRot(partialTick), -75.0f, 75.0f));
            float walkPos = living.walkAnimation.position(partialTick);
            float walkSpeed = Math.min(1.0f, living.walkAnimation.speed(partialTick));
            float swingAnim = living.getSwingAnimation(partialTick);
            float swimAmt = living.getSwimAmount(partialTick);
            boolean crouching = living.hasPose(Pose.CROUCHING);
            boolean sitting = living.isPassenger();
            float swimBlend = living.isFallFlying() ? 1.0f : swimAmt;

            float legSwing = Mth.cos(walkPos * 0.6662f) * 1.15f * walkSpeed;
            float armSwing = Mth.cos(walkPos * 0.6662f + Mth.PI) * 0.95f * walkSpeed;
            float attackPitch = swingAnim > 0.0f ? -Mth.sin(swingAnim * Mth.PI) * 1.45f : 0.0f;

            boolean isSpider = entity instanceof Spider;
            boolean isCreeper = entity instanceof Creeper;
            boolean isQuadruped = entity instanceof Animal;

            if (!isSpider && !isCreeper && !isQuadruped
                    && (h >= 1.25f || swimBlend > 0.1f) && w <= 1.55f
                    && count + 6 <= MAX_ENTITY_SHADOW_CASTERS) {
                float entityScale = Math.max(0.45f, living.getScale());
                float baseH = crouching
                        ? Math.max(h / 0.8333f, 1.45f * entityScale)
                        : (swimBlend > 0.1f ? Math.max(h, 1.80f * entityScale) : h);
                float baseW = Math.min(Math.max(w, 0.45f * entityScale), 1.40f);

                float legLen = baseH * 0.42f;
                float torsoLen = baseH * 0.34f;
                float headHy = baseH * 0.115f;
                float headHx = Math.min(baseW * 0.34f, headHy * 1.05f);
                float torsoHx = baseW * 0.34f;
                float torsoHz = Math.max(0.11f, baseW * 0.18f);
                float limbThick = Math.max(0.085f, torsoHx * 0.48f);

                double standHipY = sitting
                        ? (ey + legLen * 0.55)
                        : (crouching ? (ey + legLen * 0.82) : (ey + legLen));
                double hipPivotY = Mth.lerp((double) swimBlend, standHipY, ey + 0.32);
                float bodyPitch = Mth.lerp(
                        swimBlend,
                        crouching ? 0.50f : 0.0f,
                        living.isFallFlying() ? 1.38f : 1.48f
                );

                float hipSepX = limbThick * 0.95f;
                float leftLegPitch = sitting
                        ? -1.25f
                        : (swimBlend > 0.25f ? (bodyPitch + legSwing * 0.55f) : (legSwing * (crouching ? 0.80f : 1.0f)));
                float rightLegPitch = sitting
                        ? -1.25f
                        : (swimBlend > 0.25f ? (bodyPitch - legSwing * 0.55f) : (-legSwing * (crouching ? 0.80f : 1.0f)));

                // Left & Right Legs (pivoted around hips)
                count = appendArticulatedBox(
                        buf, count, ex, hipPivotY, ez,
                        hipSepX, -legLen * 0.50f, 0.0f,
                        limbThick, legLen * 0.50f, limbThick,
                        bodyYawRad, leftLegPitch
                );
                count = appendArticulatedBox(
                        buf, count, ex, hipPivotY, ez,
                        -hipSepX, -legLen * 0.50f, 0.0f,
                        limbThick, legLen * 0.50f, limbThick,
                        bodyYawRad, rightLegPitch
                );

                // Torso (pivoted from hips upward/forward)
                count = appendArticulatedBox(
                        buf, count, ex, hipPivotY, ez,
                        0.0f, torsoLen * 0.50f, 0.0f,
                        torsoHx, torsoLen * 0.50f, torsoHz,
                        bodyYawRad, bodyPitch
                );

                // Compute shoulder & neck world anchor at top of pitched torso
                float sinB = Mth.sin(bodyYawRad);
                float cosB = Mth.cos(bodyYawRad);
                float sinTP = Mth.sin(bodyPitch);
                float cosTP = Mth.cos(bodyPitch);
                float shoulderLocalY = torsoLen * 0.94f * cosTP;
                float shoulderLocalZ = torsoLen * 0.94f * sinTP;
                double shX = ex - shoulderLocalZ * sinB;
                double shY = hipPivotY + shoulderLocalY;
                double shZ = ez + shoulderLocalZ * cosB;

                float armLen = torsoLen * 0.96f;
                float armOffX = torsoHx + limbThick * 0.72f;
                float swimArmPitch = bodyPitch - 0.55f;
                float leftArmPitch = swimBlend > 0.25f
                        ? (swimArmPitch - armSwing * 0.45f)
                        : (-armSwing + (crouching ? -0.18f : 0.0f));
                float rightArmPitch = swimBlend > 0.25f
                        ? (swimArmPitch + armSwing * 0.45f + attackPitch)
                        : (armSwing + attackPitch + (crouching ? -0.18f : 0.0f));

                // Left & Right Arms (+attack swing)
                count = appendArticulatedBox(
                        buf, count, shX, shY, shZ,
                        armOffX, -armLen * 0.48f, 0.0f,
                        limbThick * 0.88f, armLen * 0.48f, limbThick * 0.88f,
                        bodyYawRad, leftArmPitch
                );
                count = appendArticulatedBox(
                        buf, count, shX, shY, shZ,
                        -armOffX, -armLen * 0.48f, 0.0f,
                        limbThick * 0.88f, armLen * 0.48f, limbThick * 0.88f,
                        bodyYawRad, rightArmPitch
                );

                // Head (independently rotated by headYaw & headPitch above shoulder/neck)
                float neckGap = headHy * 1.02f;
                double neckX = ex - (torsoLen + 0.04f) * sinTP * sinB;
                double neckY = hipPivotY + (torsoLen + 0.04f) * cosTP;
                double neckZ = ez + (torsoLen + 0.04f) * sinTP * cosB;
                count = appendArticulatedBox(
                        buf, count, neckX, neckY, neckZ,
                        0.0f, neckGap, 0.0f,
                        headHx, headHy, headHx,
                        headYawRad, headPitchRad * 0.75f
                );
            } else if (isQuadruped && w >= 0.42f && h >= 0.42f && count + 6 <= MAX_ENTITY_SHADOW_CASTERS) {
                // Quadruped animal (Cow, Pig, Sheep, Wolf, Horse): horizontal body + head + 4 swinging legs
                float legH = h * 0.42f;
                float bodyHy = Math.max(0.14f, h * 0.23f);
                float bodyHx = Math.max(0.16f, w * 0.34f);
                float bodyHz = Math.max(w * 0.62f, h * 0.40f);
                double bodyY = ey + legH + bodyHy * 0.82f;
                float legThick = Math.max(0.065f, bodyHx * 0.34f);
                double hipY = ey + legH * 0.94f;
                float legX = bodyHx * 0.56f;
                float legZ = bodyHz * 0.62f;

                count = appendArticulatedBox(
                        buf, count, ex, bodyY, ez,
                        0.0f, 0.0f, 0.0f, bodyHx, bodyHy, bodyHz, bodyYawRad, 0.0f
                );
                float quadHeadPitch = headPitchRad * 0.7f;
                count = appendArticulatedBox(
                        buf, count, ex, bodyY + bodyHy * 0.55f, ez,
                        0.0f, h * 0.08f, bodyHz * 0.92f,
                        bodyHx * 0.65f, bodyHy * 0.72f, bodyHx * 0.65f,
                        headYawRad, quadHeadPitch
                );
                // Diagonal gait: FL/BR vs FR/BL
                float qSwing = legSwing * 0.85f;
                count = appendArticulatedBox(buf, count, ex, hipY, ez,  legX, -legH * 0.48f,  legZ, legThick, legH * 0.48f, legThick, bodyYawRad,  qSwing);
                count = appendArticulatedBox(buf, count, ex, hipY, ez, -legX, -legH * 0.48f,  legZ, legThick, legH * 0.48f, legThick, bodyYawRad, -qSwing);
                count = appendArticulatedBox(buf, count, ex, hipY, ez,  legX, -legH * 0.48f, -legZ, legThick, legH * 0.48f, legThick, bodyYawRad, -qSwing);
                count = appendArticulatedBox(buf, count, ex, hipY, ez, -legX, -legH * 0.48f, -legZ, legThick, legH * 0.48f, legThick, bodyYawRad,  qSwing);
            } else if (isCreeper && count + 4 <= MAX_ENTITY_SHADOW_CASTERS) {
                float legH = h * 0.28f;
                float torsoHy = h * 0.24f;
                float torsoHx = Math.max(0.16f, w * 0.34f);
                float headR = Math.max(0.18f, w * 0.38f);
                count = appendArticulatedBox(buf, count, ex, ey + legH + torsoHy, ez, 0.0f, 0.0f, 0.0f, torsoHx, torsoHy, torsoHx * 0.72f, bodyYawRad, 0.0f);
                count = appendArticulatedBox(buf, count, ex, ey + h - headR * 0.95f, ez, 0.0f, 0.0f, 0.0f, headR, headR, headR, headYawRad, headPitchRad * 0.7f);
                count = appendArticulatedBox(buf, count, ex, ey + legH * 0.95f, ez, 0.0f, -legH * 0.48f,  torsoHx * 0.72f, torsoHx * 0.85f, legH * 0.48f, torsoHx * 0.42f, bodyYawRad,  legSwing * 0.85f);
                count = appendArticulatedBox(buf, count, ex, ey + legH * 0.95f, ez, 0.0f, -legH * 0.48f, -torsoHx * 0.72f, torsoHx * 0.85f, legH * 0.48f, torsoHx * 0.42f, bodyYawRad, -legSwing * 0.85f);
            } else if (isSpider && count + 4 <= MAX_ENTITY_SHADOW_CASTERS) {
                float hy = h * 0.34f;
                float legWave = Mth.sin(walkPos * 1.15f) * 0.22f * walkSpeed;
                count = appendArticulatedBox(buf, count, ex, ey + hy * 1.1f, ez, 0.0f, 0.0f, -w * 0.14f, w * 0.24f, hy * 0.95f, w * 0.30f, bodyYawRad, 0.0f);
                count = appendArticulatedBox(buf, count, ex, ey + hy * 1.05f, ez, 0.0f, 0.0f,  w * 0.22f, w * 0.18f, hy * 0.82f, w * 0.18f, headYawRad, headPitchRad * 0.5f);
                count = appendArticulatedBox(buf, count, ex, ey + hy * 0.75f, ez,  w * 0.36f, -hy * 0.2f, legWave, w * 0.24f, hy * 0.55f, w * 0.34f, bodyYawRad, 0.0f);
                count = appendArticulatedBox(buf, count, ex, ey + hy * 0.75f, ez, -w * 0.36f, -hy * 0.2f, -legWave, w * 0.24f, hy * 0.55f, w * 0.34f, bodyYawRad, 0.0f);
            } else {
                float hx = Math.max(0.14f, w * 0.44f);
                float hy = Math.max(0.12f, h * 0.46f);
                float hz = Math.max(0.14f, w * 0.44f);
                count = appendArticulatedBox(
                        buf, count, ex, ey + hy + 0.04, ez,
                        0.0f, 0.0f, 0.0f, hx, hy, hz, bodyYawRad, 0.0f
                );
            }
            if (isLocalPlayer) {
                playerBoxEnd = count;
            }
        }

        // Guarantee slots 0..31 hold the local player + strictly nearest entity OBB casters by squared distance to camera
        // so shader-side evalEntityShadowSoftVis(min(cnt, 32)) always shadows the closest entities without clipping.
        if (count > playerBoxEnd + 1) {
            double[] boxDistSq = BUF_BOX_DIST_SQ;
            for (int i = playerBoxEnd; i < count; ++i) {
                int off = i * 8;
                double bdx = buf[off] - cx;
                double bdy = buf[off + 1] - cy;
                double bdz = buf[off + 2] - cz;
                boxDistSq[i] = bdx * bdx + bdy * bdy + bdz * bdz;
            }
            // Zero-GC in-place insertion sort on count <= 256 (already nearly sorted by entity distance)
            float[] tmp8 = BUF_TMP8;
            for (int i = playerBoxEnd + 1; i < count; ++i) {
                double keyDist = boxDistSq[i];
                if (keyDist >= boxDistSq[i - 1]) {
                    continue;
                }
                System.arraycopy(buf, i * 8, tmp8, 0, 8);
                int j = i - 1;
                while (j >= playerBoxEnd && boxDistSq[j] > keyDist) {
                    boxDistSq[j + 1] = boxDistSq[j];
                    System.arraycopy(buf, j * 8, buf, (j + 1) * 8, 8);
                    --j;
                }
                boxDistSq[j + 1] = keyDist;
                System.arraycopy(tmp8, 0, buf, (j + 1) * 8, 8);
            }
        }

        engine.nativeRayTracingScene().setEntityShadows(buf, count);
    }

    private static int appendArticulatedBox(
            float[] buf,
            int index,
            double pivotX,
            double pivotY,
            double pivotZ,
            float localX,
            float localY,
            float localZ,
            float hx,
            float hy,
            float hz,
            float yawRad,
            float pitchRad
    ) {
        if (index >= MAX_ENTITY_SHADOW_CASTERS) {
            return index;
        }
        float sinY = Mth.sin(yawRad);
        float cosY = Mth.cos(yawRad);
        float sinP = Mth.sin(pitchRad);
        float cosP = Mth.cos(pitchRad);
        float ry = localY * cosP - localZ * sinP;
        float rz = localY * sinP + localZ * cosP;
        double wx = pivotX + localX * cosY - rz * sinY;
        double wy = pivotY + ry;
        double wz = pivotZ + localX * sinY + rz * cosY;

        int off = index * 8;
        buf[off] = (float) wx;
        buf[off + 1] = (float) wy;
        buf[off + 2] = (float) wz;
        buf[off + 3] = hx;
        buf[off + 4] = hy;
        buf[off + 5] = hz;
        buf[off + 6] = yawRad;
        buf[off + 7] = pitchRad;
        return index + 1;
    }
}
