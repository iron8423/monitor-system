package com.monitor.twin;

/**
 * 逐米步进的视线计算（复查清单 P1-10）——离线地形生成器里那段算法的后端实现。
 *
 * <p><b>为什么是"逐米步进"而不是解析的射线-三角网求交</b>：这里的地形是一个规则网格，
 * 每一步只做一次双线性采样，逻辑短到可以逐行核对；而射线-三角网求交要遍历三角形，
 * 在 4.7 万个格点的网格上是一次实现风险远大于收益的事。300m 的视线 = 300 次采样，
 * 一次试算的代价可以忽略。</p>
 *
 * <p><b>三个必须和生成器一模一样的细节</b>（差一个，结论就会在临界点上翻面）：</p>
 * <ul>
 *   <li>步数 = {@code max(8, int(水平距离 / 1.0))}，取样点是 {@code t = step/steps}，
 *       {@code step} 取 {@code 1..steps-1}——<b>两端点不算</b>（雷达头与目标点本来就不在地面上）；</li>
 *   <li>净空 = 射线高程 − 地面绝对高程，取全程最小值；</li>
 *   <li>判通视是 {@code clearance > margin}（默认 0.25m，严格大于）。
 *       阈值不是 0：贴着地面掠过的视线在工程上没有意义，毫米波要的是净空。</li>
 * </ul>
 */
public final class LineOfSight {

    /** 与生成器一致的默认净空阈值（米）。 */
    public static final double DEFAULT_MARGIN_M = 0.25;

    private LineOfSight() {
    }

    /**
     * 一次视线计算的结果。
     *
     * @param visible           是否通视（净空 &gt; margin）
     * @param minimumClearanceM 全程最小净空（米，可能为负——那就是穿地而过）
     * @param steps             步进采样次数（口径留痕：同一个净空值是在多少步下算出来的）
     * @param surfaceDistanceM  水平距离（米）
     */
    public record Result(boolean visible, double minimumClearanceM, int steps, double surfaceDistanceM) {
    }

    /** 沿射线步进：{@code start} 是雷达头（绝对高程，已含天线高），{@code end} 是目标反射器。 */
    public static Result compute(TerrainHeightfield field,
                                 double startX, double startY, double startZ,
                                 double endX, double endY, double endZ,
                                 double marginM) {
        double dx = endX - startX;
        double dy = endY - startY;
        double dz = endZ - startZ;
        double distance = Math.sqrt(dx * dx + dy * dy);
        int steps = Math.max(8, (int) (distance / 1.0));
        double clearance = Double.POSITIVE_INFINITY;
        for (int step = 1; step < steps; step++) {
            double t = step / (double) steps;
            double x = startX + dx * t;
            double y = startY + dy * t;
            double z = startZ + dz * t;
            clearance = Math.min(clearance, z - field.groundAbsolute(x, y));
        }
        return new Result(clearance > marginM, clearance, steps, distance);
    }

    /** ENU 平面方位角（度）：0°=北，顺时针为正。 */
    public static double bearingDegrees(double east, double north) {
        return (Math.toDegrees(Math.atan2(east, north)) + 360.0) % 360.0;
    }

    /** 俯仰角（度）：正为仰视。 */
    public static double elevationDegrees(double deltaZ, double horizontalM) {
        return Math.toDegrees(Math.atan2(deltaZ, horizontalM));
    }

    /** 斜距（米）。 */
    public static double slantRangeM(double horizontalM, double deltaZ) {
        return Math.sqrt(horizontalM * horizontalM + deltaZ * deltaZ);
    }

    /** 两个方位角之间的最小夹角（度，0..180）。 */
    public static double angularDifference(double a, double b) {
        return Math.abs((a - b + 540.0) % 360.0 - 180.0);
    }
}
