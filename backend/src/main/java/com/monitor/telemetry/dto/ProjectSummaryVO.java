package com.monitor.telemetry.dto;

import lombok.Data;

/**
 * 项目概览（《B侧接口契约_M0》§3）：3D 驾驶舱 KPI 面板取数。
 *
 * <p>四个指标都是「当前态」：测点数、<b>未解除</b>警情数、<b>在线</b>设备数、各测点最新形变中的最大值。</p>
 */
@Data
public class ProjectSummaryVO {

    private Long projectId;

    /** 项目下测点数（project → scene → object → point）。 */
    private int pointCount;

    /** 未解除警情数（status 不属于 {@code RESOLVED}/{@code FALSE_ALARM}）。 */
    private long alertCount;

    /** 绑定到本项目测点、且状态判定为 ONLINE 的设备数（口径见 {@code DeviceStatusPolicy}）。 */
    private long onlineDeviceCount;

    /**
     * 各测点<b>最新</b> defo_mm 中绝对值最大者，保留原符号（负向形变同样计入）；
     * 无数据时为 {@code null}。
     *
     * <p>虽与「测项中立化」同处一轮，本字段<b>刻意不中立</b>：名字里的 Deformation 就是口径，
     * 它只统计 {@code defo_mm}，不随主测项切换、也不统计后加的其它测项
     * （{@code rate_mm_d} 也是 mm 量纲，但它答的是「变化多快」而不是「变了多少」）。
     * 为什么不按档案/单位推断、以及真要中立化需要先补什么，见
     * {@code ProjectSummaryService#DEFO_METRIC} 与契约 §3。</p>
     */
    private Double maxDeformationMm;
}
