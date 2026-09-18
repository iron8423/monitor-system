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
    /**
     * 本次实际生效的窗口（P0-3）：省略 {@code from}/{@code to} 时补默认窗口，
     * 回显出来才能回答"为什么只有这些点"。带时区，与 {@code points[].t} 同口径。
     */
    private String from;
    private String to;
    /** 窗口两端是否都由服务端默认窗口补出（{@code from}/{@code to} 都没传时为 true）。 */
    private Boolean windowDefaulted;
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
