package com.monitor.asset.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 设置某条设备-测点绑定的**来源优先级**（V25，复查清单 P1-2）。
 *
 * <p>范围 1..999：数值越小越优先，100 是"未特别指定"的默认档。
 * 上限 999 不是技术限制，而是让"默认档"永远有回旋余地——把某台设成 1000 就再也
 * 无法用"比默认更差"来表达它了。</p>
 */
@Data
public class SourcePriorityRequest {

    @NotNull(message = "sourcePriority 不能为空")
    @Min(value = 1, message = "sourcePriority 最小为 1（越小越优先）")
    @Max(value = 999, message = "sourcePriority 最大为 999")
    private Integer sourcePriority;
}
