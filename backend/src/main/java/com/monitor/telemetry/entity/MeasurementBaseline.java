package com.monitor.telemetry.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 测量基准版本（V24，复查清单 P1-4）：某一时刻起，某个测点的累计形变以新版基准为准。
 *
 * <p>它**不改写历史数据**：表里只记"什么时候、为什么、谁"换了基准，以及换的时候读数是多少。
 * 曲线怎么呈现（画一条基准变更标记、还是按当前基准折算）是**读侧**的事——
 * 改写历史会把"当时看到的就是这个样子"这条审计事实抹掉。</p>
 */
@Data
@TableName("measurement_baseline")
public class MeasurementBaseline {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long pointId;

    /** 这一版基准从哪一刻起生效（含）。 */
    private LocalDateTime effectiveFrom;

    /** 变更原因短码（白名单见 MeasurementBaselineService）。 */
    private String reason;

    private String note;

    /** 换基准时该点的读数：将来"按当前基准折算"用的偏移基准；当时没有数据则为空。 */
    private BigDecimal baselineValueMm;

    private String operator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
