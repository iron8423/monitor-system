package com.monitor.telemetry.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 测量值（按测项一行；一条消息含 N 测项 -> 拆 N 行，共用 message_id）。
 * 列名对齐 A 的 measurement 表（V1，2026-09-10 对表）：measure_value（非 value）、point_id 与 point_code 并存。
 *
 * <p><b>刻意不继承 {@code BaseEntity}</b>：那张基类带来 {@code deleted}（逻辑删除）与
 * {@code updatedAt}，而 {@code measurement} 表这两个列都没有——继承之后每一条 INSERT
 * 都会因为引用不存在的列而失败。测量表是无软删、只追加的事实表，保持独立。</p>
 */
@Data
@TableName("measurement")
public class Measurement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String deviceId;
    private Long pointId;
    private String pointCode;
    private Long metricId;
    private String metricCode;
    private Long seq;
    private String schemaVersion;
    /** REALTIME=实时状态链路，BACKFILL=仅历史补录。 */
    private String ingestMode;
    private LocalDateTime collectTime;
    private LocalDateTime receiveTime;

    /**
     * **平台写入这一行的时刻**（清单第 10 条）。
     *
     * <p>它**不是**「网关接收时间」：契约（{@code docs/message-contract.md}）与
     * {@code IngestMessage} 里都没有承载网关时间的字段，凭空用 {@code now()} 造一个
     * 只会得到 {@code receive_time} 的副本——一列长得像数据、其实是重复的东西。</p>
     *
     * <p>它给出的唯一可诊断量是 {@code created_at - receive_time} = 平台从收到报文到落库的
     * 处理时延。将来与网关方改契约补上真正的网关时间戳时，**另开一列**，不要动这一列。</p>
     *
     * <p>无需迁移：V1 就建好了这个可空列，只是实体里一直没有它、全仓零写入点。
     * 由 {@code MyMetaObjectHandler.insertFill} 自动填充（{@code ingest_mode} 等
     * 手工赋值的字段不受影响），所以 {@code buildRow} 一行都不用改。</p>
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private Double measureValue;
    private String unit;
    private String quality;
    private String attributes;   // JSON <=1024（position/signal/state）
    private String rawRef;
}
