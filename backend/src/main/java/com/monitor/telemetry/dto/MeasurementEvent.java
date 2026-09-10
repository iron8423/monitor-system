package com.monitor.telemetry.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSE {@code measurement} 事件载荷（《B侧接口契约_M0》§6）。
 *
 * <p>按契约示例<b>扁平</b>展开测项：{@code pointId/pointCode/collectTime/<metricCode>…/quality}。
 * 即 {@code defo_mm}、{@code rate_mm_d} 直接作为顶层字段，不套一层 {@code metrics}——
 * 前端 3D 标点与曲线按 metricCode 直接取值。</p>
 */
public final class MeasurementEvent {

    private MeasurementEvent() {
    }

    public static Map<String, Object> of(Long pointId, String pointCode, String collectTimeIso,
                                         Map<String, Double> metrics, String quality) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("pointId", pointId);
        event.put("pointCode", pointCode);
        event.put("collectTime", collectTimeIso);
        if (metrics != null) {
            for (Map.Entry<String, Double> e : metrics.entrySet()) {
                if (e.getValue() != null) {
                    event.put(e.getKey(), e.getValue());
                }
            }
        }
        event.put("quality", quality);
        return event;
    }
}
