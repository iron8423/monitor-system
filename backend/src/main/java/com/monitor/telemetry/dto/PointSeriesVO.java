package com.monitor.telemetry.dto;

import lombok.Data;

import java.util.List;

/**
 * M1：GET /api/v1/points/{pointId}/series 响应（《B侧接口契约_M0》§3）。
 */
@Data
public class PointSeriesVO {

    private Long pointId;
    private String pointCode;
    private String metricCode;
    private String unit;
    private List<Item> points;

    /** 曲线点：t = 采集时间（ISO8601 带时区），v = 值。 */
    @Data
    public static class Item {

        private String t;
        private Double v;

        public Item() {
        }

        public Item(String t, Double v) {
            this.t = t;
            this.v = v;
        }
    }
}
