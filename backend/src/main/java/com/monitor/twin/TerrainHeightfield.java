package com.monitor.twin;

/**
 * 场景本地高程场（复查清单 P1-10）：后端自己算遮挡用的地形网格。
 *
 * <p><b>与离线生成器逐字对齐的口径</b>（{@code tools/terrain_asset/build_terrain_asset.py}）：</p>
 * <ul>
 *   <li>本地坐标 +X 东、+Y 北、+Z 上，原点在锚点；
 *       {@code xs = linspace(-W/2, W/2, cellsX+1)}，间距恒为 {@code W/cellsX}；</li>
 *   <li>{@code grid[j][i]} 是<b>相对基准面</b>的高度，绝对高程 = {@code floor + sample(x,y)}；</li>
 *   <li>经纬度换本地米用<b>锚点处</b>的 {@code mPerLon/mPerLat} 常数，不逐点重算——
 *       生成器写场景坐标与写库坐标用的是同一个换算，逐点重算会让后端和 GLB 差出几米；</li>
 *   <li>采样是双线性，索引夹到 {@code [0, cells-0.001]}（不是 {@code cells}）——
 *       与生成器同一行代码，差 0.001 会让边缘格心落在格外。</li>
 * </ul>
 *
 * <p>不做插值外的任何修饰：这里的目的不是"更好看"，而是与生成器给出<b>同一个净空</b>
 * （四个场景 25 条视线逐值复核，最大偏差 0.0008m，全部来自 float32 存储舍入）。</p>
 */
public final class TerrainHeightfield {

    private final String assetVersion;
    private final double anchorLongitude;
    private final double anchorLatitude;
    private final double floor;
    private final double width;
    private final double depth;
    private final double metresPerLon;
    private final double metresPerLat;
    private final int cellsX;
    private final int cellsY;
    /** 行主序：grid[j * (cellsX + 1) + i]，i 沿 x 最快。 */
    private final float[] grid;

    public TerrainHeightfield(String assetVersion, double anchorLongitude, double anchorLatitude,
                              double floor, double width, double depth, double metresPerLon,
                              double metresPerLat, int cellsX, int cellsY, float[] grid) {
        if (grid.length != (cellsX + 1) * (cellsY + 1)) {
            throw new IllegalArgumentException("高程网格尺寸与 cells 不符: " + grid.length);
        }
        this.assetVersion = assetVersion;
        this.anchorLongitude = anchorLongitude;
        this.anchorLatitude = anchorLatitude;
        this.floor = floor;
        this.width = width;
        this.depth = depth;
        this.metresPerLon = metresPerLon;
        this.metresPerLat = metresPerLat;
        this.cellsX = cellsX;
        this.cellsY = cellsY;
        this.grid = grid;
    }

    public String assetVersion() {
        return assetVersion;
    }

    public double floor() {
        return floor;
    }

    public double width() {
        return width;
    }

    public double depth() {
        return depth;
    }

    public int cellsX() {
        return cellsX;
    }

    public int cellsY() {
        return cellsY;
    }

    /** 经度 → 本地 x（米，东正）。 */
    public double xOf(double longitude) {
        return (longitude - anchorLongitude) * metresPerLon;
    }

    /** 纬度 → 本地 y（米，北正）。 */
    public double yOf(double latitude) {
        return (latitude - anchorLatitude) * metresPerLat;
    }

    /**
     * 点是否落在模型覆盖范围内。
     *
     * <p>采样本身会把范围外的点夹到边上（生成器就是这么写的），但那对"通视校核"是错的：
     * 夹住之后会拿边界地形冒充场景外几公里的地形，结论看着有值其实毫无依据。
     * 所以调用方要先用本方法判断，范围外就明确说"模型没覆盖"，而不是给一个数。</p>
     */
    public boolean covers(double x, double y) {
        return x >= -width / 2.0 && x <= width / 2.0
                && y >= -depth / 2.0 && y <= depth / 2.0;
    }

    /** 相对基准面的双线性采样（米）。 */
    public double sample(double x, double y) {
        double dx = width / cellsX;
        double dy = depth / cellsY;
        double i = (x + width / 2.0) / dx;
        double j = (y + depth / 2.0) / dy;
        i = Math.min(Math.max(i, 0.0), cellsX - 0.001);
        j = Math.min(Math.max(j, 0.0), cellsY - 0.001);
        int i0 = (int) Math.floor(i);
        int j0 = (int) Math.floor(j);
        double fx = i - i0;
        double fy = j - j0;
        int row = cellsX + 1;
        float a = grid[j0 * row + i0];
        float b = grid[j0 * row + i0 + 1];
        float c = grid[(j0 + 1) * row + i0];
        float d = grid[(j0 + 1) * row + i0 + 1];
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
    }

    /** 绝对高程（米）：基准面 + 相对高度。 */
    public double groundAbsolute(double x, double y) {
        return floor + sample(x, y);
    }
}
