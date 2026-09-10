package com.monitor.telemetry.dto;

import lombok.Data;

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
}
