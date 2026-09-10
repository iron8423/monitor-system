package com.monitor.alarm.dto;

import lombok.Data;

/**
 * 警情处置请求体。{@code operator} 缺省取当前登录用户。
 */
@Data
public class AlarmActionRequest {

    /** confirm / research / dispatch / handle / resolve / misreport */
    private String action;
    private String comment;
    private String operator;
}
