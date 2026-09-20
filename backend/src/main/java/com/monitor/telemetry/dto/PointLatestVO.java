package com.monitor.telemetry.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * M1：GET /api/v1/points/{pointId}/latest 响应（《B侧接口契约_M0》§3）。
 * {@code latest} 内含 collectTime、各 metricCode 值（defo_mm/rate_mm_d）、quality、signal、position。
 */
@Data
public class PointLatestVO {

    private Long pointId;
    private String pointCode;
    private Map<String, Object> latest;
    /** 目标状态 normal / disappeared / suspicious（取自 measurement.attributes）。 */
    private String state;
    /**
     * 这个「当前值」是**哪台设备**报的（V25，P1-2）。
     *
     * <p>一个测点可以有多台设备观测，值却只有一个——不告诉调用方来源，
     * 界面上就分不出"这条曲线怎么突然换了个量级"，也解释不了"为什么慢的那台没覆盖快的"。</p>
     */
    private String sourceDeviceCode;
    /** 该来源的优先级（越小越优先；默认 100 = 未特别指定）。 */
    private Integer sourcePriority;
    /** 观测该测点的全部来源；**单来源时省略**（不占响应体积）。 */
    private List<PointSourceVO> sources;
}
