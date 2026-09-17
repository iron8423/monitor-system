package com.monitor.telemetry.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** ingest 响应 data：accepted / rejected / duplicates / results。 */
@Data
public class IngestResult {
    /** 本批请求采用的接入模式：REALTIME / BACKFILL。 */
    private String ingestMode;
    private int accepted = 0;
    private int rejected = 0;
    private int duplicates = 0;
    private List<Item> results = new ArrayList<>();

    @Data
    public static class Item {
        private String pointCode;
        private String collectTime;
        private String status;    // OK / DUPLICATE / REJECTED
        private String quality;
        /** REJECTED 时给转换器/运维看的稳定原因码；成功与重复时为空。 */
        private String reason;
    }
}
