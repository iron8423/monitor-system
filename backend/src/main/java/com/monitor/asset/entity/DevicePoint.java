package com.monitor.asset.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.monitor.common.base.Identifiable;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备-测点绑定（硬删除，无软删除字段）。
 */
@Data
@TableName("device_point")
public class DevicePoint implements Identifiable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;
    private Long pointId;
    private String targetCode;
    /**
     * 来源优先级（V25，复查清单 P1-2）：数值**越小越优先**，默认 100。
     *
     * <p>一个测点可以被多台设备观测，而"当前值"只能有一个。判据是
     * 「优先级最小的来源里、时间最新的一条」——所有来源都是默认值时退化为纯按时间，
     * 与加这一列之前的行为完全一致。详见 {@code MeasurementQueryService#resolveLatestRow}。</p>
     */
    private Integer sourcePriority;
    private BigDecimal azimuthDegrees;
    private BigDecimal elevationDegrees;
    private BigDecimal slantRangeM;
    private BigDecimal reflectorHeightM;
    private Boolean lineOfSight;
    private BigDecimal minimumClearanceM;
    private String calibrationStatus;
    private LocalDateTime calibratedAt;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private String calibrationNote;

    /**
     * 失效痕迹（V15，清单第 09 条）。三者由**同一条**集合式 UPDATE 写入同一个时间戳，
     * 所以这一组三元组本身就是一次失效事件的记录，可以按时间/操作者查询。
     *
     * <p>与 {@code calibrationNote} 的分工：那一列记的是「这条标定当初是**怎么标出来的**」，
     * 是原始标定的唯一记录，**不要在失效时往里追加文字**——那等于毁掉标定依据。
     * 失效是事后事件，记在这里。</p>
     *
     * <p>{@code invalidatedReason} 存短代码（{@code DEVICE_POSE_CHANGED} /
     * {@code POINT_MOVED} / 人工停用时的白名单值），不是散文：它要能被 grep、被前端当枚举渲染。</p>
     */
    private LocalDateTime invalidatedAt;
    private String invalidatedReason;
    private String invalidatedBy;
}
