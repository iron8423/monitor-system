package com.monitor.telemetry.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * P2-4：批量 series 响应（一次请求取多个测点的同窗口曲线）。
 *
 * <p>与单点 {@code GET /points/{id}/series} 的关系：单点那份 {@link PointSeriesVO} 原样放在
 * {@code series} 里，字段一个不少（含 baselines），所以消费方可以按同一条逻辑解析。</p>
 */
@Data
public class PointSeriesBatchVO {

    private String metricCode;
    private String from;
    private String to;
    private String granularity;
    /** 窗口两端是否都由服务端默认窗口补出（与单点同口径）。 */
    private Boolean windowDefaulted;

    /** 成功取到（且在数据范围内）的测点，顺序与请求里的 pointIds 一致。 */
    private List<PointSeriesVO> series = new ArrayList<>();

    /**
     * 请求了但**没给结果**的测点：档案里不存在，或不在调用者的数据范围内。
     *
     * <p>单独列出来而不是让整批 403/404：一次回放请求几十上百个点，其中一个点被回收
     * 或越权，不该让整屏曲线消失。同时也不能静默——"少了两个点"和"这个项目只有这些点"
     * 在图上看起来一模一样。</p>
     */
    private List<Long> skippedPointIds = new ArrayList<>();

    /** 请求里重复出现的 id 只取一次（回显实际处理的去重后数量）。 */
    private Integer requestedPoints;

    private String note;
}
