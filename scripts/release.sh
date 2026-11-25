#!/usr/bin/env bash
set -euo pipefail

# Metality Engine & Meteor Shader Packs Release Packager
# Usage: ./scripts/release.sh [VERSION] [--skip-build]
# Example: ./scripts/release.sh 0.1.0-beta.1

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${1:-$(grep -E '^mod_version=' gradle.properties | cut -d'=' -f2)}"
API_VERSION="$(grep -E '^api_version=' gradle.properties | cut -d'=' -f2)"
MC_VERSION="$(grep -E '^minecraft_version=' gradle.properties | cut -d'=' -f2)"
SKIP_BUILD="${2:-}"

if [[ -z "$VERSION" ]]; then
  echo "Error: could not determine version." >&2
  exit 1
fi

TAG="v${VERSION}"
echo "==> Packaging Metality release ${TAG} (MC ${MC_VERSION})"

# 1. Verify Dusk / Zenith shared shader parity
echo "==> Checking Dusk/Zenith shared shader parity..."
diff -rq shaderpacks/meteor-dusk/shaders/lib shaderpacks/meteor-zenith/shaders/lib
for f in sky_lut.slang clouds_qres.slang volumetric_godrays.slang bloom_downsample.slang bloom_down_mip.slang bloom_blur.slang final_tonemap.slang; do
  cmp -s "shaderpacks/meteor-dusk/shaders/${f}" "shaderpacks/meteor-zenith/shaders/${f}"
done
echo "    Shared shader parity OK."

# 2. Build & test unless --skip-build
if [[ "$SKIP_BUILD" != "--skip-build" ]]; then
  echo "==> Building native core & Gradle modules..."
  cmake --build native/build --parallel 8
  ctest --test-dir native/build --output-on-failure
  /opt/homebrew/bin/gradle --max-workers=1 :core:buildNativeMetalCore :api:check :core:check :mod:check :mod:build
fi

MOD_JAR="mod/build/libs/metality-mc${MC_VERSION}-${VERSION}.jar"
API_JAR="api/build/libs/api-${API_VERSION}.jar"

if [[ ! -f "$MOD_JAR" ]]; then
  echo "Error: expected Mod JAR not found: $MOD_JAR" >&2
  exit 1
fi

# 3. Prepare release directories
ENGINE_DIR="releases/engine/${TAG}"
DUSK_DIR="releases/shaderpacks/meteor-dusk/${TAG}"
ZENITH_DIR="releases/shaderpacks/meteor-zenith/${TAG}"
BUNDLE_DIR="releases/bundles/${TAG}"

mkdir -p "$ENGINE_DIR" "$DUSK_DIR" "$ZENITH_DIR" "$BUNDLE_DIR"

# 4. Copy Engine artifacts (only attach metality-api JAR when api_version == mod_version)
cp -f "$MOD_JAR" "${ENGINE_DIR}/metality-mc${MC_VERSION}-${VERSION}.jar"
if [[ "$API_VERSION" == "$VERSION" && -f "$API_JAR" ]]; then
  cp -f "$API_JAR" "${ENGINE_DIR}/metality-api-${API_VERSION}.jar"
fi

# 5. Package Shader Packs (.zip, clean of macOS .DS_Store)
DUSK_ZIP="${ROOT_DIR}/${DUSK_DIR}/meteor-dusk-${VERSION}.zip"
ZENITH_ZIP="${ROOT_DIR}/${ZENITH_DIR}/meteor-zenith-${VERSION}.zip"
rm -f "$DUSK_ZIP" "$ZENITH_ZIP"

(cd shaderpacks/meteor-dusk && zip -qr "$DUSK_ZIP" pack.json pack.ts shaders -x '*.DS_Store' '*__MACOSX*')
(cd shaderpacks/meteor-zenith && zip -qr "$ZENITH_ZIP" pack.json pack.ts shaders -x '*.DS_Store' '*__MACOSX*')

# 6. Assemble Bundle
cp -f "$MOD_JAR" "${BUNDLE_DIR}/metality-mc${MC_VERSION}-${VERSION}.jar"
cp -f "$DUSK_ZIP" "${BUNDLE_DIR}/meteor-dusk-${VERSION}.zip"
cp -f "$ZENITH_ZIP" "${BUNDLE_DIR}/meteor-zenith-${VERSION}.zip"

# 7. Write RELEASE_NOTES.md
cat > "${BUNDLE_DIR}/RELEASE_NOTES.md" <<EOF
# Metality Engine & Meteor Shader Packs ${TAG}

- **Release Date**: $(date +%Y-%m-%d)
- **Target Minecraft**: ${MC_VERSION} (Fabric Loader >= 0.19.5, Java 25, macOS 15+ Apple Silicon)
- **Engine Mod**: \`metality-mc${MC_VERSION}-${VERSION}.jar\`
- **Shader Packs**:
  - \`meteor-dusk-${VERSION}.zip\` (High-Performance Apple Silicon Edition)
  - \`meteor-zenith-${VERSION}.zip\` (Apple Silicon Ray Tracing Edition)

See \`CHANGELOG.md\` in the repository root for detailed changes.
EOF
cp -f "${BUNDLE_DIR}/RELEASE_NOTES.md" "${ENGINE_DIR}/RELEASE_NOTES.md"

# 8. Generate SHA256SUMS.txt for each directory
for dir in "$ENGINE_DIR" "$DUSK_DIR" "$ZENITH_DIR" "$BUNDLE_DIR"; do
  (
    cd "$dir"
    rm -f SHA256SUMS.txt
    shasum -a 256 -- *.jar *.zip 2>/dev/null > SHA256SUMS.txt || true
  )
done

echo "==> Release ${TAG} packaged successfully:"
ls -lh "$ENGINE_DIR" "$DUSK_DIR" "$ZENITH_DIR" "$BUNDLE_DIR"
