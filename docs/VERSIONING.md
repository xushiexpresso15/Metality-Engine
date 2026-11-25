# Metality 版本迭代命名規則與發版整理規範

本文件定義 **Metality Engine** 與官方光影包（**Meteor Dusk**、**Meteor Zenith**）的版本命名規則、產物檔名規範、專案內發版目錄結構，以及標準發版流程。

---

## 1. 版本號命名規則（SemVer 2.0.0）

全面採用 **Semantic Versioning 2.0.0**，相容 Fabric Loader、Gradle、npm、GitHub Releases 與 Modrinth 版本排序規則：

```text
v<MAJOR>.<MINOR>.<PATCH>[-<stage>.<N>]
```

- **目錄與 Git Tag 顯示格式**：加上 `v` 前綴（例：`v0.1.0-beta.1`）
- **設定檔與產物檔名格式**：不加 `v` 前綴（例：`0.1.0-beta.1`）

### 版本欄位定義
| 欄位 | 意義 | 進位時機 |
| :--- | :--- | :--- |
| `MAJOR` | 主版本 | 正式 1.0 里程碑、對外 `:api` 或 `pack.ts` 契約破壞性變更 |
| `MINOR` | 次版本 | 新增重大引擎功能（如連體玻璃、MetalFX 升級、新管線階段）或支援新 Minecraft 主版本 |
| `PATCH` | 修補版本 | 錯誤修復、光影調校、效能最佳化（完全相容既有設定與光影包） |
| `stage.N` | 預發行階段 | 開發階段遞增序號（無此後綴即為正式穩定版） |

### 開發階段演進順序（由舊到新）
1. **`alpha.N`**（例：`0.1.0-alpha` → `0.1.0-alpha.2`）：內部架構原型、管線實驗期，允許破壞性調整。
2. **`beta.N`**（例：`0.1.0-beta.1` → `0.1.0-beta.2`）：功能完整、畫質與效能打磨期，適合公開測試。
3. **`rc.N`**（例：`0.1.0-rc.1`）：正式版候選，只修阻斷性 Bug，不再新增功能。
4. **穩定版**（例：`0.1.0`、`1.0.0`）：正式釋出版本。

---

## 2. 產物檔名規範

| 元件 | 產物檔名規則 | 本次 Beta 範例 |
| :--- | :--- | :--- |
| **Metality 模組 JAR** | `metality-mc<MC_VERSION>-<ENGINE_VERSION>.jar` | `metality-mc26.3-0.1.0-beta.1.jar` |
| **Metality 公開 API JAR** | `metality-api-<ENGINE_VERSION>.jar` | `metality-api-0.1.0-beta.1.jar` |
| **Meteor Dusk 光影包** | `meteor-dusk-<PACK_VERSION>.zip` | `meteor-dusk-0.1.0-beta.1.zip` |
| **Meteor Zenith 光影包** | `meteor-zenith-<PACK_VERSION>.zip` | `meteor-zenith-0.1.0-beta.1.zip` |
| **TypeScript SDK** | `@metality/sdk@<SDK_VERSION>` | `0.1.0-beta.1` |

> **版本同步原則**：引擎與官方光影包在重大里程碑同步升版（並輸出整合包 `releases/bundles/v<VERSION>/`）；若後續僅微調單一光影包（不改引擎）或僅修補引擎（不改光影），兩者可各自獨立推進 `PATCH` 或 `beta.N`。

---

## 3. 專案內版本目錄結構

原始碼永遠維持單一主幹（`api/`、`core/`、`mod/`、`native/`、`sdk/`、`shaderpacks/`）；所有已完成驗證的歷史與當前發行產物統一集中於專案根目錄 **`releases/`**：

```text
metality/
├── api/                         # 開發主幹（Source of Truth）
├── core/
├── mod/
├── native/
├── sdk/
├── shaderpacks/                 # 光影開發主幹（含 pack.json + pack.ts + shaders/）
│   ├── meteor-dusk/
│   └── meteor-zenith/
├── docs/
│   └── VERSIONING.md            # 本規範文件
├── scripts/
│   └── release.sh               # 一鍵建置與歸檔腳本
├── CHANGELOG.md                 # 全專案版本更新日誌
└── releases/                    # 發版產物歸檔（每個版本一個不可變資料夾）
    ├── engine/                  # 引擎獨立發版
    │   └── v0.1.0-beta.1/
    │       ├── metality-mc26.3-0.1.0-beta.1.jar
    │       ├── metality-api-0.1.0-beta.1.jar
    │       ├── RELEASE_NOTES.md
    │       └── SHA256SUMS.txt
    ├── shaderpacks/             # 光影包獨立發版
    │   ├── meteor-dusk/
    │   │   └── v0.1.0-beta.1/
    │   │       ├── meteor-dusk-0.1.0-beta.1.zip
    │   │       └── SHA256SUMS.txt
    │   └── meteor-zenith/
    │       └── v0.1.0-beta.1/
    │           ├── meteor-zenith-0.1.0-beta.1.zip
    │           └── SHA256SUMS.txt
    └── bundles/                 # 同期整合發佈包（引擎 + 雙光影一次到位）
        └── v0.1.0-beta.1/
            ├── metality-mc26.3-0.1.0-beta.1.jar
            ├── meteor-dusk-0.1.0-beta.1.zip
            ├── meteor-zenith-0.1.0-beta.1.zip
            ├── RELEASE_NOTES.md
            └── SHA256SUMS.txt
```

### 整理守則
1. **不可變歸檔（Immutable Releases）**：已發佈的 `releases/.../vX.Y.Z[-stage.N]/` 目錄不覆寫舊檔；有任何修正一律遞增下一個版本號（如 `v0.1.0-beta.2`）。
2. **固定附帶校驗檔**：每個發版目錄必含 `SHA256SUMS.txt`，引擎與整合包必含 `RELEASE_NOTES.md`。
3. **主幹維持乾淨命名**：`shaderpacks/meteor-dusk/` 與 `shaderpacks/meteor-zenith/` 目錄名稱不帶版本號，以利開發測試並避免破壞使用者已儲存的 `meteor-dusk.properties` / `meteor-zenith.properties`；版本資訊紀錄在目錄內的 `pack.json` 與 `pack.ts` 標頭。
4. **Git Tag 對應（啟用 Git 後）**：
   - 整合／引擎發版：`v0.1.0-beta.1`
   - 單獨光影發版：`meteor-dusk-v0.1.0-beta.2`、`meteor-zenith-v0.1.0-beta.2`

---

## 4. 標準發版流程（Release SOP）

1. 更新版本號：
   - 引擎：`gradle.properties`（`mod_version`）、`native/src/metality_c_api.mm`、`native/CMakeLists.txt`、`sdk/package.json`
   - 光影：`shaderpacks/meteor-dusk/pack.json` & `pack.ts`、`shaderpacks/meteor-zenith/pack.json` & `pack.ts`
2. 更新 `CHANGELOG.md` 紀錄本次變更。
3. 執行一鍵打包與驗證腳本：
   ```bash
   ./scripts/release.sh 0.1.0-beta.1
   ```
   腳本會自動驗證雙光影包共享 shader 一致性、編譯原生核心與 Gradle 模組、執行測試、封裝 `.zip` 光影包、產生 `SHA256SUMS.txt` 並歸檔至 `releases/`。
4. （本機遊玩測試）將 `releases/bundles/v<VERSION>/metality-mc26.3-<VERSION>.jar` 替換舊版 JAR 放入 PrismLauncher `mods/`（移除舊版 `metality-mc26.3-*.jar` 以免 Fabric 重複載入），並同步光影包至 `shaderpacks/`。
