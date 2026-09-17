package com.monitor.telemetry.dto;

import com.monitor.common.exception.BizException;

/**
 * 数据接入用途。
 * REALTIME 会推进当前状态并参与告警；BACKFILL 仅补齐历史记录。
 */
public enum IngestMode {
    REALTIME,
    BACKFILL;

    public static IngestMode parse(String value) {
        if (value == null || value.isBlank()) {
            return REALTIME;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BizException("ingestMode 只允许 REALTIME 或 BACKFILL");
        }
    }
}
