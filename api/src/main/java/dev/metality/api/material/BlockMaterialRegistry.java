package dev.metality.api.material;

import java.util.Optional;

/**
 * 執行緒安全的方塊／流體材質註冊表。
 * 供第三方模組註冊自訂方塊（如 Create 機械方塊、發光礦石、自訂樹葉）的 PBR／擺動／圖層屬性，
 * 熱路徑直接以緊湊整數 materialId (1..65535，0 保留給空氣) 查表，零 HashMap 分配開銷。
 */
public interface BlockMaterialRegistry {

    int AIR_MATERIAL_ID = 0;
    int MAX_MATERIALS = 65_535;

    /**
     * 註冊或覆寫特定方塊 ID 的材質屬性，回傳緊湊的原生 materialId。
     */
    int register(BlockMaterialDescriptor descriptor);

    /**
     * 以字串 ID 取得材質索引（未知方塊回傳預設 Solid/fallback ID 或 0）。
     */
    int resolveId(String blockId);

    Optional<BlockMaterialDescriptor> getDescriptor(int materialId);

    int size();

    long paletteVersion();
}
