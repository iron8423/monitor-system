package com.monitor.twin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 把 {@code classpath:terrain/*.json} + {@code *.bin} 的离线高程场读进内存（P1-10）。
 *
 * <p>网格由 {@code tools/terrain_asset/export_heightfield.py} 从同一份 DEM 素材重建，
 * 单个场景 5–19 万个格点、120–190KB；四个场景合计约 560KB，常驻内存没有问题，
 * 所以启动时一次性读入、之后只读。</p>
 *
 * <p><b>读不到不是致命错误，但绝不能静默</b>：高程场缺失时平台退回到"没有地形校核能力"
 * 的旧状态（试算端点明确回 {@code sceneAvailable=false} + 原因），而不是拿一份错的地形
 * 去算。启动日志把可用资产版本列出来，运维一眼能看出"这个场景没导出网格"。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TerrainHeightfieldService {

    static final String LOCATION_PATTERN = "classpath:terrain/*.json";

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    /** 资产版本 → 高程场。四个场景规模很小，不做淘汰。 */
    private final Map<String, TerrainHeightfield> fields = new TreeMap<>();
    /** 资产版本 → 载入失败原因（给试算端点回显，便于现场排查）。 */
    private final Map<String, String> failures = new TreeMap<>();

    @PostConstruct
    public void reload() {
        fields.clear();
        failures.clear();
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver(resourceLoader)
                    .getResources(LOCATION_PATTERN);
        } catch (IOException e) {
            log.warn("高程场目录不可读（{}）：通视试算将不可用", LOCATION_PATTERN, e);
            return;
        }
        for (Resource resource : resources) {
            try {
                loadOne(resource);
            } catch (IOException | RuntimeException e) {
                String name = resource.getFilename();
                failures.put(name == null ? "?" : name, e.getMessage());
                log.warn("高程场载入失败，跳过 {}：{}", name, e.getMessage());
            }
        }
        if (fields.isEmpty()) {
            log.warn("未载入任何高程场（{}）：绑定/标定的地形通视校核将不可用", LOCATION_PATTERN);
        } else {
            log.info("已载入 {} 份地形高程场：{}", fields.size(), fields.keySet());
        }
    }

    private void loadOne(Resource metaResource) throws IOException {
        JsonNode meta;
        try (InputStream in = metaResource.getInputStream()) {
            meta = objectMapper.readTree(in);
        }
        String assetVersion = meta.path("assetVersion").asText("");
        if (assetVersion.isEmpty()) {
            throw new IOException("缺少 assetVersion");
        }
        String gridFile = meta.path("grid").path("file").asText("");
        if (gridFile.isEmpty()) {
            throw new IOException("缺少 grid.file");
        }
        Resource gridResource = resourceLoader.getResource("classpath:terrain/" + gridFile);
        if (!gridResource.exists()) {
            throw new IOException("网格文件不存在: " + gridFile);
        }
        int cellsX = meta.path("cellsX").asInt(0);
        int cellsY = meta.path("cellsY").asInt(0);
        int expected = (cellsX + 1) * (cellsY + 1);
        if (expected <= 0) {
            throw new IOException("cells 不合法: " + cellsX + "×" + cellsY);
        }
        byte[] raw;
        try (InputStream in = gridResource.getInputStream()) {
            raw = in.readAllBytes();
        }
        if (raw.length != expected * 4) {
            throw new IOException("网格字节数 " + raw.length + " 与 " + expected + " 个 float32 不符");
        }
        float[] grid = new float[expected];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().get(grid);
        fields.put(assetVersion, new TerrainHeightfield(
                assetVersion,
                meta.path("anchor").path("longitude").asDouble(),
                meta.path("anchor").path("latitude").asDouble(),
                meta.path("floor").asDouble(),
                meta.path("width").asDouble(),
                meta.path("depth").asDouble(),
                meta.path("mPerLon").asDouble(),
                meta.path("mPerLat").asDouble(),
                cellsX, cellsY, grid));
    }

    /** 按资产版本取高程场。版本对不上就是 {@code empty}——不猜、不退化到别的场景。 */
    public Optional<TerrainHeightfield> byAssetVersion(String assetVersion) {
        if (assetVersion == null || assetVersion.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(fields.get(assetVersion.trim()));
    }

    public Set<String> availableVersions() {
        return new TreeSet<>(fields.keySet());
    }

    public Map<String, String> failures() {
        return new TreeMap<>(failures);
    }
}
