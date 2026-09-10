package com.monitor.alarm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 警情处置留痕（V1 {@code alarm_action}），一条警情一行，构成详情页时间线。
 * 动作（D5 冻结）：confirm / research / dispatch / handle / resolve / misreport，另有系统动作 trigger。
 */
@Data
@TableName("alarm_action")
public class AlarmAction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long alarmId;
    private String actionType;
    private String operator;
    private String note;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
