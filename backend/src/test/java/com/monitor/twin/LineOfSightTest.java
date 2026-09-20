package com.monitor.twin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐米步进视线计算的边界：平地、净空阈值、被山脊切断、方位角口径（P1-10）。
 *
 * <p>这四个用例刻意都不用真实地形——真实地形的回归在
 * {@link TerrainSightRegressionTest}（对着迁移里的数），这里管的是"算法本身在临界点上
 * 站哪一边"，两者互补。</p>
 */
class LineOfSightTest {

    private static final double FLOOR = 100.0;

    /** 全零网格 = 一片平地，地面绝对高程恒为 {@code FLOOR}。 */
    private static TerrainHeightfield flat() {
        return new TerrainHeightfield("flat-test", 113.0, 23.0, FLOOR,
                20.0, 20.0, 100000.0, 110000.0, 2, 2, new float[9]);
    }

    @Test
    void levelRayIsVisible() {
        LineOfSight.Result r = LineOfSight.compute(flat(), -9, 0, FLOOR + 10, 9, 0, FLOOR + 10, 0.25);
        assertTrue(r.visible());
        assertEquals(10.0, r.minimumClearanceM(), 1e-9);
        assertEquals(18.0, r.surfaceDistanceM(), 1e-9);
    }

    @Test
    void clearanceBelowMarginIsBlocked() {
        LineOfSight.Result r = LineOfSight.compute(flat(), -9, 0, FLOOR + 0.1, 9, 0, FLOOR + 0.1, 0.25);
        assertFalse(r.visible(), "净空 0.1m 低于阈值 0.25m，判遮挡");
        assertEquals(0.1, r.minimumClearanceM(), 1e-9);
    }

    /** 判据是「严格大于 margin」：正好等于阈值不算通视，这一条是刻意钉住的。 */
    @Test
    void clearanceExactlyAtMarginIsBlocked() {
        LineOfSight.Result r = LineOfSight.compute(flat(), -9, 0, FLOOR + 0.25, 9, 0, FLOOR + 0.25, 0.25);
        assertFalse(r.visible());
        assertEquals(0.25, r.minimumClearanceM(), 1e-9);
    }

    /** 中间立起一道 30m 高的坎：两端都在坎的两侧、视线从坎里穿过 -> 净空为负。 */
    @Test
    void ridgeInTheMiddleBlocks() {
        float[] grid = new float[9];
        // 行主序 grid[j*3+i]：i=1 是 x=0 那一列，j=1 是 y=0 那一行 -> 索引 4，正中间
        grid[4] = 30f;
        TerrainHeightfield ridge = new TerrainHeightfield("ridge-test", 113.0, 23.0, FLOOR,
                20.0, 20.0, 100000.0, 110000.0, 2, 2, grid);
        LineOfSight.Result r = LineOfSight.compute(ridge, -9, 0, FLOOR + 5, 9, 0, FLOOR + 5, 0.25);
        assertFalse(r.visible());
        assertEquals(-25.0, r.minimumClearanceM(), 1e-6);
    }

    @Test
    void stepCountMatchesGenerator() {
        // 4m 的视线只有 8 步的下限（生成器 max(8, int(distance))）
        assertEquals(8, LineOfSight.compute(flat(), 0, 0, FLOOR + 5, 4, 0, FLOOR + 5, 0.25).steps());
        // 100m 的视线逐米一步
        assertEquals(100, LineOfSight.compute(flat(), -50, 0, FLOOR + 5, 50, 0, FLOOR + 5, 0.25).steps());
    }

    @Test
    void bearingIsCompassDegrees() {
        assertEquals(0.0, LineOfSight.bearingDegrees(0, 1), 1e-9, "正北");
        assertEquals(90.0, LineOfSight.bearingDegrees(1, 0), 1e-9, "正东");
        assertEquals(180.0, LineOfSight.bearingDegrees(0, -1), 1e-9, "正南");
        assertEquals(270.0, LineOfSight.bearingDegrees(-1, 0), 1e-9, "正西");
        assertEquals(20.0, LineOfSight.angularDifference(350, 10), 1e-9, "跨 0° 取最小夹角");
    }

    @Test
    void coversRejectsPointsOutsideTheModel() {
        TerrainHeightfield flat = flat();
        assertTrue(flat.covers(10.0, -10.0));
        assertFalse(flat.covers(10.001, 0), "范围外不算覆盖：采样会把点夹到边界上，那个值没有依据");
        // 采样本身仍然给值（与生成器一致），这是"夹住"的行为，不是可用的结论
        assertEquals(0.0, flat.sample(500, 500), 1e-9);
    }
}
