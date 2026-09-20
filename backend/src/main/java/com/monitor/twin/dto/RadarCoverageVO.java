package com.monitor.twin.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 雷达**按地形裁剪**的地面覆盖（复查清单 P1-11 的后半）。
 *
 * <p>与前端画的那个扇形的关系：扇形是**理论视场**（按量程与角度解析算出来，不打地形），
 * 这里给的是"波束方向上看得到多远的地面"——它回答的是另一个问题。两者必须在界面上
 * 一眼可分：理论视场是虚线提示层，这个是实测覆盖层。</p>
 */
@Data
public class RadarCoverageVO {

    private Long deviceId;
    private String code;

    /** 该项目数字孪生资产是否有后端高程场；false 时下面所有几何字段为空。 */
    private boolean terrainAvailable;
    private String assetVersion;
    /** 算不了的原因（资产未配置 / 版本没导出网格 / 档案缺坐标）。 */
    private String unavailableReason;

    private Double headingDegrees;
    private Double halfAngleDegrees;
    private Double detectionRangeM;
    private Double antennaHeightM;
    /** 雷达所在处的地面绝对高程（前端用它把边界高程换算成本地 z）。 */
    private Double headGroundAltitudeM;
    /** 本次用的反射器高度（米，测点标记之上）；地面覆盖按"地面 + 这个高度"判可见。 */
    private Double reflectorHeightM;
    private Double stepDegrees;
    private Double stepMeters;

    /** 是否**真的被地形截短了**（只要有一条方位线的可见距离小于量程就是 true）。 */
    private boolean terrainClipped;
    /** 所有方位里最短的可见距离（米）；全部通视时等于量程。 */
    private Double shortestVisibleDistanceM;

    private List<Ray> rays = new ArrayList<>();
    private String note;

    /**
     * 一条方位线上的结果。
     *
     * <p>{@code visibleDistanceM} 是**连续可见**的距离：从雷达脚下一路往外走，第一次
     * "地面落到视线之下"（山脊遮挡）就停。它不等于"最远能看到的那一点"——
     * 山脊后面若还有一座更高的山，山尖仍然可见，但那不是能布测点的连续区域，
     * 所以这里给的是能用的那一段，并在 {@code note} 里说明。</p>
     */
    @Data
    public static class Ray {
        private Double azimuthDegrees;
        /** 连续可见的水平距离（米）。 */
        private Double visibleDistanceM;
        /** 该处地面的绝对高程（米）。 */
        private Double groundAltitudeM;
        /** 是否被地形截短（false = 一路看到量程边）。 */
        private Boolean blocked;

        public Ray() {
        }

        public Ray(Double azimuthDegrees, Double visibleDistanceM, Double groundAltitudeM,
                   Boolean blocked) {
            this.azimuthDegrees = azimuthDegrees;
            this.visibleDistanceM = visibleDistanceM;
            this.groundAltitudeM = groundAltitudeM;
            this.blocked = blocked;
        }
    }
}
