package com.monitor.twin.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 标定试算结果（复查清单 P1-10）。
 *
 * <p>字段分三组，读的人要能一眼分清「模型算出来的」「现在档案里存的」「平台据此判的」：
 * 前两组混在一起，就会出现"这个方位角到底是谁给的"这种没法回答的问题。</p>
 */
@Data
public class CalibrationPreviewVO {

    // ---- ① 能不能算 ----
    /** 该测点所在项目的数字孪生资产是否已有后端高程场。 */
    private boolean sceneAvailable;
    /** 资产版本（有网格时非空）。 */
    private String assetVersion;
    /** 算不了的原因（资产未配置 / 版本没导出网格 / 档案缺坐标 / 落在模型范围外）。 */
    private String unavailableReason;
    /** 设备头与目标是否都落在模型覆盖范围内。 */
    private boolean insideModel;

    // ---- ② 模型按档案几何算出来的 ----
    /** 模型算出的方位角（度，0°=北顺时针）。 */
    private Double computedAzimuthDegrees;
    /** 模型算出的俯仰角（度，正为仰视）。 */
    private Double computedElevationDegrees;
    /** 模型算出的斜距（米）。 */
    private Double computedSlantRangeM;
    /** 水平距离（米）。 */
    private Double horizontalDistanceM;
    /** 地形通视结论：true 通视 / false 被遮挡 / null 没算（范围外或缺数据）。 */
    private Boolean terrainLineOfSight;
    /** 全程最小净空（米）。 */
    private Double minimumClearanceM;
    /** 步进采样次数（口径留痕）。 */
    private Integer samplingSteps;
    /** 本次试算用的天线高 / 反射器高（米）。 */
    private BigDecimal antennaHeightUsedM;
    private BigDecimal reflectorHeightUsedM;

    // ---- ③ 与设备视场、与现有绑定的一致性 ----
    private Boolean withinDetectionRange;
    private Boolean withinHorizontalFov;
    private Boolean withinVerticalFov;
    /** 当前绑定上的标定值（未绑定时为 null）。 */
    private BigDecimal boundAzimuthDegrees;
    private BigDecimal boundElevationDegrees;
    private BigDecimal boundSlantRangeM;
    /** 「绑定上的值」与「档案几何算出的值」之差；正数代表绑定值偏大。 */
    private Double azimuthDeltaDegrees;
    private Double slantRangeDeltaM;

    // ---- ④ 结论 ----
    /** VISIBLE / BLOCKED / OUTSIDE_MODEL / NO_TERRAIN / GEOMETRY_MISSING。 */
    private String verdict;
    /** 一句可以直接显示给人看的话。 */
    private String summary;
    /** 补充说明（口径、来源、需要人工确认的地方）。 */
    private List<String> notes = new ArrayList<>();
}
