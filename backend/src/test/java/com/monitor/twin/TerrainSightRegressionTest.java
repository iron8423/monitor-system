package com.monitor.twin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-10 的「口径一致」回归网：后端的逐米步进视线计算必须与离线地形生成器给出**同一个答案**。
 *
 * <p><b>为什么这份测试值得写</b>：平台有两处独立实现同一件事——Python 生成器
 * （写出迁移里的 {@code line_of_sight} / {@code minimum_clearance_m}）与后端
 * （标定时的通视校核）。两份代码一旦漂移，症状是「迁移里说通视 1.438m、平台说被遮挡」，
 * 而两边看起来都很有道理。表里的期望值是从**已执行的迁移里抄下来的**
 * （V19 山地 7 条 + V20 三个场景 17 条），不是用后端算出来再写回去的。</p>
 *
 * <p>每条断言四件事：方位角、俯仰角、斜距、最小净空。前三个钉住「本地坐标 ↔ 经纬度」
 * 与投影常数，第四个钉住「高程场 + 双线性采样 + 逐米步进」。</p>
 *
 * <p>容差 0.01m/0.01°：净空的最大偏差来自网格以 float32 存储（实测 0.0008m），
 * 角度的偏差来自迁移里保留的小数位。把容差收紧到 1e-9 只会让这条测试对着存储精度报警，
 * 而不是对着算法错误报警。</p>
 */
class TerrainSightRegressionTest {

    private static TerrainHeightfieldService service;

    @BeforeAll
    static void loadFields() {
        service = new TerrainHeightfieldService(new ObjectMapper(), new DefaultResourceLoader());
        service.reload();
    }

    /**
     * 从迁移里抄下来的视线，每行 13 个字段：
     * 资产版本|设备经度|纬度|地面高程|天线高|测点经度|纬度|高程|反射器高|
     * 期望方位|期望俯仰|期望斜距|期望最小净空。
     */
    private static final String MIGRATED_SIGHTS = """
        |qingyuan-hillside-2.0.0|113.0528015|23.7621687|131.001|10.0|113.0498585|23.7612658|211.734|2.5|251.565|13.039|324.597|1.438
        |qingyuan-hillside-2.0.0|113.0528015|23.7621687|131.001|10.0|113.050349|23.7603629|198.757|2.5|231.34|10.659|325.777|1.15
        |qingyuan-hillside-2.0.0|113.0528015|23.7621687|131.001|10.0|113.05133|23.7599114|180.66|2.5|210.964|8.228|294.58|3.084
        |qingyuan-hillside-2.0.0|113.0528015|23.7621687|131.001|10.0|113.0518205|23.7599114|171.009|2.5|201.801|6.884|271.213|2.883
        |qingyuan-hillside-2.0.0|113.052311|23.7567513|71.275|10.0|113.0518205|23.7590086|141.753|2.5|348.69|13.875|262.614|2.869
        |qingyuan-hillside-2.0.0|113.052311|23.7567513|71.275|10.0|113.0518205|23.7581057|107.385|2.5|341.565|10.256|160.681|2.839
        |qingyuan-bridge-1.0.0|113.077119|23.7965932|304.062|10.0|113.074195|23.7956|326.55|2.5|249.74|2.701|318.007|5.789
        |qingyuan-bridge-1.0.0|113.077119|23.7965932|304.062|10.0|113.074715|23.7956|326.55|2.5|245.821|3.194|268.979|10.532
        |qingyuan-bridge-1.0.0|113.077119|23.7965932|304.062|10.0|113.0752351|23.7956|326.55|2.5|240.191|3.875|221.785|10.528
        |qingyuan-bridge-1.0.0|113.077119|23.7965932|304.062|10.0|113.0757649|23.7956|326.55|2.5|231.442|4.854|177.112|10.512
        |qingyuan-bridge-1.0.0|113.077119|23.7965932|304.062|10.0|113.076285|23.7956|326.55|2.5|217.694|6.154|139.82|10.462
        |qingyuan-bridge-1.0.0|113.077119|23.7965932|304.062|10.0|113.076805|23.7956|326.55|2.5|196.22|7.454|115.536|10.334
        |qingyuan-railway-1.0.0|113.0245478|23.7220349|27.572|10.0|113.0196935|23.7204594|10.859|2.5|250.581|-2.641|525.416|1.582
        |qingyuan-railway-1.0.0|113.0245478|23.7220349|27.572|10.0|113.0205761|23.7204594|13.443|2.5|246.691|-2.808|441.524|3.274
        |qingyuan-railway-1.0.0|113.0245478|23.7220349|27.572|10.0|113.0214587|23.7204594|16.028|2.5|241.015|-3.027|360.608|3.585
        |qingyuan-railway-1.0.0|113.0245478|23.7220349|27.572|10.0|113.0223413|23.7204594|18.612|2.5|232.204|-3.308|285.212|4.119
        |qingyuan-railway-1.0.0|113.0245478|23.7220349|27.572|10.0|113.0232239|23.7204594|21.197|2.5|217.727|-3.599|221.061|1.813
        |qingyuan-railway-1.0.0|113.0245478|23.7220349|27.572|10.0|113.0241065|23.7204594|23.782|2.5|194.46|-3.585|180.562|1.272
        |qingyuan-factory-1.0.0|113.080059|23.6953544|17.833|10.0|113.0773137|23.6944334|19.124|2.5|249.984|-1.194|298.065|4.202
        |qingyuan-factory-1.0.0|113.080059|23.6953544|17.833|10.0|113.0777059|23.6942709|19.124|2.5|243.435|-1.326|268.4|4.612
        |qingyuan-factory-1.0.0|113.080059|23.6953544|17.833|10.0|113.0781961|23.6935485|19.124|2.5|223.531|-1.289|275.932|4.961
        |qingyuan-factory-1.0.0|113.080059|23.6953544|17.833|10.0|113.0774117|23.6936388|19.124|2.5|234.866|-1.077|330.21|5.757
        |qingyuan-factory-1.0.0|113.080059|23.6953544|17.833|10.0|113.0786863|23.6942528|19.124|2.5|228.93|-1.915|185.802|4.224
        |qingyuan-factory-1.0.0|113.080059|23.6953544|17.833|10.0|113.0789315|23.6940451|19.124|2.5|218.418|-1.921|185.172|4.751
            """;

    @Test
    void loadsEveryExportedScene() {
        assertEquals(List.of("qingyuan-bridge-1.0.0", "qingyuan-factory-1.0.0",
                        "qingyuan-hillside-2.0.0", "qingyuan-railway-1.0.0"),
                List.copyOf(service.availableVersions()),
                "四个场景都必须有高程场；少一个，那个场景的标定就没有地形校核");
        assertTrue(service.failures().isEmpty(), () -> "载入失败: " + service.failures());
    }

    @Test
    void matchesEveryMigratedClearance() {
        int checked = 0;
        for (String raw : MIGRATED_SIGHTS.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            // 行首的 "|" 让 split 结果的第一段是空串，真正的字段从 f[1] 开始
            String[] f = line.split("\\|");
            String assetVersion = f[1];
            TerrainHeightfield field = service.byAssetVersion(assetVersion).orElseThrow(
                    () -> new AssertionError("缺少高程场: " + assetVersion));
            double headX = field.xOf(Double.parseDouble(f[2]));
            double headY = field.yOf(Double.parseDouble(f[3]));
            double headZ = Double.parseDouble(f[4]) + Double.parseDouble(f[5]);
            double targetX = field.xOf(Double.parseDouble(f[6]));
            double targetY = field.yOf(Double.parseDouble(f[7]));
            double targetZ = Double.parseDouble(f[8]) + Double.parseDouble(f[9]);

            double dx = targetX - headX;
            double dy = targetY - headY;
            double dz = targetZ - headZ;
            double horizontal = Math.hypot(dx, dy);
            double azimuth = LineOfSight.bearingDegrees(dx, dy);
            double elevation = LineOfSight.elevationDegrees(dz, horizontal);
            double slant = LineOfSight.slantRangeM(horizontal, dz);
            LineOfSight.Result sight = LineOfSight.compute(field, headX, headY, headZ,
                    targetX, targetY, targetZ, LineOfSight.DEFAULT_MARGIN_M);

            assertTrue(field.covers(headX, headY) && field.covers(targetX, targetY),
                    () -> line + " 的端点落在模型范围外，这条视线没有可比性");
            assertEquals(Double.parseDouble(f[10]), azimuth, 0.01, () -> line + " 方位角");
            assertEquals(Double.parseDouble(f[11]), elevation, 0.01, () -> line + " 俯仰角");
            assertEquals(Double.parseDouble(f[12]), slant, 0.01, () -> line + " 斜距");
            assertEquals(Double.parseDouble(f[13]), sight.minimumClearanceM(), 0.01,
                    () -> line + " 最小净空");
            assertTrue(sight.visible(), () -> line + " 迁移里标的是通视，后端却算成遮挡");
            checked++;
        }
        assertEquals(24, checked, "四个场景共 24 条视线，一条都不能少（少一条就少一处口径覆盖）");
    }
}
