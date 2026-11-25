package dev.metality.spi;

import dev.metality.terrain.ChunkMeshingScratch;

/**
 * 版本隔離 SPI：由各 Minecraft 版本的 :mod 適配層實作，負責將 18x18x18 區塊鄰域資料填入零 GC 的 ChunkMeshingScratch。
 */
@FunctionalInterface
public interface ChunkNeighborhoodSource {
    void populateScratch(int sectionX, int sectionY, int sectionZ, ChunkMeshingScratch scratch);
}
