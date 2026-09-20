package com.monitor.telemetry.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登记一次测量基准变更（P1-4）。
 *
 * <p>{@code effectiveFrom} 省略时取"现在"——绝大多数场景就是"我刚换完反射器，从现在起算"。
 * 允许填过去（现场先干活、后补登记是常态），但不允许填未来：一条未来的基准会让
 * "当前基准"变成一个还没生效的东西，而界面上分不出这两者。</p>
 */
@Data
public class MeasurementBaselineRequest {

    /** 生效时刻；省略 = 现在。 */
    private LocalDateTime effectiveFrom;

    /** 原因短码（白名单在 MeasurementBaselineService）。 */
    private String reason;

    @Size(max = 512, message = "备注过长")
    private String note;
}
